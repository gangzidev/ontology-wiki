package com.ontologywiki.ontology.service.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ontologywiki.common.utils.SecurityUtils;
import com.ontologywiki.ontology.config.OntologyIntegrationProperties;
import com.ontologywiki.ontology.mapper.OntologyAnalysisMapper;
import com.ontologywiki.ontology.service.OntologyArtifactExporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * 本体配置草案服务。
 *
 * <p>职责：
 * <ul>
 *   <li>草案审核：只允许「待审核」流转到通过 / 驳回；</li>
 *   <li>草案导入：调用本体平台导入接口，并记录导入结果；</li>
 *   <li>草案导出：RDF/OWL 或工程包；</li>
 *   <li>版本对比：与同任务上一版逐类比较差异；</li>
 *   <li>沙箱测试：调用平台测试接口并记录回执。</li>
 * </ul>
 *
 * <p>SQL 已下沉至 {@link OntologyAnalysisMapper}，本类只保留外部调用与结果整形。
 */
@Service
public class OntologyProposalService {

    private static final Logger log = LoggerFactory.getLogger(OntologyProposalService.class);

    /** 草案状态 */
    private static final String STATUS_DRAFT = "DRAFT";
    private static final String STATUS_APPROVED = "APPROVED";
    private static final String STATUS_REJECTED = "REJECTED";
    private static final String STATUS_IMPORTED = "IMPORTED";

    /** 导入记录的目标系统标识 */
    private static final String TARGET_PLATFORM = "本体平台";
    private static final String TARGET_SANDBOX = "本体平台沙箱";

    /** 单次对比最多输出的变更条数 */
    private static final int MAX_DIFF_ITEMS = 500;

    /** 参与版本对比的配置分类 */
    private static final List<String> DIFF_CATEGORIES = Arrays.asList(
            "objects", "properties", "identitySpecs", "dataProfiles", "relations", "mappings",
            "derivedProperties", "validationRules", "axioms", "logics", "actions", "serviceFlows",
            "permissionCandidates", "queryPatterns", "terms", "semanticMappings", "governanceRules");

    private final OntologyAnalysisMapper mapper;
    private final OntologyArtifactExporter artifactExporter;
    private final OntologyIntegrationProperties integration;
    private final RestTemplate rest;
    private final ObjectMapper json = new ObjectMapper();

    public OntologyProposalService(OntologyAnalysisMapper mapper,
                                   OntologyArtifactExporter artifactExporter,
                                   OntologyIntegrationProperties integration,
                                   RestTemplate rest) {
        this.mapper = mapper;
        this.artifactExporter = artifactExporter;
        this.integration = integration;
        this.rest = rest;
    }

    /**
     * 审核草案。
     *
     * @param proposalId 草案主键
     * @param decision   审核结论（APPROVED / REJECTED）
     * @param comment    审核意见，驳回时必填
     * @return 提示信息
     * @throws IllegalArgumentException 入参非法时抛出
     * @throws IllegalStateException    草案不存在或已处理时抛出
     */
    public String review(long proposalId, String decision, String comment) {
        String normalizedDecision = decision == null ? "" : decision.trim().toUpperCase(Locale.ROOT);
        String normalizedComment = comment == null ? "" : comment.trim();
        if (!STATUS_APPROVED.equals(normalizedDecision) && !STATUS_REJECTED.equals(normalizedDecision)) {
            throw new IllegalArgumentException("请选择审核通过或驳回");
        }
        if (STATUS_REJECTED.equals(normalizedDecision) && normalizedComment.isEmpty()) {
            throw new IllegalArgumentException("驳回时请填写审核意见");
        }
        int updated = mapper.reviewProposal(proposalId, normalizedDecision,
                clip(normalizedComment, 1000), SecurityUtils.getUsername(), STATUS_DRAFT);
        if (updated == 0) {
            throw new IllegalStateException("草案不存在或已处理，请刷新列表");
        }
        return "审核记录已保存";
    }

    /**
     * 导入草案到本体平台。
     *
     * @param proposalId 草案主键
     * @return 平台响应体
     * @throws IllegalArgumentException 平台未配置或草案未审核通过时抛出
     * @throws IllegalStateException    平台调用失败时抛出
     */
    public String importToPlatform(long proposalId) {
        OntologyIntegrationProperties.Platform platform = integration.getPlatform();
        if (!platform.isEnabled() || isBlank(platform.getBaseUrl()) || isBlank(platform.getImportPath())) {
            throw new IllegalArgumentException("本体平台导入接口未配置");
        }
        Map<String, Object> proposal = mapper.selectProposal(proposalId);
        if (proposal == null) {
            throw new IllegalArgumentException("配置草案不存在");
        }
        if (!STATUS_APPROVED.equalsIgnoreCase(String.valueOf(proposal.get("status")))) {
            throw new IllegalArgumentException("只有审核通过的草案才能导入");
        }
        String username = SecurityUtils.getUsername();
        try {
            JsonNode fullConfig = json.readTree(String.valueOf(proposal.get("config_json")));
            Object payload = fullConfig.path("targetConfig").isObject() ? fullConfig.path("targetConfig") : fullConfig;
            ResponseEntity<String> response = rest.exchange(platform.getBaseUrl() + platform.getImportPath(),
                    HttpMethod.POST, new HttpEntity<>(payload, buildPlatformHeaders(platform)), String.class);
            String status = response.getStatusCode().is2xxSuccessful() ? "SUCCESS" : "FAILED";
            mapper.insertImportRecord(proposalId, TARGET_PLATFORM, status,
                    clip(response.getBody(), 2000), username);
            if ("SUCCESS".equals(status)) {
                mapper.markProposalImported(proposalId, username, STATUS_IMPORTED);
            }
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new IllegalStateException("本体平台返回 " + response.getStatusCodeValue());
            }
            return response.getBody();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            mapper.insertImportRecord(proposalId, TARGET_PLATFORM, "FAILED",
                    "请求失败，请检查导入接口配置和服务状态", username);
            throw new IllegalStateException("本体平台导入失败，请检查接口配置和服务状态", e);
        }
    }

    /**
     * 导出草案为 RDF/OWL 或工程包。
     *
     * @param proposalId 草案主键
     * @param format     导出格式
     * @return 导出产物
     * @throws IllegalArgumentException 格式不支持或草案不存在时抛出
     */
    public OntologyArtifactExporter.Artifact export(long proposalId, String format) {
        Map<String, Object> proposal = mapper.selectProposalForExport(proposalId);
        if (proposal == null) {
            throw new IllegalArgumentException("配置草案不存在");
        }
        try {
            Map<String, Object> config = json.readValue(String.valueOf(proposal.get("config_json")),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
            String label = String.valueOf(proposal.get("proposal_name"));
            Map<String, Object> datasource = Collections.emptyMap();
            Object taskId = proposal.get("task_id");
            if (taskId != null) {
                List<Map<String, Object>> rows = mapper.selectTaskDatasource(((Number) taskId).longValue());
                if (!rows.isEmpty()) {
                    datasource = rows.get(0);
                }
            }
            if ("rdf".equalsIgnoreCase(format) || "owl".equalsIgnoreCase(format)) {
                return artifactExporter.rdf(config, String.valueOf(proposalId), label, value(datasource.get("name")));
            }
            if ("project".equalsIgnoreCase(format) || "tar".equalsIgnoreCase(format)) {
                return artifactExporter.engineeringProject(config, String.valueOf(proposalId), label,
                        SecurityUtils.getUsername(), value(datasource.get("name")),
                        value(datasource.get("schema_name")), value(datasource.get("db_type")));
            }
            throw new IllegalArgumentException("导出格式仅支持 RDF/OWL 或工程 TAR");
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("草案配置不完整或导出失败，请检查对象、属性和关系映射", e);
        }
    }

    /**
     * 对比草案与同任务上一版的差异。
     *
     * @param proposalId 草案主键
     * @return 差异结果
     * @throws IllegalArgumentException 草案不存在或配置无法解析时抛出
     */
    public Map<String, Object> diff(long proposalId) {
        List<Map<String, Object>> currentRows = mapper.selectProposalForDiff(proposalId);
        if (currentRows.isEmpty()) {
            throw new IllegalArgumentException("配置草案不存在");
        }
        Map<String, Object> current = currentRows.get(0);
        if (current.get("task_id") == null) {
            return Collections.singletonMap("message", "手工草案没有可比较的历史版本");
        }
        List<Map<String, Object>> previousRows = mapper.selectPreviousProposal(
                ((Number) current.get("task_id")).longValue(), proposalId);
        if (previousRows.isEmpty()) {
            return Collections.singletonMap("message", "这是该分析任务的第一个版本");
        }
        try {
            Map<String, Object> before = json.readValue(String.valueOf(previousRows.get(0).get("config_json")),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
            Map<String, Object> after = json.readValue(String.valueOf(current.get("config_json")),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
            List<Map<String, Object>> changes = new ArrayList<>();
            for (String key : DIFF_CATEGORIES) {
                diffRows(key, before.get(key), after.get(key), changes);
            }
            Map<?, ?> beforeTaxonomy = before.get("taxonomy") instanceof Map
                    ? (Map<?, ?>) before.get("taxonomy") : Collections.emptyMap();
            Map<?, ?> afterTaxonomy = after.get("taxonomy") instanceof Map
                    ? (Map<?, ?>) after.get("taxonomy") : Collections.emptyMap();
            diffRows("taxonomy.nodes", beforeTaxonomy.get("nodes"), afterTaxonomy.get("nodes"), changes);
            diffRows("taxonomy.edges", beforeTaxonomy.get("edges"), afterTaxonomy.get("edges"), changes);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("previousProposalId", previousRows.get(0).get("proposal_id"));
            result.put("previousProposalName", previousRows.get(0).get("proposal_name"));
            result.put("currentProposalId", proposalId);
            result.put("currentProposalName", current.get("proposal_name"));
            result.put("changes", changes);
            result.put("added", changes.stream().filter(row -> "新增".equals(row.get("change"))).count());
            result.put("removed", changes.stream().filter(row -> "移除".equals(row.get("change"))).count());
            result.put("modified", changes.stream().filter(row -> "修改".equals(row.get("change"))).count());
            return result;
        } catch (Exception e) {
            log.warn("草案版本对比失败 proposalId={}", proposalId, e);
            throw new IllegalArgumentException("配置草案 JSON 无法解析，不能生成版本差异", e);
        }
    }

    /**
     * 沙箱测试草案。
     *
     * @param proposalId 草案主键
     * @return 回执编号、状态与明细
     * @throws IllegalArgumentException 测试地址未配置或草案不存在时抛出
     */
    public Map<String, Object> sandboxTest(long proposalId) {
        OntologyIntegrationProperties.Platform platform = integration.getPlatform();
        if (isBlank(platform.getBaseUrl()) || isBlank(platform.getTestPath())) {
            throw new IllegalArgumentException("本体平台沙箱测试地址未配置");
        }
        List<Map<String, Object>> rows = mapper.selectProposalNameAndConfig(proposalId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("配置草案不存在");
        }
        String status = "FAILED";
        String detail;
        try {
            Map<String, Object> config = json.readValue(String.valueOf(rows.get(0).get("config_json")),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
            Object targetConfig = config.containsKey("targetConfig") ? config.get("targetConfig") : config;
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("mode", "SANDBOX_TEST");
            payload.put("proposalId", proposalId);
            payload.put("proposalName", rows.get(0).get("proposal_name"));
            payload.put("targetConfig", targetConfig);
            ResponseEntity<String> response = rest.exchange(platform.getBaseUrl() + platform.getTestPath(),
                    HttpMethod.POST, new HttpEntity<>(payload, buildPlatformHeaders(platform)), String.class);
            status = sandboxReceiptStatus(response.getBody());
            detail = "HTTP " + response.getStatusCodeValue() + "\n" + value(response.getBody());
        } catch (Exception e) {
            detail = "沙箱请求失败，请检查测试接口契约和服务状态";
        }
        String receiptDetail = clip(detail, 2000);

        // 回执必须先落库再返回编号，保证前端拿到的编号可追溯
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("proposalId", proposalId);
        params.put("targetSystem", TARGET_SANDBOX);
        params.put("status", status);
        params.put("detail", receiptDetail);
        params.put("username", SecurityUtils.getUsername());
        params.put("generatedId", null);
        mapper.insertImportRecordWithKey(params);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("receiptId", params.get("generatedId"));
        result.put("status", status);
        result.put("detail", receiptDetail);
        return result;
    }

    // ==================== 内部方法 ====================

    /** 构造调用本体平台所需的请求头。 */
    private HttpHeaders buildPlatformHeaders(OntologyIntegrationProperties.Platform platform) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (!isBlank(platform.getToken())) {
            headers.setBearerAuth(platform.getToken());
        }
        return headers;
    }

    /** 解析沙箱回执中的状态，无法识别时归为待定。 */
    private String sandboxReceiptStatus(String body) {
        try {
            JsonNode root = json.readTree(body == null ? "{}" : body);
            if (root.path("passed").asBoolean(false) || root.path("success").asBoolean(false)) return "SUCCESS";
            String state = root.path("status").asText(root.path("result").asText("")).trim().toUpperCase(Locale.ROOT);
            if (Arrays.asList("PASS", "PASSED", "SUCCESS", "OK").contains(state)) return "SUCCESS";
            if (Arrays.asList("FAIL", "FAILED", "ERROR", "REJECTED").contains(state)) return "FAILED";
            return "PENDING";
        } catch (Exception e) {
            return "PENDING";
        }
    }

    /** 按身份键把两侧列表转成 Map，便于逐条比较。 */
    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> indexRows(Object value) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        if (!(value instanceof Collection)) return result;
        for (Object item : (Collection<?>) value) {
            if (!(item instanceof Map)) continue;
            Map<String, Object> row = (Map<String, Object>) item;
            Object identity = row.get("id");
            if (identity == null) identity = row.get("name");
            if (identity == null && row.get("table") != null) identity = row.get("table");
            if (identity == null && row.get("objectId") != null && row.get("column") != null) {
                identity = row.get("objectId") + "." + row.get("column");
            }
            if (identity == null && row.get("sourceId") != null && row.get("targetId") != null) {
                identity = row.get("sourceId") + "." + row.get("targetId") + "." + value(row.get("type"));
            }
            if (identity == null && row.get("alias") != null && row.get("targetObjectId") != null) {
                identity = row.get("alias") + "." + row.get("targetObjectId");
            }
            if (identity == null && row.get("type") != null && row.get("tables") != null) {
                identity = row.get("type") + "." + String.valueOf(row.get("tables"));
            }
            String key = identity == null ? String.valueOf(row.hashCode()) : String.valueOf(identity);
            result.put(key, row);
        }
        return result;
    }

    /** 比较某一分类下的新增 / 移除 / 修改。 */
    private void diffRows(String category, Object beforeValue, Object afterValue,
                          List<Map<String, Object>> changes) throws Exception {
        Map<String, Map<String, Object>> before = indexRows(beforeValue);
        Map<String, Map<String, Object>> after = indexRows(afterValue);
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(before.keySet());
        keys.addAll(after.keySet());
        for (String key : keys) {
            if (changes.size() >= MAX_DIFF_ITEMS) return;
            Map<String, Object> oldRow = before.get(key);
            Map<String, Object> newRow = after.get(key);
            String change = oldRow == null ? "新增"
                    : newRow == null ? "移除"
                    : json.valueToTree(oldRow).equals(json.valueToTree(newRow)) ? null : "修改";
            if (change == null) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("category", category);
            item.put("change", change);
            item.put("key", key);
            if (oldRow != null) item.put("before", oldRow);
            if (newRow != null) item.put("after", newRow);
            changes.add(item);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String value(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String clip(String value, int max) {
        return value == null ? "" : value.substring(0, Math.min(value.length(), max));
    }
}

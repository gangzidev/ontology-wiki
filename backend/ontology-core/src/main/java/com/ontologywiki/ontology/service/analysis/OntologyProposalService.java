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
 *   <li>草案完成：记录本体工程师修正完成；</li>
 *   <li>草案导入：调用本体平台导入接口，并记录导入结果；</li>
 *   <li>草案导出：RDF/OWL 或工程包；</li>
 *   <li>版本对比：与同任务上一版逐类比较差异；</li>
 * </ul>
 *
 * <p>SQL 已下沉至 {@link OntologyAnalysisMapper}，本类只保留外部调用与结果整形。
 */
@Service
public class OntologyProposalService {

    private static final Logger log = LoggerFactory.getLogger(OntologyProposalService.class);

    /** 草案状态 */
    private static final String STATUS_DRAFT = "DRAFT";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_IMPORTED = "IMPORTED";

    /** 导入记录的目标系统标识 */
    private static final String TARGET_PLATFORM = "本体平台";

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
     * 标记草案已由本体工程师修正完成。
     *
     * @param proposalId 草案主键
     * @return 提示信息
     * @throws IllegalArgumentException 入参非法时抛出
     * @throws IllegalStateException    草案不存在或已处理时抛出
     */
    public String complete(long proposalId) {
        Map<String, Object> proposal = mapper.selectProposal(proposalId);
        if (proposal == null) {
            throw new IllegalStateException("草案不存在或已处理，请刷新列表");
        }
        if (!STATUS_DRAFT.equalsIgnoreCase(String.valueOf(proposal.get("status")))) {
            throw new IllegalStateException("草案不存在或已处理，请刷新列表");
        }
        try {
            JsonNode config = json.readTree(String.valueOf(proposal.get("config_json")));
            List<String> errors = validateCompletionSemantics(config);
            if (!errors.isEmpty()) {
                throw new IllegalArgumentException(errors.get(0));
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("草案暂不能完成：配置不是有效 JSON，请先修正草案。", e);
        }
        int updated = mapper.completeProposal(proposalId, STATUS_COMPLETED,
                "", SecurityUtils.getUsername(), STATUS_DRAFT);
        if (updated == 0) {
            throw new IllegalStateException("草案不存在或已处理，请刷新列表");
        }
        return "草案已完成";
    }

    /** 从指定历史版本创建一个新的待修正草案，保留当前版本并可继续编辑。 */
    public long rollbackToVersion(long proposalId) {
        Map<String, Object> source = mapper.selectProposal(proposalId);
        if (source == null || source.get("task_id") == null || source.get("config_json") == null) {
            throw new IllegalArgumentException("历史草案不存在或配置为空");
        }
        Map<String, Object> params = new HashMap<>();
        params.put("taskId", ((Number) source.get("task_id")).longValue());
        params.put("proposalName", "回退版本 - " + String.valueOf(source.get("proposal_name")));
        params.put("configJson", String.valueOf(source.get("config_json")));
        params.put("username", SecurityUtils.getUsername());
        mapper.insertProposal(params);
        Object generatedId = params.get("generatedId");
        if (!(generatedId instanceof Number)) throw new IllegalStateException("回退草案创建失败");
        return ((Number) generatedId).longValue();
    }

    /** 草案完成前校验指标及可选维度，质量评测由配置管理员独立执行。 */
    static List<String> validateCompletionSemantics(JsonNode config) {
        JsonNode semantics = mergedSemantics(config);
        JsonNode propertiesNode = config == null ? null : config.path("properties");
        if (propertiesNode == null || !propertiesNode.isArray() || propertiesNode.isEmpty()) {
            propertiesNode = config == null ? null : config.path("targetConfig").path("properties");
        }
        Map<String, JsonNode> properties = index(propertiesNode);
        List<String> errors = new ArrayList<>();
        JsonNode metrics = semantics.path("metrics");
        boolean noMetrics = metrics.isMissingNode() || metrics.isNull() || (metrics.isArray() && metrics.size() == 0);
        Map<String, JsonNode> objects = index(config == null ? null : config.path("objects"));
        boolean detailAvailable = false;
        for (JsonNode property : properties.values()) {
            JsonNode object = objects.get(text(property, "objectId"));
            if (object != null && !text(object, "table").isEmpty() && !text(property, "column").isEmpty()) { detailAvailable = true; break; }
        }
        if (!noMetrics || !detailAvailable) checkCompletionEntries(semantics, "metrics", properties, errors);
        checkCompletionEntries(semantics, "dimensions", properties, errors);
        return errors;
    }

    private static void checkCompletionEntries(JsonNode semantics, String category,
                                               Map<String, JsonNode> properties, List<String> errors) {
        JsonNode entries = semantics.path(category);
        if ("dimensions".equals(category) && (entries.isMissingNode() || entries.isNull()
                || (entries.isArray() && entries.size() == 0))) return;
        if (!entries.isArray() || entries.size() == 0) {
            errors.add("草案暂不能完成：" + ("metrics".equals(category) ? "还没有可统计指标" : "还没有可分组维度")
                    + "，请进入分析问答的“语义与维度”页签补充" + ("metrics".equals(category) ? "指标" : "维度") + "。");
            return;
        }
        for (int i = 0; i < entries.size(); i++) {
            JsonNode entry = entries.get(i);
            String name = text(entry, "name");
            if (name.isEmpty()) name = "第" + (i + 1) + "项";
            if (text(entry, "id").isEmpty()) {
                errors.add("草案暂不能完成：" + ("metrics".equals(category) ? "指标" : "维度") + "“" + name + "”缺少编号，请回分析问答修正。");
                return;
            }
            if (text(entry, "name").isEmpty()) {
                errors.add("草案暂不能完成：" + ("metrics".equals(category) ? "指标" : "维度") + "缺少名称，请回分析问答修正。");
                return;
            }
            boolean computed = "metrics".equals(category)
                    && ("COMPUTED".equalsIgnoreCase(text(entry, "type"))
                    || "COMPUTED".equalsIgnoreCase(text(entry, "aggregation"))
                    || entry.path("formula").isObject());
            if (computed) {
                if (!entry.path("formula").isObject()) {
                    errors.add("草案暂不能完成：指标“" + name + "”缺少有效计算公式，请回分析问答修正。");
                }
                continue;
            }
            String propertyId = text(entry, "propertyId");
            JsonNode property = properties.get(propertyId);
            boolean mapped = property != null && !text(property, "objectId").isEmpty() && !text(property, "column").isEmpty();
            if (!mapped) {
                errors.add("草案暂不能完成：" + ("metrics".equals(category) ? "指标" : "维度") + "“" + name + "”没有绑定有效字段，请回分析问答的“语义与维度”页签修正。");
                return;
            }
            if ("metrics".equals(category)) {
                String aggregation = text(entry, "aggregation").toUpperCase(Locale.ROOT);
                if (!Arrays.asList("COUNT", "SUM", "AVG", "MIN", "MAX").contains(aggregation)) {
                    errors.add("草案暂不能完成：指标“" + name + "”没有有效聚合方式，请回分析问答的“语义与维度”页签修正。");
                    return;
                }
                if (("SUM".equals(aggregation) || "AVG".equals(aggregation))
                        && !isNumeric(text(property, "dataType"))) {
                    errors.add("草案暂不能完成：指标“" + name + "”的" + aggregation + "字段必须是数值类型，请回分析问答修正。");
                    return;
                }
            }
        }
    }

    private static JsonNode mergedSemantics(JsonNode config) {
        com.fasterxml.jackson.databind.node.ObjectNode merged = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        JsonNode target = config == null ? null : config.path("targetConfig");
        copyObject(target == null ? null : target.path("querySemantics"), merged);
        copyObject(target == null ? null : target.path("semanticCatalog"), merged);
        copyObject(config == null ? null : config.path("querySemantics"), merged);
        copyObject(config == null ? null : config.path("semanticCatalog"), merged);
        return merged;
    }

    private static void copyObject(JsonNode source, com.fasterxml.jackson.databind.node.ObjectNode target) {
        if (source != null && source.isObject()) source.fields().forEachRemaining(entry -> target.set(entry.getKey(), entry.getValue()));
    }

    private static Map<String, JsonNode> index(JsonNode entries) {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        if (entries != null && entries.isArray()) {
            for (JsonNode entry : entries) {
                String id = text(entry, "id");
                if (!id.isEmpty()) result.put(id, entry);
            }
        }
        return result;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.path(field);
        return value == null || !value.isValueNode() ? "" : value.asText().trim();
    }

    private static boolean isNumeric(String dataType) {
        return dataType.toUpperCase(Locale.ROOT).matches(".*\\b(TINYINT|SMALLINT|MEDIUMINT|INT|INTEGER|BIGINT|DECIMAL|NUMERIC|FLOAT|DOUBLE|REAL|NUMBER)\\b.*");
    }

    /**
     * 导入草案到本体平台。
     *
     * @param proposalId 草案主键
     * @return 平台响应体
     * @throws IllegalArgumentException 平台未配置或草案未完成时抛出
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
        if (!STATUS_COMPLETED.equalsIgnoreCase(String.valueOf(proposal.get("status")))) {
            throw new IllegalArgumentException("只有草案完成后才能导入");
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

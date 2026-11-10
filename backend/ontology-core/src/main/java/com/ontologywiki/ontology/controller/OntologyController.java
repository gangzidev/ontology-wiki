package com.ontologywiki.ontology.controller;

import java.util.*;
import java.util.Base64;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.ontologywiki.ontology.config.OntologyIntegrationProperties;
import com.ontologywiki.ontology.mapper.LlmSettingsMapper;
import com.ontologywiki.ontology.mapper.McpMapper;
import com.ontologywiki.ontology.mapper.OntologyAnalysisMapper;
import com.ontologywiki.ontology.mapper.WikiAssetMapper;
import com.ontologywiki.ontology.mapper.WikiChatMapper;
import com.ontologywiki.ontology.repository.OntologyResourceRepository;
import com.ontologywiki.ontology.service.CredentialService;
import com.ontologywiki.ontology.service.DataSourceScanner;
import com.ontologywiki.ontology.service.LlmWikiClient;
import com.ontologywiki.ontology.service.OntologyGenerationEngine;
import com.ontologywiki.ontology.service.OntologyArtifactExporter;
import com.ontologywiki.ontology.service.OntologyResourceService;
import com.ontologywiki.ontology.service.analysis.OntologyProposalService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.ontologywiki.common.annotation.Log;
import com.ontologywiki.common.core.controller.BaseController;
import com.ontologywiki.common.core.domain.AjaxResult;
import com.ontologywiki.common.core.page.TableDataInfo;
import com.ontologywiki.common.constant.HttpStatus;
import com.ontologywiki.common.enums.BusinessType;
import com.ontologywiki.common.utils.SecurityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@RestController
@RequestMapping("/ontology")
public class OntologyController extends BaseController {
    private static final Logger log = LoggerFactory.getLogger(OntologyController.class);
    private static final java.util.regex.Pattern QUERY_PATTERN_LINE = java.util.regex.Pattern.compile("(?i)^\\s*(select|insert|update|delete)\\b");

    /** 资产图谱：最多节点数与页面正文截取长度 */
    private static final int GRAPH_NODE_LIMIT = 2000;
    private static final int GRAPH_CONTENT_LENGTH = 20000;

    /** 资产启用状态 */
    private static final String STATUS_ACTIVE = "0";

    /** 分析任务状态 */
    private static final String TASK_RUNNING = "RUNNING";
    private static final String TASK_DONE = "DONE";
    private static final String TASK_FAILED = "FAILED";

    /** 问答历史与会话列表的返回条数 */
    private static final int CHAT_HISTORY_LIMIT = 12;
    private static final int CHAT_MESSAGE_LIMIT = 100;
    private static final int CHAT_SESSION_LIMIT = 50;

    /** 分析上下文最多注入的 Wiki 资料条数 */
    private static final int ANALYZE_DOCUMENT_LIMIT = 100;

    /** 视为「业务规则」的资产类型（按大写匹配） */
    private static final List<String> BUSINESS_RULE_ASSET_TYPES =
            Arrays.asList("RULE", "BUSINESS_RULE", "LOGIC", "业务规则");

    private final CredentialService credentials;
    private final DataSourceScanner scanner;
    private final OntologyIntegrationProperties integration;
    private final RestTemplate rest;
    private final LlmWikiClient llmWiki;
    private final OntologyGenerationEngine generation;
    /** 通用资源增删改查业务，SQL 已下沉到 Repository 与 Mapper */
    private final OntologyResourceService resourceService;
    /** 资产图谱与问答所需的数据访问 */
    private final WikiAssetMapper assetMapper;
    /** 分析任务、草案与数据源的数据访问 */
    private final OntologyAnalysisMapper analysisMapper;
    /** 问答会话与消息数据访问 */
    private final WikiChatMapper chatMapper;
    /** 模型配置与 MCP 令牌数据访问 */
    private final LlmSettingsMapper llmSettingsMapper;
    private final McpMapper mcpMapper;
    /** 草案完成标记、导入、导出与版本对比 */
    private final OntologyProposalService proposalService;
    private final ObjectMapper json = new ObjectMapper();
    @Autowired
    private ThreadPoolTaskExecutor threadPoolTaskExecutor;

    public OntologyController(CredentialService credentials, DataSourceScanner scanner,
            OntologyIntegrationProperties integration, RestTemplate rest, LlmWikiClient llmWiki, OntologyGenerationEngine generation,
            OntologyResourceService resourceService,
            WikiAssetMapper assetMapper, OntologyAnalysisMapper analysisMapper, WikiChatMapper chatMapper,
            LlmSettingsMapper llmSettingsMapper, McpMapper mcpMapper, OntologyProposalService proposalService) {
        this.credentials = credentials; this.scanner = scanner; this.integration = integration; this.rest = rest;
        this.llmWiki = llmWiki; this.generation = generation;
        this.resourceService = resourceService;
        this.assetMapper = assetMapper;
        this.analysisMapper = analysisMapper;
        this.chatMapper = chatMapper;
        this.llmSettingsMapper = llmSettingsMapper;
        this.mcpMapper = mcpMapper;
        this.proposalService = proposalService;
    }

    @PreAuthorize("@ss.hasPermi('ontology:dashboard:list')")
    @GetMapping("/dashboard")
    public AjaxResult dashboard() {
        return AjaxResult.success(resourceService.dashboardCounts());
    }

    @PreAuthorize("@ss.hasPermi('ontology:' + #p0 + ':list')")
    @GetMapping("/{resource}/list")
    public TableDataInfo list(@PathVariable("resource") String resource, @RequestParam(name="pageNum", defaultValue="1") int pageNum,
            @RequestParam(name="pageSize", defaultValue="20") int pageSize, @RequestParam(name="keyword", required=false) String keyword,
            @RequestParam(name="spaceId", required=false) Long spaceId, @RequestParam(name="taskId", required=false) Long taskId) {
        try {
            OntologyResourceRepository.PageResult page = resourceService.page(resource, pageNum, pageSize, keyword, spaceId, taskId);
            TableDataInfo result = new TableDataInfo();
            result.setCode(HttpStatus.SUCCESS);
            result.setMsg("查询成功");
            result.setRows(page.getRows());
            result.setTotal(page.getTotal());
            return result;
        } catch (IllegalArgumentException e) {
            TableDataInfo result = new TableDataInfo();
            result.setCode(HttpStatus.BAD_REQUEST);
            result.setMsg(e.getMessage());
            result.setRows(Collections.emptyList());
            result.setTotal(0);
            return result;
        }
    }

    @PreAuthorize("@ss.hasPermi('ontology:assets:list')")
    @GetMapping("/assets/graph")
    public AjaxResult assetGraph(@RequestParam(name="spaceId", required=false) Long spaceId) {
        // 1. 取节点（Wiki 页面附带正文前缀，用于解析页面间引用）
        List<Map<String,Object>> rows = assetMapper.selectGraphNodes("0", "SYSTEM_INDEX", spaceId, GRAPH_NODE_LIMIT, GRAPH_CONTENT_LENGTH);
        Map<Long,Map<String,Object>> byId = new LinkedHashMap<>();
        Map<String,Long> byName = new HashMap<>();
        List<Map<String,Object>> nodes = new ArrayList<>();
        for (Map<String,Object> row : rows) {
            long id = ((Number) row.get("asset_id")).longValue();
            Map<String,Object> node = new LinkedHashMap<>(); node.put("id", String.valueOf(id)); node.put("assetId", id); node.put("name", row.get("asset_name")); node.put("assetType", row.get("asset_type")); node.put("spaceId", row.get("space_id"));
            byId.put(id, row); byName.putIfAbsent(String.valueOf(row.get("asset_name")).toLowerCase(Locale.ROOT), id); nodes.add(node);
        }
        // 2. 取边：来源标签、页面引用
        List<Map<String,Object>> edges = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Map<String,Object> row : rows) {
            long pageId = ((Number) row.get("asset_id")).longValue();
            String tags = row.get("tags") == null ? "" : String.valueOf(row.get("tags"));
            if (tags.startsWith("SOURCE:")) try {
                long sourceId = Long.parseLong(tags.substring("SOURCE:".length()).trim());
                addAssetGraphEdge(edges, seen, byId, sourceId, pageId, "来源资料");
            } catch (NumberFormatException ignored) { }
            if (!"WIKI_PAGE".equals(row.get("asset_type")) || row.get("content") == null) continue;
            String content = String.valueOf(row.get("content"));
            java.util.regex.Matcher wikiLinks = java.util.regex.Pattern.compile("\\[\\[([^\\]]{1,200})\\]\\]").matcher(content);
            while (wikiLinks.find()) addAssetGraphEdge(edges, seen, byId, byName, wikiLinks.group(1), pageId, "页面引用");
            java.util.regex.Matcher markdownLinks = java.util.regex.Pattern.compile("\\[[^\\]]{1,200}\\]\\(([^)\\s]{1,500})\\)").matcher(content);
            while (markdownLinks.find()) addAssetGraphEdge(edges, seen, byId, byName, markdownLinks.group(1), pageId, "页面引用");
        }
        // 3. 补边：分析沉淀页面与其引用来源
        List<Map<String,Object>> memories = assetMapper.selectAnalysisMemoryLinks("0", "WIKI_PAGE", spaceId);
        for (Map<String,Object> memory : memories) {
            Object raw = memory.get("source_json"); if (raw == null) continue;
            try {
                JsonNode source = json.readTree(String.valueOf(raw));
                JsonNode citations = source.path("citedSources");
                if (!citations.isArray()) continue;
                long pageId = ((Number) memory.get("asset_id")).longValue();
                for (JsonNode citation : citations) if (citation.path("assetId").canConvertToLong()) {
                    addAssetGraphEdge(edges, seen, byId, citation.path("assetId").asLong(), pageId, "分析依据");
                }
            } catch (Exception ignored) { }
        }
        Map<String,Object> result = new LinkedHashMap<>(); result.put("nodes", nodes); result.put("edges", edges);
        return AjaxResult.success(result);
    }

    private void addAssetGraphEdge(List<Map<String,Object>> edges, Set<String> seen, Map<Long,Map<String,Object>> nodes, long sourceId, long targetId, String label) {
        if (sourceId == targetId || !nodes.containsKey(sourceId) || !nodes.containsKey(targetId)) return;
        String key = sourceId + ":" + targetId;
        if (!seen.add(key)) return;
        Map<String,Object> edge = new LinkedHashMap<>(); edge.put("source", String.valueOf(sourceId)); edge.put("target", String.valueOf(targetId)); edge.put("name", label); edges.add(edge);
    }

    private void addAssetGraphEdge(List<Map<String,Object>> edges, Set<String> seen, Map<Long,Map<String,Object>> nodes, Map<String,Long> names, String rawTarget, long sourceId, String label) {
        String target = rawTarget == null ? "" : rawTarget.trim();
        if (target.startsWith("http://") || target.startsWith("https://")) return;
        int query = target.indexOf('?'); if (query >= 0) target = target.substring(0, query);
        int fragment = target.indexOf('#'); if (fragment >= 0) target = target.substring(0, fragment);
        int slash = target.lastIndexOf('/'); if (slash >= 0) target = target.substring(slash + 1);
        if (target.toLowerCase(Locale.ROOT).endsWith(".md")) target = target.substring(0, target.length() - 3);
        try { target = java.net.URLDecoder.decode(target, "UTF-8"); } catch (Exception ignored) { }
        Long targetId = null;
        try { targetId = Long.valueOf(target); } catch (NumberFormatException ignored) { targetId = names.get(target.toLowerCase(Locale.ROOT)); }
        if (targetId != null) addAssetGraphEdge(edges, seen, nodes, sourceId, targetId, label);
    }

    @PreAuthorize("@ss.hasPermi('ontology:' + #p0 + ':query')")
    @GetMapping("/{resource}/{id}")
    public AjaxResult get(@PathVariable("resource") String resource, @PathVariable("id") long id) {
        try {
            Map<String, Object> row = resourceService.find(resource, id);
            return row == null ? AjaxResult.error("记录不存在") : AjaxResult.success(row);
        } catch (IllegalArgumentException e) {
            return AjaxResult.error(e.getMessage());
        }
    }

    @PreAuthorize("@ss.hasPermi('ontology:' + #p0 + ':add')")
    @Log(title = "本体Wiki资源", businessType = BusinessType.INSERT, isSaveRequestData = false, isSaveResponseData = false)
    @Transactional
    @PostMapping("/{resource}")
    public AjaxResult add(@PathVariable("resource") String resource, @RequestBody Map<String,Object> body) {
        try {
            Long newId = resourceService.create(resource, body);
            Map<String,Object> result = new LinkedHashMap<>();
            result.put("id", newId);
            result.put("message", "创建成功");
            return AjaxResult.success(result);
        } catch (IllegalArgumentException e) {
            return AjaxResult.error(e.getMessage());
        }
    }

    @PreAuthorize("@ss.hasPermi('ontology:' + #p0 + ':edit')")
    @Log(title = "本体Wiki资源", businessType = BusinessType.UPDATE, isSaveRequestData = false, isSaveResponseData = false)
    @Transactional
    @PutMapping("/{resource}/{id}")
    public AjaxResult edit(@PathVariable("resource") String resource, @PathVariable("id") long id, @RequestBody Map<String,Object> body) {
        try {
            return toAjax(resourceService.update(resource, id, body));
        } catch (IllegalArgumentException e) {
            return AjaxResult.error(e.getMessage());
        }
    }

    @PreAuthorize("@ss.hasPermi('ontology:proposals:edit')")
    @PostMapping("/proposals/{id}/complete")
    public AjaxResult completeProposal(@PathVariable("id") long id) {
        try {
            return AjaxResult.success(proposalService.complete(id));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return AjaxResult.error(e.getMessage());
        }
    }

    @PreAuthorize("@ss.hasPermi('ontology:' + #p0 + ':remove')")
    @Log(title = "本体Wiki资源", businessType = BusinessType.DELETE, isSaveRequestData = false, isSaveResponseData = false)
    @DeleteMapping("/{resource}/{ids}")
    public AjaxResult remove(@PathVariable("resource") String resource, @PathVariable("ids") String ids) {
        try {
            return toAjax(resourceService.delete(resource, ids));
        } catch (IllegalArgumentException e) {
            return AjaxResult.error(e.getMessage());
        }
    }

    @PreAuthorize("@ss.hasPermi('ontology:datasources:edit')")
    @PostMapping("/datasources/{id}/credential")
    public AjaxResult setCredential(@PathVariable("id") long id, @RequestBody Map<String,String> body) {
        String password = body == null ? null : body.get("password");
        if (password == null || password.trim().isEmpty() || password.length() > 2048) return AjaxResult.error("数据库密码不能为空或过长");
        return toAjax(analysisMapper.updateDatasourceCredential(id, credentials.encrypt(password), SecurityUtils.getUsername()));
    }

    @PreAuthorize("@ss.hasAnyPermi('ontology:datasources:add,ontology:datasources:edit,ontology:datasources:query')")
    @Log(title = "本体数据源连接测试", businessType = BusinessType.OTHER, isSaveRequestData = false, isSaveResponseData = false)
    @PostMapping("/datasources/test")
    public AjaxResult testDatasource(@RequestBody(required=false) Map<String,Object> body) {
        Map<String,Object> source = body == null ? new LinkedHashMap<>() : new LinkedHashMap<>(body);
        String password = source.get("password") == null ? "" : String.valueOf(source.get("password"));
        Object id = source.get("datasource_id");
        if (id == null) id = source.get("id");
        if (id != null && !String.valueOf(id).trim().isEmpty()) {
            Map<String,Object> saved = analysisMapper.selectDatasource(Long.parseLong(String.valueOf(id)));
            saved.forEach((key, value) -> { if (!source.containsKey(key) || source.get(key) == null || String.valueOf(source.get(key)).trim().isEmpty()) source.put(key, value); });
            if (password.isEmpty()) {
                Object encrypted = saved.get("password_enc");
                if (encrypted == null || String.valueOf(encrypted).trim().isEmpty()) return AjaxResult.error("该数据源尚未设置数据库密码");
                password = credentials.decrypt(String.valueOf(encrypted));
            }
        }
        if (!resourceService.isValidJdbcUrl(value(source.get("db_type")), value(source.get("jdbc_url")))) return AjaxResult.error("JDBC URL 无效、与数据库类型不匹配或包含账号密码；请检查地址并将密码单独填写");
        return AjaxResult.success(scanner.testConnection(source, password));
    }

    @PreAuthorize("@ss.hasPermi('ontology:datasources:query')")
    @PostMapping("/datasources/{id}/scan")
    public AjaxResult scanDatasource(@PathVariable("id") long id, @RequestBody(required=false) Map<String,Object> options) {
        Map<String,Object> source = analysisMapper.selectDatasource(id);
        return AjaxResult.success(scanner.scan(source, options == null ? Collections.<String,Object>emptyMap() : options));
    }

    @PreAuthorize("@ss.hasPermi('ontology:proposals:edit')")
    @Log(title = "本体配置草案导入", businessType = BusinessType.IMPORT, isSaveRequestData = false, isSaveResponseData = false)
    @PostMapping("/proposals/{id}/import")
    public AjaxResult importProposal(@PathVariable("id") long id) {
        try {
            return AjaxResult.success(proposalService.importToPlatform(id));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return AjaxResult.error(e.getMessage());
        }
    }

    @PreAuthorize("@ss.hasPermi('ontology:proposals:query')")
    @Log(title = "本体配置草案导出", businessType = BusinessType.EXPORT, isSaveRequestData = false, isSaveResponseData = false)
    @GetMapping("/proposals/{id}/export")
    public ResponseEntity<?> exportProposal(@PathVariable("id") long id, @RequestParam("format") String format) {
        try {
            OntologyArtifactExporter.Artifact artifact = proposalService.export(id, format);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(artifact.getContentType()));
            headers.setContentLength(artifact.getBytes().length);
            headers.setContentDisposition(ContentDisposition.attachment().filename(artifact.getFilename(), java.nio.charset.StandardCharsets.UTF_8).build());
            return new ResponseEntity<>(artifact.getBytes(), headers, org.springframework.http.HttpStatus.OK);
        } catch (IllegalArgumentException e) {
            log.warn("本体草案导出失败，proposalId={}", id, e);
            return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
                    .body(AjaxResult.error(e.getMessage()));
        }
    }

    @PreAuthorize("@ss.hasPermi('ontology:analyses:query')")
    @GetMapping("/analyses/{id}/export")
    public ResponseEntity<?> exportAnalysis(@PathVariable("id") long id, @RequestParam("format") String format) {
        List<Map<String,Object>> rows = analysisMapper.selectLatestProposalId(id);
        if (rows.isEmpty()) return ResponseEntity.status(org.springframework.http.HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON).body(AjaxResult.error("该分析任务还没有可导出的配置草案"));
        return exportProposal(((Number) rows.get(0).get("proposal_id")).longValue(), format);
    }

    @PreAuthorize("@ss.hasPermi('ontology:proposals:query')")
    @GetMapping("/proposals/{id}/diff")
    public AjaxResult proposalDiff(@PathVariable("id") long id) {
        try {
            return AjaxResult.success(proposalService.diff(id));
        } catch (IllegalArgumentException e) {
            return AjaxResult.error(e.getMessage());
        }
    }

    private String value(Object value) { return value == null ? "" : String.valueOf(value); }

    /**
     * 未配置任何可用大模型时返回的专用业务码。
     *
     * <p>前端据此弹窗提示「请先配置模型」，并跳转到模型配置页面；
     * 不复用 500，避免与其他服务端异常混淆。
     */
    private static final int MODEL_NOT_CONFIGURED = 603;

    /** 构造「未配置模型」响应，供所有依赖大模型能力的接口复用。 */
    private AjaxResult modelNotConfigured() {
        return AjaxResult.error(MODEL_NOT_CONFIGURED, "尚未配置可用的大模型，请先在「模型配置」中添加并启用一个模型");
    }

    @PreAuthorize("@ss.hasPermi('ontology:analyses:edit')")
    @PostMapping("/analyses/{id}/analyze")
    public AjaxResult analyze(@PathVariable("id") long id, @RequestBody(required=false) Map<String,String> request) {
        Map<String,Object> task = analysisMapper.selectTask(id);
        if (task == null) return AjaxResult.error("分析任务不存在");
        Object sourceValue = task.get("source_id");
        if (sourceValue == null) return AjaxResult.error("分析任务未配置数据源");
        Long wikiSpaceId = task.get("wiki_space_id") == null ? null : ((Number) task.get("wiki_space_id")).longValue();
        if (wikiSpaceId == null) return AjaxResult.error("该任务未绑定行业知识空间，请先编辑任务并选择行业；分析不会读取其他行业的 Wiki 资料");
        if (chatMapper.countEnabledSpace(wikiSpaceId, STATUS_ACTIVE) == 0) return AjaxResult.error("分析任务关联的行业知识空间不存在或已停用");
        if (!llmWiki.enabled()) return modelNotConfigured();
        Map<String,Object> source = analysisMapper.selectDatasource(((Number) sourceValue).longValue());
        String requestedMode = request == null ? "" : value(request.get("mode")).trim().toUpperCase(Locale.ROOT);
        if (!requestedMode.isEmpty() && !Arrays.asList("FULL", "INCREMENTAL").contains(requestedMode)) return AjaxResult.error("分析模式无效");
        boolean incremental = "INCREMENTAL".equals(requestedMode);
        Set<String> changedTables = incremental ? requestedTableNames(request.get("tables")) : Collections.<String>emptySet();
        Map<String,Object> previousSnapshot = null;
        if (incremental) {
            if (changedTables.isEmpty()) return AjaxResult.error("增量更新请填写至少一个发生变化的表名");
            if (changedTables.size() > 20) return AjaxResult.error("单次增量更新最多选择20张表");
            List<Map<String,Object>> previous = analysisMapper.selectLatestProposalWithConfig(id);
            if (previous.isEmpty()) return AjaxResult.error("该任务还没有历史草案，请先执行一次全量分析");
            try {
                Map<String,Object> config = json.readValue(String.valueOf(previous.get(0).get("config_json")), new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() { });
                Object snapshot = config.get("schemaSnapshot");
                if (!(snapshot instanceof Map)) return AjaxResult.error("历史草案没有可用于增量更新的结构快照，请先执行一次全量分析");
                previousSnapshot = (Map<String,Object>) snapshot;
            } catch (Exception e) { return AjaxResult.error("历史草案结构快照无法解析，请先执行一次全量分析"); }
        }
        // 只在非执行中状态流转，避免并发重复触发
        if (analysisMapper.markTaskRunning(id, "1/4 正在连接数据源，读取表结构和字段…", SecurityUtils.getUsername(), TASK_RUNNING) == 0) return AjaxResult.error("该分析任务正在执行");
        // 在主线程捕获 username，线程池线程无 SecurityContext
        String username = SecurityUtils.getUsername();
        String ask = request == null ? null : request.get("question");
        int sampleLimit = request == null || request.get("sampleLimit") == null ? 20 : Math.max(0, Math.min(Integer.parseInt(request.get("sampleLimit")), 20));
        final Map<String,Object> prevSnapshot = previousSnapshot;
        threadPoolTaskExecutor.execute(() ->
            doRunAnalysis(id, task, source, wikiSpaceId, incremental, changedTables, prevSnapshot, ask, sampleLimit,
                    request == null ? null : request.get("queryPatterns"),
                    request == null ? null : request.get("stages"),
                    request == null ? null : request.get("tables"),
                    username));
        return AjaxResult.success(Collections.singletonMap("async", true));
    }

    /** 分析任务主逻辑，在线程池中异步执行，不持有 SecurityContext。 */
    private void doRunAnalysis(long id, Map<String,Object> task, Map<String,Object> source, Long wikiSpaceId,
            boolean incremental, Set<String> changedTables, Map<String,Object> previousSnapshot,
            String ask, int sampleLimit, String queryPatternsRaw, String stagesRaw, String tablesRaw,
            String username) {
        String prompt;
        Map<String,Object> schema;
        try {
            Map<String,Object> scanOptions = new HashMap<>();
            if (incremental) {
                scanOptions.put("tables", changedTables);
                schema = generation.mergeSchemaSnapshot(previousSnapshot, scanner.scan(source, scanOptions), changedTables);
            } else if (sampleLimit > 0 && tablesRaw != null) {
                scanOptions.put("sampleLimit", sampleLimit);
                scanOptions.put("tables", Arrays.asList(tablesRaw.split(",")));
                schema = scanner.scan(source, scanOptions);
            } else {
                schema = scanner.scan(source, scanOptions);
            }
            updateAnalysisProgress(id, "2/4 已读取 " + schema.get("tableCount") + " 张表，正在生成对象、属性和关系候选…", username);
            prompt = "请基于数据库元数据和确定性候选结果补充本体业务语义。缺乏证据的内容标记为待确认，不要编造。请用中文回答；如有候选，请在回答末尾输出JSON对象，候选使用 objects、properties、relations、logics、actions、terms 数组。所有写动作必须标记高风险并说明依据。\n任务范围：" + scopeForPrompt(task.get("scope")) + "\n工程师问题：" + (ask == null ? "分析该数据库" : ask) + "\n数据库元数据与样例统计：" + json.writeValueAsString(schemaForPrompt(schema));
        } catch (Exception e) {
            String message = e instanceof IllegalArgumentException && e.getMessage() != null ? e.getMessage() : "读取数据源或构建分析请求失败，请检查连接和分析参数";
            analysisMapper.markTaskFailed(id, clip(message, 1000), username, TASK_FAILED);
            return;
        }
        try {
            List<Map<String,Object>> documents = loadWikiDocuments(wikiSpaceId);
            List<Map<String,Object>> businessRules = loadBusinessRules(wikiSpaceId);
            String confirmedKnowledge = llmWiki.confirmedKnowledgeContext(id, wikiSpaceId, ask);
            List<String> queryPatterns = queryPatternsRaw != null
                    ? Arrays.asList(queryPatternsRaw.split("\\r?\\n")) : queryPatternsFromScope(task.get("scope"));
            Set<Integer> stages = parseStages(stagesRaw);
            Map<String,Object> deterministic = generation.generate(schema, documents, businessRules, queryPatterns, stages);
            deterministic.put("schemaSnapshot", generation.schemaMetadataSnapshot(schema));
            updateAnalysisProgress(id, "3/4 候选结构已生成，正在结合 Wiki 资料调用模型补充业务语义…", username);
            // Keep the full deterministic bundle in the proposal, but send only valid compact examples to the LLM.
            prompt = clip(prompt, 60000) + "\n确定性生成引擎候选摘要：" + candidateSummary(deterministic) + "\n请只补充有证据的业务语义，返回分析说明和可选JSON。";
            if (!confirmedKnowledge.isEmpty()) prompt += "\n已确认分析知识（优先参考，若与当前结构冲突请明确提示）：\n" + clip(confirmedKnowledge, 24000);
            Map<String,Object> analysisResponse = llmWiki.enabled()
                    ? Collections.singletonMap("answer", llmWiki.analysisCompletion(prompt))
                    : Collections.singletonMap("answer", "LLM Wiki 未启用，已生成数据库结构候选，请工程师审核。");
            String answer = String.valueOf(analysisResponse.getOrDefault("answer", ""));
            updateAnalysisProgress(id, "4/4 模型分析完成，正在校验并保存待审核配置草案…", username);
            deterministic.put("llmAnswer", answer);
            Map<String, Object> llmSuggestions = parseLlmSuggestions(answer);
            if (!llmSuggestions.isEmpty()) deterministic.put("llmSuggestions", llmSuggestions);
            else if (llmWiki.enabled()) {
                List<Map<String, Object>> warnings = listOfMaps(deterministic.get("warnings"));
                warnings.add(warning("LLM_SUGGESTIONS_UNPARSED", "llmWiki", "LLM Wiki 回复没有可识别的结构化候选，保留文本说明供人工查看"));
                deterministic.put("warnings", warnings);
            }
            if (analysisResponse.containsKey("citedPages")) deterministic.put("citedPages", analysisResponse.get("citedPages"));
            if (analysisResponse.containsKey("citedSources")) deterministic.put("citedSources", analysisResponse.get("citedSources"));
            String config = json.writeValueAsString(deterministic);
            // 草案必须回传主键，后续问答与导入都依赖它
            Map<String, Object> proposalParams = new LinkedHashMap<>();
            proposalParams.put("proposalName", String.valueOf(task.get("task_name")) + " 配置草案");
            proposalParams.put("taskId", id);
            proposalParams.put("configJson", config);
            proposalParams.put("username", username);
            proposalParams.put("generatedId", null);
            analysisMapper.insertProposal(proposalParams);
            llmWiki.saveAnalysisAnswer(id, answer, false, username);
            analysisMapper.markTaskFinished(id, clip(answer, 60000), username, TASK_DONE);
        } catch (Exception e) {
            log.error("本体分析任务执行失败 taskId={}", id, e);
            analysisMapper.markTaskFailed(id, "LLM Wiki 分析失败，请检查外部服务配置和数据源状态", username, TASK_FAILED);
        }
    }

    @PreAuthorize("@ss.hasPermi('ontology:analyses:query')")
    @GetMapping("/analyses/{id}/latest-result")
    public AjaxResult latestAnalysisResult(@PathVariable("id") long id) {
        List<Map<String,Object>> rows = analysisMapper.selectLatestProposalWithConfig(id);
        if (rows.isEmpty()) return AjaxResult.success(Collections.emptyMap());
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("proposalId", rows.get(0).get("proposal_id"));
        result.put("isSavedProposal", true);
        try {
            Map<String,Object> cfg = json.readValue(String.valueOf(rows.get(0).get("config_json")),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() { });
            result.put("proposalConfig", cfg);
            result.put("answer", cfg.get("llmAnswer"));
        } catch (Exception ignored) { }
        return AjaxResult.success(result);
    }

    private void updateAnalysisProgress(long taskId, String message, String username) {
        analysisMapper.updateRunningProgress(taskId, message, username, TASK_RUNNING);
    }

    /** 兼容旧调用（主线程 SecurityContext 可用时）。 */
    private void updateAnalysisProgress(long taskId, String message) {
        analysisMapper.updateRunningProgress(taskId, message, SecurityUtils.getUsername(), TASK_RUNNING);
    }

    @PreAuthorize("@ss.hasPermi('ontology:analyses:query')")
    @GetMapping("/analyses/{id}/knowledge")
    public AjaxResult analysisKnowledge(@PathVariable("id") long id) {
        return AjaxResult.success(llmWiki.listAnalysisMemory(id));
    }

    @PreAuthorize("@ss.hasPermi('ontology:analyses:query')")
    @GetMapping("/analyses/{id}/context")
    public AjaxResult analysisContext(@PathVariable("id") long id, @RequestParam(name="proposalId", required=false) Long proposalId) {
        List<Map<String,Object>> rows = proposalId == null
                ? analysisMapper.selectTaskContext(id)
                : analysisMapper.selectTaskContextByProposal(id, proposalId);
        return rows.isEmpty() ? AjaxResult.error(proposalId == null ? "分析任务不存在" : "配置草案不属于当前分析任务") : AjaxResult.success(rows.get(0));
    }

    @PreAuthorize("@ss.hasPermi('ontology:analyses:edit')")
    @PostMapping("/analyses/{id}/answer/refine")
    public AjaxResult refineAnalysisAnswer(@PathVariable("id") long id, @RequestBody Map<String,Object> request) {
        String answer = request == null || request.get("answer") == null ? "" : String.valueOf(request.get("answer"));
        String question = request == null || request.get("question") == null ? "" : String.valueOf(request.get("question"));
        if (!llmWiki.enabled()) return modelNotConfigured();
        Map<String,Object> result = llmWiki.refineAnalysisAnswer(id, answer, question);
        return AjaxResult.success(result);
    }

    @PreAuthorize("@ss.hasPermi('ontology:analyses:edit')")
    @PutMapping("/analyses/{id}/answer")
    @Transactional
    public AjaxResult saveAnalysisAnswer(@PathVariable("id") long id, @RequestBody Map<String,Object> request) {
        String answer = request == null || request.get("answer") == null ? "" : String.valueOf(request.get("answer"));
        boolean confirm = request != null && Boolean.parseBoolean(String.valueOf(request.get("confirm")));
        Long proposalId = null;
        if (request != null && request.get("proposalId") != null && !String.valueOf(request.get("proposalId")).trim().isEmpty()) {
            proposalId = Long.valueOf(String.valueOf(request.get("proposalId")));
        }
        if (request != null && request.containsKey("analysisChanges")) {
            Object rawChanges = request.get("analysisChanges");
            List<?> changes = rawChanges instanceof List ? (List<?>) rawChanges : Collections.emptyList();
            Map<String,Object> saved = llmWiki.saveAnalysisResult(id, proposalId, answer, changes, SecurityUtils.getUsername());
            return AjaxResult.success(saved);
        }
        Map<String,Object> memory = llmWiki.saveAnalysisAnswer(id, proposalId, answer, confirm, SecurityUtils.getUsername());
        return AjaxResult.success(memory);
    }

    private Set<String> requestedTableNames(String raw) {
        Set<String> names = new LinkedHashSet<>();
        if (raw != null) for (String name : raw.split(",")) if (name != null && !name.trim().isEmpty()) names.add(name.trim().toLowerCase(Locale.ROOT));
        return names;
    }

    static List<String> queryPatternsFromScope(Object scope) {
        List<String> result = new ArrayList<>();
        if (scope == null) return result;
        for (String line : String.valueOf(scope).split("\\r?\\n")) {
            String candidate = line.trim();
            if (candidate.length() <= 500 && QUERY_PATTERN_LINE.matcher(candidate).find()) result.add(candidate);
            if (result.size() == 20) break;
        }
        return result;
    }

    static String scopeForPrompt(Object scope) {
        if (scope == null) return "";
        List<String> lines = new ArrayList<>();
        for (String line : String.valueOf(scope).split("\\r?\\n")) if (!QUERY_PATTERN_LINE.matcher(line.trim()).find()) lines.add(line);
        return String.join("\n", lines).trim();
    }

    @PreAuthorize("@ss.hasPermi('ontology:dashboard:list')")
    @PostMapping("/wiki/ask")
    public AjaxResult askWiki(@RequestBody Map<String,Object> request) {
        if (request == null || request.get("question") == null || String.valueOf(request.get("question")).trim().isEmpty() || String.valueOf(request.get("question")).length() > 4000) return AjaxResult.error("请输入问题，且不超过4000字");
        String question = String.valueOf(request.get("question"));
        String sessionId = request.get("sessionId") == null ? null : String.valueOf(request.get("sessionId")); if (sessionId == null || !sessionId.matches("[A-Za-z0-9_-]{8,64}")) sessionId = UUID.randomUUID().toString().replace("-", "");
        Long spaceId = null;
        Object rawSpaceId = request.get("spaceId");
        String scopeType = request.get("scopeType") == null ? "" : String.valueOf(request.get("scopeType")).trim().toUpperCase(Locale.ROOT);
        Long scopeId = null;
        Object rawScopeId = request.get("scopeId");
        if (scopeType.isEmpty()) scopeType = rawSpaceId == null || String.valueOf(rawSpaceId).trim().isEmpty() ? "ALL" : "SPACE";
        if (!Arrays.asList("ALL", "SPACE", "ASSET", "ANALYSIS").contains(scopeType)) return AjaxResult.error("问答范围无效，请重新选择");
        if ("SPACE".equals(scopeType) || "ASSET".equals(scopeType) || "ANALYSIS".equals(scopeType)) {
            Object idValue = rawScopeId == null ? rawSpaceId : rawScopeId;
            try { if (idValue == null || String.valueOf(idValue).trim().isEmpty()) throw new NumberFormatException(); scopeId = Long.valueOf(String.valueOf(idValue)); }
            catch (NumberFormatException e) { return AjaxResult.error("请先选择具体的 Wiki 空间、资产或分析任务"); }
        }
        if ("SPACE".equals(scopeType)) spaceId = scopeId;
        if ("ASSET".equals(scopeType)) {
            List<Map<String,Object>> assetRows = chatMapper.selectAssetSpace(scopeId, STATUS_ACTIVE);
            if (assetRows.isEmpty()) return AjaxResult.error("所选 Wiki 资产不存在或已停用");
            spaceId = assetRows.get(0).get("space_id") == null ? null : ((Number) assetRows.get(0).get("space_id")).longValue();
        }
        if ("ANALYSIS".equals(scopeType)) {
            List<Map<String,Object>> taskRows = chatMapper.selectTaskSpace(scopeId);
            if (taskRows.isEmpty()) return AjaxResult.error("所选分析任务不存在");
            spaceId = taskRows.get(0).get("wiki_space_id") == null ? null : ((Number) taskRows.get(0).get("wiki_space_id")).longValue();
        }
        Long proposalId = null;
        if ("ANALYSIS".equals(scopeType) && request.get("proposalId") != null && !String.valueOf(request.get("proposalId")).trim().isEmpty()) {
            try { proposalId = Long.valueOf(String.valueOf(request.get("proposalId"))); }
            catch (NumberFormatException e) { return AjaxResult.error("配置草案编号无效，请重新打开草案问答"); }
            if (analysisMapper.countProposalOfTask(proposalId, scopeId) == 0) return AjaxResult.error("所选配置草案不属于当前分析任务");
        }
        if (spaceId != null && chatMapper.countEnabledSpace(spaceId, STATUS_ACTIVE) == 0) return AjaxResult.error("关联 Wiki 空间不存在或已停用");
        // 内置模型未启用时，只有「全部资料」范围且外部智文服务配置齐全才允许回退；
        // 两者都不可用必须返回 603，否则前端只会收到笼统的「请求失败」，无法引导用户去配置模型
        if (!llmWiki.enabled() && !("ALL".equals(scopeType) && zhiwenConfigured())) return modelNotConfigured();
        String username = SecurityUtils.getUsername();
        List<Map<String,Object>> sessionRows = chatMapper.selectSession(sessionId, username);
        Map<String,Object> contextState = Collections.emptyMap();
        if (sessionRows.isEmpty()) {
            chatMapper.insertSession(sessionId, spaceId, scopeType, scopeId, clip(question, 80), username);
        } else {
            Object rawContextState = sessionRows.get(0).get("context_state_json");
            if (rawContextState != null && !String.valueOf(rawContextState).trim().isEmpty()) try {
                contextState = json.readValue(String.valueOf(rawContextState), new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() { });
            } catch (Exception ignored) { contextState = Collections.emptyMap(); }
            Object savedSpace = sessionRows.get(0).get("space_id");
            Long savedSpaceId = savedSpace == null ? null : ((Number) savedSpace).longValue();
            String savedType = String.valueOf(sessionRows.get(0).get("scope_type"));
            Object savedRawId = sessionRows.get(0).get("scope_id");
            Long savedId = savedRawId == null ? null : ((Number) savedRawId).longValue();
            if (!scopeType.equals(savedType) || !Objects.equals(scopeId, savedId)) return AjaxResult.error("当前会话已固定在其他知识范围，请新建问答后切换范围");
            spaceId = savedSpaceId;
            chatMapper.touchSession(sessionId, username);
        }
        List<Map<String,Object>> history = chatMapper.selectRecentMessages(sessionId, username, CHAT_HISTORY_LIMIT);
        Collections.reverse(history);
        chatMapper.insertMessage(sessionId, "user", question, username);
        try {
            Map<String,Object> wikiResponse = llmWiki.enabled()
                    ? llmWiki.queryResult(question, Boolean.parseBoolean(String.valueOf(request.get("saveAsPage"))), spaceId, history, scopeType, scopeId, proposalId,
                            request.get("analysisChanges") instanceof List ? (List<?>) request.get("analysisChanges") : Collections.emptyList(), contextState, "ANALYSIS".equals(scopeType))
                    : Collections.singletonMap("answer", askZhiwen(question, sessionId));
            String answer = String.valueOf(wikiResponse.getOrDefault("answer", ""));
            // 助手消息需要主键，才能写入引用关系
            Map<String, Object> messageParams = new LinkedHashMap<>();
            messageParams.put("sessionId", sessionId);
            messageParams.put("role", "assistant");
            messageParams.put("content", answer);
            messageParams.put("username", username);
            messageParams.put("generatedId", null);
            chatMapper.insertMessageWithKey(messageParams);
            Object messageId = messageParams.get("generatedId");
            if (messageId instanceof Number && wikiResponse.get("citationIds") instanceof List) {
                for (Object citedId : (List<?>) wikiResponse.get("citationIds")) if (citedId instanceof Number) chatMapper.insertCitation(((Number) messageId).longValue(), ((Number) citedId).longValue());
            }
            if (wikiResponse.get("contextState") instanceof Map) try {
                chatMapper.updateSessionContext(sessionId, json.writeValueAsString(wikiResponse.get("contextState")), username);
            } catch (Exception e) { log.warn("保存问答会话焦点状态失败，后续轮次将重新定位候选", e); }
            Map<String,Object> result = new LinkedHashMap<>(); result.put("answer", answer); result.put("sessionId", sessionId);
            if (wikiResponse.containsKey("citedPages")) result.put("citedPages", wikiResponse.get("citedPages"));
            if (wikiResponse.containsKey("citedSources")) result.put("citedSources", wikiResponse.get("citedSources"));
            if (wikiResponse.containsKey("savedToWiki")) result.put("savedToWiki", wikiResponse.get("savedToWiki"));
            if (wikiResponse.containsKey("pageTitle")) result.put("pageTitle", wikiResponse.get("pageTitle"));
            if (wikiResponse.containsKey("analysisConfig")) result.put("analysisConfig", wikiResponse.get("analysisConfig"));
            if (wikiResponse.containsKey("analysisChanges")) result.put("analysisChanges", wikiResponse.get("analysisChanges"));
            if (wikiResponse.containsKey("analysisUpdatedChanges")) result.put("analysisUpdatedChanges", wikiResponse.get("analysisUpdatedChanges"));
            return AjaxResult.success(result);
        }
        catch (Exception e) { return AjaxResult.error("LLM Wiki 请求失败，请检查外部服务配置和服务状态"); }
    }

    @PreAuthorize("@ss.hasPermi('ontology:dashboard:list')")
    @GetMapping("/wiki/sessions")
    public AjaxResult wikiSessions() {
        return AjaxResult.success(chatMapper.listSessions(SecurityUtils.getUsername(), CHAT_SESSION_LIMIT));
    }

    @PreAuthorize("@ss.hasPermi('ontology:dashboard:list')")
    @GetMapping("/wiki/messages")
    public AjaxResult wikiMessages(@RequestParam("sessionId") String sessionId) {
        if (sessionId == null || !sessionId.matches("[A-Za-z0-9_-]{8,64}")) return AjaxResult.error("会话编号无效");
        List<Map<String,Object>> messages = chatMapper.selectSessionMessages(sessionId, SecurityUtils.getUsername(), CHAT_MESSAGE_LIMIT);
        if (!messages.isEmpty()) {
            // 一次批量取引用，再按消息归组，避免逐条查询
            List<Long> ids = new ArrayList<>();
            for (Map<String,Object> message : messages) ids.add(((Number) message.get("message_id")).longValue());
            Map<Long,List<Map<String,Object>>> sourcesByMessage = new HashMap<>();
            for (Map<String,Object> row : chatMapper.selectCitations(ids)) {
                long messageId = ((Number) row.get("message_id")).longValue();
                Map<String,Object> source = new LinkedHashMap<>(); source.put("assetId", ((Number) row.get("asset_id")).longValue()); source.put("title", row.get("asset_name")); source.put("assetType", row.get("asset_type"));
                sourcesByMessage.computeIfAbsent(messageId, key -> new ArrayList<>()).add(source);
            }
            for (Map<String,Object> message : messages) message.put("sources", sourcesByMessage.getOrDefault(((Number) message.get("message_id")).longValue(), Collections.<Map<String,Object>>emptyList()));
        }
        return AjaxResult.success(messages);
    }

    @PreAuthorize("@ss.hasPermi('ontology:dashboard:list')")
    @GetMapping("/wiki/status")
    public AjaxResult wikiStatus() {
        return AjaxResult.success(llmWiki.health());
    }

    // ─── 多源模型配置管理 ───────────────────────────────────────────────────────

    @PreAuthorize("@ss.hasPermi('ontology:llm:list')")
    @GetMapping("/wiki/llm-configs")
    public AjaxResult listLlmConfigs() {
        return AjaxResult.success(llmSettingsMapper.selectAll());
    }

    @PreAuthorize("@ss.hasPermi('ontology:llm:list')")
    @PostMapping("/wiki/llm-configs")
    public AjaxResult addLlmConfig(@RequestBody Map<String,Object> body) {
        try {
            String name = clip(String.valueOf(body.getOrDefault("settingName","新配置")),100);
            String providerType = validProviderType(String.valueOf(body.getOrDefault("providerType","OPENAI_COMPAT")));
            String usageScene   = validUsageScene(String.valueOf(body.getOrDefault("usageScene","ALL")));
            String baseUrl      = clip(String.valueOf(body.getOrDefault("apiBaseUrl","")),1000);
            String model        = clip(String.valueOf(body.getOrDefault("model","")),200);
            boolean enabled     = Boolean.parseBoolean(String.valueOf(body.getOrDefault("enabled","true")));
            if (baseUrl.trim().isEmpty()) return AjaxResult.error("API 地址不能为空");
            if (model.trim().isEmpty()) return AjaxResult.error("模型名称不能为空");
            String rawKey = body.get("apiKey") == null ? null : String.valueOf(body.get("apiKey")).trim();
            String keyEnc = (rawKey == null || rawKey.isEmpty() || "OLLAMA".equals(providerType)) ? null : credentials.encrypt(rawKey);

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("settingName", name);
            params.put("providerType", providerType);
            params.put("usageScene", usageScene);
            params.put("enabled", enabled);
            params.put("apiBaseUrl", baseUrl);
            params.put("apiKeyEnc", keyEnc);
            params.put("model", model);
            params.put("username", SecurityUtils.getUsername());
            params.put("generatedId", null);
            llmSettingsMapper.insertConfig(params);
            return AjaxResult.success(Collections.singletonMap("id", params.get("generatedId")));
        } catch (Exception e) { return AjaxResult.error(e.getMessage() == null ? "保存失败" : e.getMessage()); }
    }

    @PreAuthorize("@ss.hasPermi('ontology:llm:list')")
    @PutMapping("/wiki/llm-configs/{id}")
    public AjaxResult updateLlmConfig(@PathVariable("id") long id, @RequestBody Map<String,Object> body) {
        try {
            String name         = clip(String.valueOf(body.getOrDefault("settingName","新配置")),100);
            String providerType = validProviderType(String.valueOf(body.getOrDefault("providerType","OPENAI_COMPAT")));
            String usageScene   = validUsageScene(String.valueOf(body.getOrDefault("usageScene","ALL")));
            String baseUrl      = clip(String.valueOf(body.getOrDefault("apiBaseUrl","")),1000);
            String model        = clip(String.valueOf(body.getOrDefault("model","")),200);
            boolean enabled     = Boolean.parseBoolean(String.valueOf(body.getOrDefault("enabled","true")));
            if (baseUrl.trim().isEmpty()) return AjaxResult.error("API 地址不能为空");
            if (model.trim().isEmpty()) return AjaxResult.error("模型名称不能为空");
            String rawKey = body.get("apiKey") == null ? null : String.valueOf(body.get("apiKey")).trim();
            boolean clearKey = Boolean.parseBoolean(String.valueOf(body.getOrDefault("clearApiKey","false")));

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("id", id);
            params.put("settingName", name);
            params.put("providerType", providerType);
            params.put("usageScene", usageScene);
            params.put("enabled", enabled);
            params.put("apiBaseUrl", baseUrl);
            params.put("model", model);
            params.put("username", SecurityUtils.getUsername());
            // 三种情况：显式清空、换新密钥、保留原密钥
            if (clearKey) {
                llmSettingsMapper.updateConfigClearKey(params);
            } else if (rawKey != null && !rawKey.isEmpty() && !"OLLAMA".equals(providerType)) {
                params.put("apiKeyEnc", credentials.encrypt(rawKey));
                llmSettingsMapper.updateConfigWithKey(params);
            } else {
                llmSettingsMapper.updateConfigKeepKey(params);
            }
            return AjaxResult.success();
        } catch (Exception e) { return AjaxResult.error(e.getMessage() == null ? "保存失败" : e.getMessage()); }
    }

    @PreAuthorize("@ss.hasPermi('ontology:llm:list')")
    @DeleteMapping("/wiki/llm-configs/{id}")
    public AjaxResult deleteLlmConfig(@PathVariable("id") long id) {
        llmSettingsMapper.deleteById(id);
        return AjaxResult.success();
    }

    @PreAuthorize("@ss.hasPermi('ontology:llm:list')")
    @PostMapping("/wiki/llm-configs/{id}/test")
    public AjaxResult testLlmConfig(@PathVariable("id") long id) {
        try {
            List<Map<String,Object>> rows = llmSettingsMapper.selectById(id);
            if (rows.isEmpty()) return AjaxResult.error("配置不存在");
            Map<String,Object> row = rows.get(0);
            String baseUrl = String.valueOf(row.get("api_base_url"));
            String apiKey  = row.get("api_key_enc") == null ? "" : credentials.decrypt(String.valueOf(row.get("api_key_enc")));
            return AjaxResult.success(testModelConnection(baseUrl, apiKey, String.valueOf(row.get("model"))));
        } catch (Exception e) {
            Map<String,Object> result = new LinkedHashMap<>(); result.put("connected", false); result.put("message", e.getMessage());
            return AjaxResult.success(result);
        }
    }

    /**
     * 测试尚未保存的模型配置连接。
     * 编辑态若未重新输入密钥，可复用已保存密钥（按 id 读取），避免每次测试都要重新粘贴。
     */
    @PreAuthorize("@ss.hasPermi('ontology:llm:list')")
    @PostMapping("/wiki/llm-configs/test")
    public AjaxResult testLlmConfigDraft(@RequestBody(required=false) Map<String,Object> body) {
        Map<String,Object> input = body == null ? new LinkedHashMap<>() : body;
        String baseUrl = clip(String.valueOf(input.getOrDefault("apiBaseUrl","")),1000).trim();
        String model = clip(String.valueOf(input.getOrDefault("model","")),200).trim();
        if (baseUrl.isEmpty()) return AjaxResult.error("请先填写接口地址");
        if (model.isEmpty()) return AjaxResult.error("请先填写模型名称");
        String apiKey = input.get("apiKey") == null ? "" : String.valueOf(input.get("apiKey")).trim();
        boolean clearKey = Boolean.parseBoolean(String.valueOf(input.getOrDefault("clearApiKey","false")));
        Object id = input.get("id");
        if (apiKey.isEmpty() && !clearKey && id != null && !String.valueOf(id).trim().isEmpty()) {
            List<Map<String,Object>> rows = llmSettingsMapper.selectById(Long.parseLong(String.valueOf(id)));
            if (!rows.isEmpty() && rows.get(0).get("api_key_enc") != null) {
                apiKey = credentials.decrypt(String.valueOf(rows.get(0).get("api_key_enc")));
            }
        }
        return AjaxResult.success(testModelConnection(baseUrl, apiKey, model));
    }

    /** 调用模型的 /models 接口验证地址、网络与密钥是否可用，异常统一转为 connected=false 返回 */
    private Map<String,Object> testModelConnection(String baseUrl, String apiKey, String model) {
        Map<String,Object> result = new LinkedHashMap<>();
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (apiKey != null && !apiKey.trim().isEmpty()) headers.setBearerAuth(apiKey.trim());
            String uri = baseUrl.endsWith("/") ? baseUrl + "models" : baseUrl + "/models";
            ResponseEntity<String> resp = rest.exchange(java.net.URI.create(uri), HttpMethod.GET,
                    new HttpEntity<>(headers), String.class);
            result.put("connected", true);
            result.put("model", model);
            try { result.put("response", json.readTree(resp.getBody())); } catch (Exception ignored) { result.put("response", resp.getBody()); }
        } catch (Exception e) {
            result.put("connected", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    private String validProviderType(String v) {
        if (v == null) return "OPENAI_COMPAT";
        switch (v.toUpperCase(Locale.ROOT)) { case "OLLAMA": return "OLLAMA"; case "LOCAL_COMPAT": return "LOCAL_COMPAT"; default: return "OPENAI_COMPAT"; }
    }

    private String validUsageScene(String v) {
        if (v == null) return "ALL";
        switch (v.toUpperCase(Locale.ROOT)) { case "ANALYSIS": return "ANALYSIS"; case "WIKI": return "WIKI"; case "FAST": return "FAST"; default: return "ALL"; }
    }

    // ─── MCP Token 管理 ────────────────────────────────────────────────────────

    @PreAuthorize("@ss.hasPermi('ontology:llm:list')")
    @GetMapping("/wiki/mcp-tokens")
    public AjaxResult listMcpTokens() {
        return AjaxResult.success(mcpMapper.listTokens(SecurityUtils.getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('ontology:llm:list')")
    @PostMapping("/wiki/mcp-tokens")
    public AjaxResult createMcpToken(@RequestBody(required=false) Map<String,Object> body) {
        try {
            String rawName = body == null || body.get("tokenName") == null ? "" : String.valueOf(body.get("tokenName")).trim();
            // 名称约束与前端保持一致：非空、2–50 字符、只允许常见可读字符
            if (rawName.isEmpty()) return AjaxResult.error("请输入令牌名称");
            if (rawName.length() < 2 || rawName.length() > 50) return AjaxResult.error("令牌名称长度需为 2–50 个字符");
            if (!rawName.matches("[\\p{L}\\p{N} ._()（）-]+")) return AjaxResult.error("令牌名称只能包含中英文、数字、空格及 . _ - ( )");
            String name = clip(rawName, 100);
            // 生成随机 Token：32 字节 Base64url
            byte[] raw = new byte[32]; new java.security.SecureRandom().nextBytes(raw);
            String token = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
            // 只存 SHA-256 摘要，明文仅在本次响应中返回一次
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            String hash = bytesToHex(md.digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            mcpMapper.insertToken(name, hash, SecurityUtils.getUsername());
            Map<String,Object> result = new LinkedHashMap<>(); result.put("token", token); result.put("note","请立即复制，此 Token 不会再次显示"); return AjaxResult.success(result);
        } catch (Exception e) { return AjaxResult.error("生成 Token 失败"); }
    }

    @PreAuthorize("@ss.hasPermi('ontology:llm:list')")
    @DeleteMapping("/wiki/mcp-tokens/{id}")
    public AjaxResult deleteMcpToken(@PathVariable("id") long id) {
        mcpMapper.deleteToken(id, SecurityUtils.getUsername());
        return AjaxResult.success();
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    @PreAuthorize("@ss.hasPermi('ontology:assets:add')")
    @PostMapping("/wiki/sources/url")
    public AjaxResult addWikiUrlSource(@RequestBody Map<String, String> request) {
        if (request == null || request.get("url") == null || request.get("url").trim().isEmpty()) return AjaxResult.error("请输入来源 URL");
        try { return AjaxResult.success(llmWiki.addUrlSource(request.get("url").trim())); }
        catch (Exception e) { return AjaxResult.error(e.getMessage()); }
    }

    @PreAuthorize("@ss.hasAnyPermi('ontology:assets:add,ontology:assets:edit')")
    @PostMapping("/wiki/assets/{id}/ingest")
    public AjaxResult ingestWikiAsset(@PathVariable("id") long id) {
        if (!llmWiki.enabled()) return modelNotConfigured();
        try { return AjaxResult.success(llmWiki.ingestAsset(id)); }
        catch (Exception e) { return AjaxResult.error("Wiki 整理失败，请检查模型配置和资料内容"); }
    }

    @PreAuthorize("@ss.hasPermi('ontology:assets:query')")
    @GetMapping("/wiki/pages")
    public AjaxResult wikiPages() { try { return AjaxResult.success(llmWiki.listPages()); } catch (Exception e) { return AjaxResult.error(e.getMessage()); } }

    @PreAuthorize("@ss.hasPermi('ontology:assets:query')")
    @GetMapping("/wiki/pages/{slug}")
    public AjaxResult wikiPage(@PathVariable("slug") String slug) { try { return AjaxResult.success(llmWiki.getPage(slug)); } catch (Exception e) { return AjaxResult.error(e.getMessage()); } }

    @PreAuthorize("@ss.hasPermi('ontology:assets:add')")
    @PostMapping("/wiki/pages")
    public AjaxResult createWikiPage(@RequestBody Map<String, String> request) { try { return AjaxResult.success(llmWiki.createPage(request)); } catch (Exception e) { return AjaxResult.error(e.getMessage()); } }

    @PreAuthorize("@ss.hasPermi('ontology:assets:edit')")
    @PutMapping("/wiki/pages/{slug}")
    public AjaxResult updateWikiPage(@PathVariable("slug") String slug, @RequestBody Map<String, String> request) { try { return AjaxResult.success(llmWiki.updatePage(slug, request)); } catch (Exception e) { return AjaxResult.error(e.getMessage()); } }

    @PreAuthorize("@ss.hasPermi('ontology:assets:remove')")
    @DeleteMapping("/wiki/pages/{slug}")
    public AjaxResult deleteWikiPage(@PathVariable("slug") String slug) { try { return AjaxResult.success(llmWiki.deletePage(slug)); } catch (Exception e) { return AjaxResult.error(e.getMessage()); } }

    @PreAuthorize("@ss.hasPermi('ontology:assets:query')")
    @GetMapping("/wiki/files/tree")
    public AjaxResult wikiFileTree() { try { return AjaxResult.success(llmWiki.fileTree()); } catch (Exception e) { return AjaxResult.error(e.getMessage()); } }

    @PreAuthorize("@ss.hasPermi('ontology:assets:query')")
    @GetMapping("/wiki/categories/{category}")
    public AjaxResult wikiCategory(@PathVariable("category") String category) { try { return AjaxResult.success(llmWiki.categoryPages(category)); } catch (Exception e) { return AjaxResult.error(e.getMessage()); } }

    @PreAuthorize("@ss.hasPermi('ontology:assets:query')")
    @GetMapping("/wiki/index")
    public AjaxResult wikiIndex() { try { return AjaxResult.success(llmWiki.index()); } catch (Exception e) { return AjaxResult.error(e.getMessage()); } }

    @PreAuthorize("@ss.hasPermi('ontology:assets:query')")
    @GetMapping("/wiki/log")
    public AjaxResult wikiLog() { try { return AjaxResult.success(llmWiki.log()); } catch (Exception e) { return AjaxResult.error(e.getMessage()); } }

    @PreAuthorize("@ss.hasPermi('ontology:assets:query')")
    @GetMapping("/wiki/files/content")
    public AjaxResult wikiFileContent(@RequestParam("path") String path) { try { return AjaxResult.success(llmWiki.fileContent(path)); } catch (Exception e) { return AjaxResult.error(e.getMessage()); } }

    /** 外部智文服务是否可用：开关打开且地址、HMAC 凭据齐全 */
    private boolean zhiwenConfigured() {
        OntologyIntegrationProperties.Zhiwen z = integration.getZhiwen();
        return z.isEnabled()
                && z.getBaseUrl() != null && !z.getBaseUrl().trim().isEmpty()
                && z.getSecretId() != null && !z.getSecretId().trim().isEmpty()
                && z.getSecretKey() != null && !z.getSecretKey().trim().isEmpty();
    }

    private String askZhiwen(String question, String priorSession) {
        OntologyIntegrationProperties.Zhiwen zhiwen = integration.getZhiwen();
        if (!zhiwen.isEnabled() || zhiwen.getBaseUrl() == null || zhiwen.getBaseUrl().trim().isEmpty()) throw new IllegalStateException("智文/LLM Wiki API 未配置");
        if (zhiwen.getSecretId() == null || zhiwen.getSecretKey() == null || zhiwen.getSecretId().trim().isEmpty() || zhiwen.getSecretKey().trim().isEmpty()) throw new IllegalStateException("智文 HMAC 凭据未配置");
        String path = zhiwen.getPath();
        String date = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC));
        String canonical = zhiwen.getSecretId() + "\nPOST " + path + "\ndate: " + date + "\nx-tenantid: " + zhiwen.getTenantId() + "\nx-userid: " + zhiwen.getUserId() + "\nx-source: " + zhiwen.getSource() + "\n";
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(zhiwen.getSecretKey().getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            String signature = Base64.getEncoder().encodeToString(mac.doFinal(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            String auth = "Signature keyId=\"" + zhiwen.getSecretId() + "\",algorithm=\"hmac-sha256\",headers=\"@request-target date x-tenantid x-userid x-source\",signature=\"" + signature + "\"";
            Map<String,Object> payload = new LinkedHashMap<>(); payload.put("query", question); payload.put("content", question); payload.put("userId", zhiwen.getUserId()); payload.put("sessionId", priorSession == null ? UUID.randomUUID().toString() : priorSession); payload.put("messageId", UUID.randomUUID().toString()); payload.put("chatType", "chat"); payload.put("agentCode", zhiwen.getAgentCode()); payload.put("workflowId", zhiwen.getWorkflowId()); payload.put("tenantId", zhiwen.getTenantId()); payload.put("requestTime", System.currentTimeMillis()); payload.put("extraData", Collections.singletonMap("range", "all"));
            HttpHeaders headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON); headers.set("Date", date); headers.set("x-tenantid", zhiwen.getTenantId()); headers.set("x-userid", zhiwen.getUserId()); headers.set("x-source", zhiwen.getSource()); headers.set("Authorization", auth); headers.set("X-Request-Type", "sync");
            String response = rest.postForObject(zhiwen.getBaseUrl() + path, new HttpEntity<>(payload, headers), String.class);
            com.fasterxml.jackson.databind.JsonNode root = json.readTree(response); com.fasterxml.jackson.databind.JsonNode answerNode=root.path("answer").path("content"); if (answerNode.isMissingNode() || answerNode.isNull()) answerNode=root.path("answer"); if ((answerNode.isMissingNode() || answerNode.isNull()) && root.path("answers").isArray() && root.path("answers").size() > 0) answerNode=root.path("answers").get(0).path("content"); return answerNode.isMissingNode() || answerNode.isNull() ? response : (answerNode.isTextual() ? answerNode.asText() : answerNode.toString());
        } catch (Exception e) { throw new IllegalStateException("LLM Wiki 请求失败：" + e.getMessage(), e); }
    }

    private String extractJson(String answer) {
        if (answer == null) return null;
        String candidate = answer.trim().replaceFirst("(?s)^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        int start = candidate.indexOf('{'), end = candidate.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        candidate = candidate.substring(start, end + 1);
        try { return json.readTree(candidate).toString(); } catch (Exception e) { return null; }
    }

    private Map<String, Object> parseLlmSuggestions(String answer) {
        String raw = extractJson(answer); if (raw == null) return Collections.emptyMap();
        if (raw.length() > 100000) return Collections.emptyMap();
        try {
            Map<String, Object> parsed = json.readValue(raw, Map.class);
            Map<String, Object> accepted = new LinkedHashMap<>();
            for (String key : Arrays.asList("objects", "properties", "relations", "logics", "actions", "terms")) {
                if (!(parsed.get(key) instanceof Collection)) continue;
                List<Map<String, Object>> items = new ArrayList<>();
                for (Object item : (Collection<?>) parsed.get(key)) {
                    if (!(item instanceof Map) || items.size() >= 100) continue;
                    Map<String, Object> clean = new LinkedHashMap<>();
                    Map<?, ?> fields = (Map<?, ?>) item;
                    for (String field : Arrays.asList("id", "name", "description", "technicalName", "objectId", "sourceObjectId", "targetObjectId", "operation", "expression", "risk", "evidence", "confidence")) {
                        Object value = fields.get(field);
                        if (value instanceof String) clean.put(field, clip((String) value, 4000));
                        else if (value instanceof Number || value instanceof Boolean) clean.put(field, value);
                    }
                    if (clean.containsKey("name") || clean.containsKey("expression")) {
                        clean.put("source", "LLM_WIKI"); clean.put("status", "NEEDS_REVIEW"); clean.put("reviewRequired", true);
                        if ("actions".equals(key) && !"SELECT".equalsIgnoreCase(String.valueOf(clean.get("operation")))) clean.put("risk", "HIGH");
                        items.add(clean);
                    }
                }
                accepted.put(key, items);
            }
            return accepted;
        } catch (Exception ignored) { return Collections.emptyMap(); }
    }

    private Map<String, Object> schemaForPrompt(Map<String, Object> schema) {
        if (schema == null) return Collections.emptyMap();
        Map<String, Object> out = new LinkedHashMap<>(schema);
        List<Map<String, Object>> tables = new ArrayList<>();
        for (Map<String, Object> table : listOfMaps(schema.get("tables"))) {
            Map<String, Object> copy = new LinkedHashMap<>(table);
            copy.remove("samples");
            tables.add(copy);
        }
        out.put("tables", tables); return out;
    }

    private String candidateSummary(Map<String,Object> candidates) {
        Map<String,Object> summary = new LinkedHashMap<>();
        Map<String,String[]> fields = new LinkedHashMap<>();
        fields.put("objects", new String[]{"id","name","table","description","confidence","status"});
        fields.put("properties", new String[]{"name","objectId","column","dataType","description"});
        fields.put("relations", new String[]{"name","sourceObjectId","targetObjectId","sourceColumn","targetColumn"});
        fields.put("logics", new String[]{"name","expression","executable","status"});
        fields.put("actions", new String[]{"name","objectId","operation","transactional","risk","status"});
        fields.put("terms", new String[]{"term","name","status","reason"});
        fields.put("warnings", new String[]{"code","ref","message"});
        fields.forEach((key, selectedFields) -> {
            List<Map<String,Object>> items = listOfMaps(candidates.get(key));
            List<Map<String,Object>> examples = new ArrayList<>();
            for (Map<String,Object> item : items.subList(0, Math.min(items.size(), 12))) {
                Map<String,Object> example = new LinkedHashMap<>();
                for (String field : selectedFields) if (item.containsKey(field)) example.put(field, item.get(field));
                examples.add(example);
            }
            Map<String,Object> group = new LinkedHashMap<>(); group.put("count", items.size()); group.put("examples", examples); summary.put(key, group);
        });
        try { return json.writeValueAsString(summary); }
        catch (Exception e) { return "{}"; }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOfMaps(Object value) {
        return value instanceof List ? (List<Map<String, Object>>) value : new ArrayList<>();
    }

    private Map<String, Object> warning(String code, String ref, String message) {
        Map<String, Object> result = new LinkedHashMap<>(); result.put("code", code); result.put("ref", ref); result.put("message", message); return result;
    }

    private String clip(String value, int max) { return value == null ? "" : value.substring(0, Math.min(value.length(), max)); }

    /** 只把启用且有内容的 Wiki 资产送入生成上下文，避免一次分析把整个 Wiki 塞进模型。 */
    private List<Map<String, Object>> loadWikiDocuments(Long spaceId) {
        if (spaceId == null) return Collections.emptyList();
        List<Map<String,Object>> rows = assetMapper.selectAnalyzeDocuments(STATUS_ACTIVE, spaceId, ANALYZE_DOCUMENT_LIMIT);
        return rows.stream().map(row -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sourceId", row.get("sourceId"));
            item.put("title", clip(String.valueOf(row.get("title")), 200));
            item.put("domain", row.get("domain"));
            item.put("content", clip(String.valueOf(row.get("content")), 12000));
            return item;
        }).collect(java.util.stream.Collectors.toList());
    }

    private List<Map<String, Object>> loadBusinessRules(Long spaceId) {
        if (spaceId == null) return Collections.emptyList();
        List<Map<String,Object>> rows = assetMapper.selectAnalyzeRules(STATUS_ACTIVE, spaceId,
                BUSINESS_RULE_ASSET_TYPES, ANALYZE_DOCUMENT_LIMIT);
        return rows.stream().map(row -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sourceId", row.get("sourceId"));
            item.put("title", clip(String.valueOf(row.get("title")), 200));
            item.put("content", clip(String.valueOf(row.get("content")), 12000));
            return item;
        }).collect(java.util.stream.Collectors.toList());
    }

    private Set<Integer> parseStages(String value) {
        if (value == null || value.trim().isEmpty()) return new LinkedHashSet<>();
        Set<Integer> stages = new LinkedHashSet<>();
        for (String part : value.split(",")) {
            try {
                int stage = Integer.parseInt(part.trim());
                if (stage >= 1 && stage <= 7) stages.add(stage);
            } catch (NumberFormatException ignored) { }
        }
        return stages;
    }
}

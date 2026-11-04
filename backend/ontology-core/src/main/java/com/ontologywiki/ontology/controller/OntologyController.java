package com.ontologywiki.ontology.controller;

import java.util.*;
import java.util.Base64;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.ontologywiki.ontology.config.OntologyIntegrationProperties;
import com.ontologywiki.ontology.service.CredentialService;
import com.ontologywiki.ontology.service.DataSourceScanner;
import com.ontologywiki.ontology.service.LlmWikiClient;
import com.ontologywiki.ontology.service.OntologyGenerationEngine;
import com.ontologywiki.ontology.service.OntologyArtifactExporter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.jdbc.core.PreparedStatementCreator;
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

@RestController
@RequestMapping("/ontology")
public class OntologyController extends BaseController {
    private static final Logger log = LoggerFactory.getLogger(OntologyController.class);
    private static final java.util.regex.Pattern QUERY_PATTERN_LINE = java.util.regex.Pattern.compile("(?i)^\\s*(select|insert|update|delete)\\b");
    private final JdbcTemplate jdbc;
    private final CredentialService credentials;
    private final DataSourceScanner scanner;
    private final OntologyIntegrationProperties integration;
    private final RestTemplate rest;
    private final LlmWikiClient llmWiki;
    private final OntologyGenerationEngine generation;
    private final OntologyArtifactExporter artifactExporter;
    private final ObjectMapper json = new ObjectMapper();
    private static final Map<String, Resource> RESOURCES = new LinkedHashMap<>();
    static {
        RESOURCES.put("spaces", new Resource("wiki_space", "space_id", "space_name,description,status,create_by", "space_name"));
        RESOURCES.put("assets", new Resource("wiki_asset", "asset_id", "space_id,asset_name,asset_type,domain_name,description,content,tags,status,create_by", "space_id,asset_name,asset_type"));
        RESOURCES.put("datasources", new Resource("ontology_datasource", "datasource_id", "name,db_type,host,port,database_name,schema_name,jdbc_url,username,sample_limit,description,status,create_by", "name,db_type"));
        RESOURCES.put("analyses", new Resource("analysis_task", "task_id", "task_name,source_id,wiki_space_id,scope,status,summary,create_by", "task_name,source_id,wiki_space_id"));
        RESOURCES.put("proposals", new Resource("ontology_proposal", "proposal_id", "proposal_name,proposal_type,task_id,config_json,status,create_by", "proposal_name,proposal_type"));
        RESOURCES.put("imports", new Resource("ontology_import_record", "import_id", "proposal_id,target_system,status,detail,create_by", "target_system"));
    }
    public OntologyController(JdbcTemplate jdbc, CredentialService credentials, DataSourceScanner scanner,
            OntologyIntegrationProperties integration, RestTemplate rest, LlmWikiClient llmWiki, OntologyGenerationEngine generation,
            OntologyArtifactExporter artifactExporter) {
        this.jdbc = jdbc; this.credentials = credentials; this.scanner = scanner; this.integration = integration; this.rest = rest;
        this.llmWiki = llmWiki; this.generation = generation; this.artifactExporter = artifactExporter;
    }

    @PreAuthorize("@ss.hasPermi('ontology:dashboard:list')")
    @GetMapping("/dashboard")
    public AjaxResult dashboard() {
        Map<String, Object> counts = new LinkedHashMap<>();
        RESOURCES.forEach((key, r) -> counts.put(key, jdbc.queryForObject("select count(*) from " + r.table, Long.class)));
        return AjaxResult.success(counts);
    }

    @PreAuthorize("@ss.hasPermi('ontology:' + #p0 + ':list')")
    @GetMapping("/{resource}/list")
    public TableDataInfo list(@PathVariable("resource") String resource, @RequestParam(name="pageNum", defaultValue="1") int pageNum,
            @RequestParam(name="pageSize", defaultValue="20") int pageSize, @RequestParam(name="keyword", required=false) String keyword,
            @RequestParam(name="spaceId", required=false) Long spaceId, @RequestParam(name="taskId", required=false) Long taskId) {
        Resource r = resource(resource);
        int size = Math.max(1, Math.min(pageSize, 100));
        int page = Math.max(pageNum, 1);
        List<String> filters = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if ("assets".equals(resource)) filters.add("coalesce(tags,'')<>'SYSTEM_INDEX'");
        if ("assets".equals(resource) && spaceId != null) { filters.add("space_id=?"); args.add(spaceId); }
        if ("proposals".equals(resource) && taskId != null) { filters.add("task_id=?"); args.add(taskId); }
        if (keyword != null && !keyword.trim().isEmpty()) { filters.add("concat_ws(' ', " + r.searchColumns + ") like ?"); args.add("%" + keyword.trim().substring(0, Math.min(keyword.trim().length(), 200)) + "%"); }
        String where = filters.isEmpty() ? "" : " where " + String.join(" and ", filters);
        Object[] queryArgs = args.toArray();
        long total = jdbc.queryForObject("select count(*) from " + r.table + where, Long.class, queryArgs);
        long offset = ((long) page - 1) * size;
        List<Map<String, Object>> rows = jdbc.queryForList("select " + r.selectColumns() + " from " + r.table + where + " order by " + r.id + " desc limit ? offset ?", append(queryArgs, size, offset));
        if ("proposals".equals(resource)) addProposalTaskNames(rows);
        TableDataInfo result = new TableDataInfo(); result.setCode(HttpStatus.SUCCESS); result.setMsg("查询成功"); result.setRows(rows); result.setTotal(total); return result;
    }

    @PreAuthorize("@ss.hasPermi('ontology:assets:list')")
    @GetMapping("/assets/graph")
    public AjaxResult assetGraph(@RequestParam(name="spaceId", required=false) Long spaceId) {
        String sql = "select asset_id,space_id,asset_name,asset_type,tags,case when asset_type='WIKI_PAGE' then substring(content,1,20000) else null end content from wiki_asset where status='0' and coalesce(tags,'')<>'SYSTEM_INDEX'" + (spaceId == null ? "" : " and space_id=?") + " order by asset_name limit 2000";
        List<Map<String,Object>> rows = spaceId == null ? jdbc.queryForList(sql) : jdbc.queryForList(sql, spaceId);
        Map<Long,Map<String,Object>> byId = new LinkedHashMap<>();
        Map<String,Long> byName = new HashMap<>();
        List<Map<String,Object>> nodes = new ArrayList<>();
        for (Map<String,Object> row : rows) {
            long id = ((Number) row.get("asset_id")).longValue();
            Map<String,Object> node = new LinkedHashMap<>(); node.put("id", String.valueOf(id)); node.put("assetId", id); node.put("name", row.get("asset_name")); node.put("assetType", row.get("asset_type")); node.put("spaceId", row.get("space_id"));
            byId.put(id, row); byName.putIfAbsent(String.valueOf(row.get("asset_name")).toLowerCase(Locale.ROOT), id); nodes.add(node);
        }
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
        String memorySql = "select a.asset_id,m.source_json from wiki_asset a join ontology_analysis_memory m on a.tags=concat('ANALYSIS_MEMORY:',m.memory_id) where a.status='0' and a.asset_type='WIKI_PAGE'" + (spaceId == null ? "" : " and a.space_id=?");
        List<Map<String,Object>> memories = spaceId == null ? jdbc.queryForList(memorySql) : jdbc.queryForList(memorySql, spaceId);
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
        Resource r = resource(resource);
        List<Map<String,Object>> rows = jdbc.queryForList("select " + r.selectColumns() + " from " + r.table + " where " + r.id + "=?", id);
        if ("proposals".equals(resource)) addProposalTaskNames(rows);
        return rows.isEmpty() ? AjaxResult.error("记录不存在") : AjaxResult.success(rows.get(0));
    }

    @PreAuthorize("@ss.hasPermi('ontology:' + #p0 + ':add')")
    @Log(title = "本体Wiki资源", businessType = BusinessType.INSERT, isSaveRequestData = false, isSaveResponseData = false)
    @Transactional
    @PostMapping("/{resource}")
    public AjaxResult add(@PathVariable("resource") String resource, @RequestBody Map<String,Object> body) {
        Object rawPassword = "datasources".equals(resource) && body != null ? body.get("password") : null;
        if (rawPassword != null && (String.valueOf(rawPassword).trim().isEmpty() || String.valueOf(rawPassword).length() > 2048)) return AjaxResult.error("数据库密码不能为空或过长");
        if ("proposals".equals(resource) && body != null && body.containsKey("status")) return AjaxResult.error("草案状态由审核操作维护");
        String datasourcePassword = rawPassword == null ? null : String.valueOf(rawPassword);
        Resource r = resource(resource); Map<String,Object> data = safeData(r, body);
        if ("datasources".equals(resource) && !isPostgres(data.get("db_type"))) data.put("schema_name", "");
        if (!validJdbcUrl(resource, data)) return AjaxResult.error("JDBC URL 无效、与数据库类型不匹配或包含账号密码；请检查地址并将密码单独填写");
        if (data.isEmpty()) return AjaxResult.error("请填写有效内容");
        if (!hasRequired(r, data)) return AjaxResult.error("缺少必填字段");
        String analysisScopeError = validateIndustrySpace(resource, data, null);
        if (analysisScopeError != null) return AjaxResult.error(analysisScopeError);
        data.put("create_by", SecurityUtils.getUsername());
        data.put("update_by", SecurityUtils.getUsername());
        data.put("create_time", new Date()); data.put("update_time", new Date());
        String columns = String.join(",", data.keySet());
        String marks = String.join(",", Collections.nCopies(data.size(), "?"));
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update((PreparedStatementCreator) connection -> {
            java.sql.PreparedStatement statement = connection.prepareStatement("insert into " + r.table + " (" + columns + ") values (" + marks + ")", new String[] {r.id});
            int index = 1; for (Object value : data.values()) statement.setObject(index++, value); return statement;
        }, keyHolder);
        if (datasourcePassword != null && keyHolder.getKey() != null) jdbc.update("update ontology_datasource set password_enc=?, update_by=?, update_time=now() where datasource_id=?", credentials.encrypt(datasourcePassword), SecurityUtils.getUsername(), keyHolder.getKey().longValue());
        Map<String,Object> result = new LinkedHashMap<>(); result.put("id", keyHolder.getKey() == null ? null : keyHolder.getKey().longValue()); result.put("message", "创建成功");
        return AjaxResult.success(result);
    }

    @PreAuthorize("@ss.hasPermi('ontology:' + #p0 + ':edit')")
    @Log(title = "本体Wiki资源", businessType = BusinessType.UPDATE, isSaveRequestData = false, isSaveResponseData = false)
    @Transactional
    @PutMapping("/{resource}/{id}")
    public AjaxResult edit(@PathVariable("resource") String resource, @PathVariable("id") long id, @RequestBody Map<String,Object> body) {
        Object rawPassword = "datasources".equals(resource) && body != null ? body.get("password") : null;
        if (rawPassword != null && !String.valueOf(rawPassword).trim().isEmpty() && String.valueOf(rawPassword).length() > 2048) return AjaxResult.error("数据库密码过长");
        if ("proposals".equals(resource) && body != null && body.containsKey("status")) return AjaxResult.error("草案状态由审核操作维护");
        String datasourcePassword = rawPassword == null || String.valueOf(rawPassword).trim().isEmpty() ? null : String.valueOf(rawPassword);
        Resource r = resource(resource); Map<String,Object> data = safeData(r, body);
        if ("datasources".equals(resource) && !isPostgres(data.get("db_type"))) data.put("schema_name", "");
        if (!validJdbcUrl(resource, data)) return AjaxResult.error("JDBC URL 无效、与数据库类型不匹配或包含账号密码；请检查地址并将密码单独填写");
        if (data.isEmpty()) return AjaxResult.error("请填写有效内容");
        if (!hasRequired(r, data)) return AjaxResult.error("缺少必填字段");
        String analysisScopeError = validateIndustrySpace(resource, data, id);
        if (analysisScopeError != null) return AjaxResult.error(analysisScopeError);
        boolean resetProposalReview = false;
        if ("proposals".equals(resource) && (data.containsKey("config_json") || data.containsKey("proposal_type"))) {
            List<Map<String,Object>> existing = jdbc.queryForList("select proposal_type,config_json from ontology_proposal where proposal_id=?", id);
            if (!existing.isEmpty()) {
                Map<String,Object> current = existing.get(0);
                resetProposalReview = (data.containsKey("proposal_type") && !Objects.equals(data.get("proposal_type"), current.get("proposal_type")))
                        || (data.containsKey("config_json") && !Objects.equals(data.get("config_json"), current.get("config_json")));
            }
        }
        data.put("update_by", SecurityUtils.getUsername()); data.put("update_time", new Date());
        String set = String.join(",", data.keySet().stream().map(k -> k + "=?").toArray(String[]::new));
        int updated = jdbc.update("update " + r.table + " set " + set + " where " + r.id + "=?", append(data.values().toArray(), id));
        if (updated > 0 && resetProposalReview) {
            jdbc.update("update ontology_proposal set status='DRAFT',review_by=null,review_time=null,review_comment=null where proposal_id=?", id);
        }
        if (updated > 0 && datasourcePassword != null) jdbc.update("update ontology_datasource set password_enc=?, update_by=?, update_time=now() where datasource_id=?", credentials.encrypt(datasourcePassword), SecurityUtils.getUsername(), id);
        return toAjax(updated);
    }

    @PreAuthorize("@ss.hasPermi('ontology:proposals:review')")
    @PostMapping("/proposals/{id}/review")
    public AjaxResult reviewProposal(@PathVariable("id") long id, @RequestBody(required=false) Map<String,String> body) {
        String decision = body == null || body.get("decision") == null ? "" : body.get("decision").trim().toUpperCase(Locale.ROOT);
        String comment = body == null || body.get("comment") == null ? "" : body.get("comment").trim();
        if (!"APPROVED".equals(decision) && !"REJECTED".equals(decision)) return AjaxResult.error("请选择审核通过或驳回");
        if ("REJECTED".equals(decision) && comment.isEmpty()) return AjaxResult.error("驳回时请填写审核意见");
        int updated = jdbc.update("update ontology_proposal set status=?,review_by=?,review_time=now(),review_comment=?,update_by=?,update_time=now() where proposal_id=? and status='DRAFT'",
                decision, SecurityUtils.getUsername(), clip(comment, 1000), SecurityUtils.getUsername(), id);
        return updated == 0 ? AjaxResult.error("草案不存在或已处理，请刷新列表") : AjaxResult.success("审核记录已保存");
    }

    @PreAuthorize("@ss.hasPermi('ontology:' + #p0 + ':remove')")
    @Log(title = "本体Wiki资源", businessType = BusinessType.DELETE, isSaveRequestData = false, isSaveResponseData = false)
    @DeleteMapping("/{resource}/{ids}")
    public AjaxResult remove(@PathVariable("resource") String resource, @PathVariable("ids") String ids) {
        Resource r = resource(resource); String[] parts = ids.split(",");
        if (parts.length > 100) return AjaxResult.error("单次最多删除100条");
        long[] keys = Arrays.stream(parts).mapToLong(Long::parseLong).toArray();
        return toAjax(jdbc.update("delete from " + r.table + " where " + r.id + " in (" + String.join(",", Collections.nCopies(keys.length, "?")) + ")", Arrays.stream(keys).boxed().toArray()));
    }

    @PreAuthorize("@ss.hasPermi('ontology:datasources:edit')")
    @PostMapping("/datasources/{id}/credential")
    public AjaxResult setCredential(@PathVariable("id") long id, @RequestBody Map<String,String> body) {
        String password = body == null ? null : body.get("password");
        if (password == null || password.trim().isEmpty() || password.length() > 2048) return AjaxResult.error("数据库密码不能为空或过长");
        return toAjax(jdbc.update("update ontology_datasource set password_enc=?, update_by=?, update_time=now() where datasource_id=?",
                credentials.encrypt(password), SecurityUtils.getUsername(), id));
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
            Map<String,Object> saved = jdbc.queryForMap("select datasource_id,name,db_type,host,port,database_name,schema_name,jdbc_url,username,password_enc,sample_limit from ontology_datasource where datasource_id=?", Long.parseLong(String.valueOf(id)));
            saved.forEach((key, value) -> { if (!source.containsKey(key) || source.get(key) == null || String.valueOf(source.get(key)).trim().isEmpty()) source.put(key, value); });
            if (password.isEmpty()) {
                Object encrypted = saved.get("password_enc");
                if (encrypted == null || String.valueOf(encrypted).trim().isEmpty()) return AjaxResult.error("该数据源尚未设置数据库密码");
                password = credentials.decrypt(String.valueOf(encrypted));
            }
        }
        if (!validJdbcUrl("datasources", source)) return AjaxResult.error("JDBC URL 无效、与数据库类型不匹配或包含账号密码；请检查地址并将密码单独填写");
        return AjaxResult.success(scanner.testConnection(source, password));
    }

    @PreAuthorize("@ss.hasPermi('ontology:datasources:query')")
    @PostMapping("/datasources/{id}/scan")
    public AjaxResult scanDatasource(@PathVariable("id") long id, @RequestBody(required=false) Map<String,Object> options) {
        Map<String,Object> source = jdbc.queryForMap("select datasource_id,name,db_type,host,port,database_name,schema_name,jdbc_url,username,password_enc,sample_limit from ontology_datasource where datasource_id=?", id);
        return AjaxResult.success(scanner.scan(source, options == null ? Collections.<String,Object>emptyMap() : options));
    }

    @PreAuthorize("@ss.hasPermi('ontology:proposals:edit')")
    @Log(title = "本体配置草案导入", businessType = BusinessType.IMPORT, isSaveRequestData = false, isSaveResponseData = false)
    @PostMapping("/proposals/{id}/import")
    public AjaxResult importProposal(@PathVariable("id") long id) {
        OntologyIntegrationProperties.Platform platform = integration.getPlatform();
        if (!platform.isEnabled() || platform.getBaseUrl() == null || platform.getBaseUrl().trim().isEmpty() || platform.getImportPath() == null || platform.getImportPath().trim().isEmpty()) return AjaxResult.error("本体平台导入接口未配置");
        Map<String,Object> proposal = jdbc.queryForMap("select * from ontology_proposal where proposal_id=?", id);
        if (!"APPROVED".equalsIgnoreCase(String.valueOf(proposal.get("status")))) return AjaxResult.error("只有审核通过的草案才能导入");
        try {
            com.fasterxml.jackson.databind.JsonNode fullConfig = json.readTree(String.valueOf(proposal.get("config_json")));
            Object payload = fullConfig.path("targetConfig").isObject() ? fullConfig.path("targetConfig") : fullConfig;
            HttpHeaders headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
            if (platform.getToken() != null && !platform.getToken().trim().isEmpty()) headers.setBearerAuth(platform.getToken());
            ResponseEntity<String> response = rest.exchange(platform.getBaseUrl() + platform.getImportPath(), HttpMethod.POST, new HttpEntity<>(payload, headers), String.class);
            String status = response.getStatusCode().is2xxSuccessful() ? "SUCCESS" : "FAILED";
            jdbc.update("insert into ontology_import_record(proposal_id,target_system,status,detail,create_by,create_time) values(?,?,?,?,?,now())", id, "本体平台", status, clip(response.getBody(), 2000), SecurityUtils.getUsername());
            if ("SUCCESS".equals(status)) jdbc.update("update ontology_proposal set status='IMPORTED',update_by=?,update_time=now() where proposal_id=?", SecurityUtils.getUsername(), id);
            return response.getStatusCode().is2xxSuccessful() ? AjaxResult.success(response.getBody()) : AjaxResult.error("本体平台返回 " + response.getStatusCodeValue());
        } catch (Exception e) {
            jdbc.update("insert into ontology_import_record(proposal_id,target_system,status,detail,create_by,create_time) values(?,?,?,?,?,now())", id, "本体平台", "FAILED", "请求失败，请检查导入接口配置和服务状态", SecurityUtils.getUsername());
            return AjaxResult.error("本体平台导入失败，请检查接口配置和服务状态");
        }
    }

    @PreAuthorize("@ss.hasPermi('ontology:proposals:query')")
    @Log(title = "本体配置草案导出", businessType = BusinessType.EXPORT, isSaveRequestData = false, isSaveResponseData = false)
    @GetMapping("/proposals/{id}/export")
    public ResponseEntity<?> exportProposal(@PathVariable("id") long id, @RequestParam("format") String format) {
        Map<String, Object> proposal = jdbc.queryForMap(
                "select proposal_name,config_json,task_id from ontology_proposal where proposal_id=?", id);
        try {
            Map<String, Object> config = json.readValue(String.valueOf(proposal.get("config_json")),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
            String label = String.valueOf(proposal.get("proposal_name"));
            Map<String, Object> datasource = Collections.emptyMap();
            if (proposal.get("task_id") != null) {
                List<Map<String, Object>> rows = jdbc.queryForList(
                        "select d.name,d.schema_name,d.db_type from analysis_task t left join ontology_datasource d on d.datasource_id=t.source_id where t.task_id=?",
                        ((Number) proposal.get("task_id")).longValue());
                if (!rows.isEmpty()) datasource = rows.get(0);
            }
            OntologyArtifactExporter.Artifact artifact;
            if ("rdf".equalsIgnoreCase(format) || "owl".equalsIgnoreCase(format)) {
                artifact = artifactExporter.rdf(config, String.valueOf(id), label, value(datasource.get("name")));
            } else if ("project".equalsIgnoreCase(format) || "tar".equalsIgnoreCase(format)) {
                artifact = artifactExporter.engineeringProject(config, String.valueOf(id), label, SecurityUtils.getUsername(),
                        value(datasource.get("name")), value(datasource.get("schema_name")), value(datasource.get("db_type")));
            } else {
                return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
                        .body(AjaxResult.error("导出格式仅支持 RDF/OWL 或工程 TAR"));
            }
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(artifact.getContentType()));
            headers.setContentLength(artifact.getBytes().length);
            headers.setContentDisposition(ContentDisposition.attachment().filename(artifact.getFilename(), java.nio.charset.StandardCharsets.UTF_8).build());
            return new ResponseEntity<>(artifact.getBytes(), headers, org.springframework.http.HttpStatus.OK);
        } catch (Exception e) {
            log.warn("本体草案导出失败，proposalId={}", id, e);
            return ResponseEntity.unprocessableEntity().contentType(MediaType.APPLICATION_JSON)
                    .body(AjaxResult.error("草案配置不完整或导出失败，请检查对象、属性和关系映射"));
        }
    }

    @PreAuthorize("@ss.hasPermi('ontology:analyses:query')")
    @GetMapping("/analyses/{id}/export")
    public ResponseEntity<?> exportAnalysis(@PathVariable("id") long id, @RequestParam("format") String format) {
        List<Map<String,Object>> rows = jdbc.queryForList("select proposal_id from ontology_proposal where task_id=? order by proposal_id desc limit 1", id);
        if (rows.isEmpty()) return ResponseEntity.status(org.springframework.http.HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON).body(AjaxResult.error("该分析任务还没有可导出的配置草案"));
        return exportProposal(((Number) rows.get(0).get("proposal_id")).longValue(), format);
    }

    @PreAuthorize("@ss.hasPermi('ontology:proposals:query')")
    @GetMapping("/proposals/{id}/diff")
    public AjaxResult proposalDiff(@PathVariable("id") long id) {
        List<Map<String,Object>> currentRows = jdbc.queryForList("select proposal_id,proposal_name,task_id,config_json from ontology_proposal where proposal_id=?", id);
        if (currentRows.isEmpty()) return AjaxResult.error("配置草案不存在");
        Map<String,Object> current = currentRows.get(0);
        if (current.get("task_id") == null) return AjaxResult.success(Collections.singletonMap("message", "手工草案没有可比较的历史版本"));
        List<Map<String,Object>> previousRows = jdbc.queryForList("select proposal_id,proposal_name,config_json from ontology_proposal where task_id=? and proposal_id<? order by proposal_id desc limit 1", current.get("task_id"), id);
        if (previousRows.isEmpty()) return AjaxResult.success(Collections.singletonMap("message", "这是该分析任务的第一个版本"));
        try {
            Map<String,Object> before = json.readValue(String.valueOf(previousRows.get(0).get("config_json")), new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() { });
            Map<String,Object> after = json.readValue(String.valueOf(current.get("config_json")), new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() { });
            List<Map<String,Object>> changes = new ArrayList<>();
            for (String key : Arrays.asList("objects","properties","identitySpecs","dataProfiles","relations","mappings","derivedProperties","validationRules","axioms","logics","actions","serviceFlows","permissionCandidates","queryPatterns","terms","semanticMappings","governanceRules")) diffRows(key, before.get(key), after.get(key), changes);
            Map<?,?> beforeTaxonomy = before.get("taxonomy") instanceof Map ? (Map<?,?>) before.get("taxonomy") : Collections.emptyMap();
            Map<?,?> afterTaxonomy = after.get("taxonomy") instanceof Map ? (Map<?,?>) after.get("taxonomy") : Collections.emptyMap();
            diffRows("taxonomy.nodes", beforeTaxonomy.get("nodes"), afterTaxonomy.get("nodes"), changes);
            diffRows("taxonomy.edges", beforeTaxonomy.get("edges"), afterTaxonomy.get("edges"), changes);
            Map<String,Object> result = new LinkedHashMap<>(); result.put("previousProposalId", previousRows.get(0).get("proposal_id")); result.put("previousProposalName", previousRows.get(0).get("proposal_name"));
            result.put("currentProposalId", id); result.put("currentProposalName", current.get("proposal_name")); result.put("changes", changes);
            result.put("added", changes.stream().filter(row -> "新增".equals(row.get("change"))).count());
            result.put("removed", changes.stream().filter(row -> "移除".equals(row.get("change"))).count());
            result.put("modified", changes.stream().filter(row -> "修改".equals(row.get("change"))).count());
            return AjaxResult.success(result);
        } catch (Exception e) { log.warn("草案版本对比失败 proposalId={}", id, e); return AjaxResult.error("配置草案 JSON 无法解析，不能生成版本差异"); }
    }

    private void diffRows(String category, Object beforeValue, Object afterValue, List<Map<String,Object>> changes) throws Exception {
        Map<String,Map<String,Object>> before = indexRows(beforeValue); Map<String,Map<String,Object>> after = indexRows(afterValue);
        Set<String> ids = new LinkedHashSet<>(); ids.addAll(before.keySet()); ids.addAll(after.keySet());
        for (String key : ids) {
            if (changes.size() >= 500) return;
            Map<String,Object> oldRow = before.get(key), newRow = after.get(key);
            String change = oldRow == null ? "新增" : newRow == null ? "移除" : json.valueToTree(oldRow).equals(json.valueToTree(newRow)) ? null : "修改";
            if (change == null) continue;
            Map<String,Object> item = new LinkedHashMap<>(); item.put("category", category); item.put("change", change); item.put("key", key);
            if (oldRow != null) item.put("before", oldRow); if (newRow != null) item.put("after", newRow); changes.add(item);
            if (changes.size() >= 500) return;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String,Map<String,Object>> indexRows(Object value) {
        Map<String,Map<String,Object>> result = new LinkedHashMap<>();
        if (!(value instanceof Collection)) return result;
        for (Object item : (Collection<?>) value) if (item instanceof Map) {
            Map<String,Object> row = (Map<String,Object>) item;
            Object identity = row.get("id"); if (identity == null) identity = row.get("name");
            if (identity == null && row.get("table") != null) identity = row.get("table");
            if (identity == null && row.get("objectId") != null && row.get("column") != null) identity = row.get("objectId") + "." + row.get("column");
            if (identity == null && row.get("sourceId") != null && row.get("targetId") != null) identity = row.get("sourceId") + "." + row.get("targetId") + "." + value(row.get("type"));
            if (identity == null && row.get("alias") != null && row.get("targetObjectId") != null) identity = row.get("alias") + "." + row.get("targetObjectId");
            if (identity == null && row.get("type") != null && row.get("tables") != null) identity = row.get("type") + "." + String.valueOf(row.get("tables"));
            String key = identity == null ? String.valueOf(row.hashCode()) : String.valueOf(identity);
            result.put(key, row);
        }
        return result;
    }

    @PreAuthorize("@ss.hasPermi('ontology:proposals:edit')")
    @Log(title = "本体配置草案沙箱测试", businessType = BusinessType.OTHER, isSaveRequestData = false, isSaveResponseData = false)
    @PostMapping("/proposals/{id}/sandbox-test")
    public AjaxResult sandboxTestProposal(@PathVariable("id") long id) {
        OntologyIntegrationProperties.Platform platform = integration.getPlatform();
        if (platform.getBaseUrl() == null || platform.getBaseUrl().trim().isEmpty() || platform.getTestPath() == null || platform.getTestPath().trim().isEmpty()) return AjaxResult.error("本体平台沙箱测试地址未配置");
        List<Map<String,Object>> rows = jdbc.queryForList("select proposal_name,config_json from ontology_proposal where proposal_id=?", id);
        if (rows.isEmpty()) return AjaxResult.error("配置草案不存在");
        String status = "FAILED", detail;
        try {
            Map<String,Object> config = json.readValue(String.valueOf(rows.get(0).get("config_json")), new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() { });
            Object targetConfig = config.containsKey("targetConfig") ? config.get("targetConfig") : config;
            Map<String,Object> payload = new LinkedHashMap<>(); payload.put("mode", "SANDBOX_TEST"); payload.put("proposalId", id); payload.put("proposalName", rows.get(0).get("proposal_name")); payload.put("targetConfig", targetConfig);
            HttpHeaders headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON); if (platform.getToken() != null && !platform.getToken().trim().isEmpty()) headers.setBearerAuth(platform.getToken());
            ResponseEntity<String> response = rest.exchange(platform.getBaseUrl() + platform.getTestPath(), HttpMethod.POST, new HttpEntity<>(payload, headers), String.class);
            status = sandboxReceiptStatus(response.getBody()); detail = "HTTP " + response.getStatusCodeValue() + "\n" + value(response.getBody());
        } catch (Exception e) { detail = "沙箱请求失败，请检查测试接口契约和服务状态"; }
        KeyHolder holder = new GeneratedKeyHolder(); final String receiptStatus = status; final String receiptDetail = clip(detail, 2000);
        jdbc.update(connection -> { java.sql.PreparedStatement ps = connection.prepareStatement("insert into ontology_import_record(proposal_id,target_system,status,detail,create_by,create_time) values(?,?,?,?,?,now())", new String[]{"import_id"}); ps.setLong(1, id); ps.setString(2, "本体平台沙箱"); ps.setString(3, receiptStatus); ps.setString(4, receiptDetail); ps.setString(5, SecurityUtils.getUsername()); return ps; }, holder);
        Map<String,Object> result = new LinkedHashMap<>(); result.put("receiptId", holder.getKey() == null ? null : holder.getKey().longValue()); result.put("status", status); result.put("detail", receiptDetail); return AjaxResult.success(result);
    }

    private String sandboxReceiptStatus(String body) {
        try {
            com.fasterxml.jackson.databind.JsonNode root = json.readTree(body == null ? "{}" : body);
            if (root.path("passed").asBoolean(false) || root.path("success").asBoolean(false)) return "SUCCESS";
            String state = root.path("status").asText(root.path("result").asText("")).trim().toUpperCase(Locale.ROOT);
            if (Arrays.asList("PASS","PASSED","SUCCESS","OK").contains(state)) return "SUCCESS";
            if (Arrays.asList("FAIL","FAILED","ERROR","REJECTED").contains(state)) return "FAILED";
            return "PENDING";
        } catch (Exception e) { return "PENDING"; }
    }

    private String value(Object value) { return value == null ? "" : String.valueOf(value); }

    @PreAuthorize("@ss.hasPermi('ontology:analyses:edit')")
    @PostMapping("/analyses/{id}/analyze")
    public AjaxResult analyze(@PathVariable("id") long id, @RequestBody(required=false) Map<String,String> request) {
        Map<String,Object> task = jdbc.queryForMap("select * from analysis_task where task_id=?", id);
        Object sourceValue = task.get("source_id");
        if (sourceValue == null) return AjaxResult.error("分析任务未配置数据源");
        Long wikiSpaceId = task.get("wiki_space_id") == null ? null : ((Number) task.get("wiki_space_id")).longValue();
        if (wikiSpaceId == null) return AjaxResult.error("该任务未绑定行业知识空间，请先编辑任务并选择行业；分析不会读取其他行业的 Wiki 资料");
        if (jdbc.queryForObject("select count(*) from wiki_space where space_id=? and status='0'", Integer.class, wikiSpaceId) == 0) return AjaxResult.error("分析任务关联的行业知识空间不存在或已停用");
        Map<String,Object> source = jdbc.queryForMap("select datasource_id,name,db_type,host,port,database_name,schema_name,jdbc_url,username,password_enc,sample_limit from ontology_datasource where datasource_id=?", ((Number) sourceValue).longValue());
        String requestedMode = request == null ? "" : value(request.get("mode")).trim().toUpperCase(Locale.ROOT);
        if (!requestedMode.isEmpty() && !Arrays.asList("FULL", "INCREMENTAL").contains(requestedMode)) return AjaxResult.error("分析模式无效");
        boolean incremental = "INCREMENTAL".equals(requestedMode);
        Set<String> changedTables = incremental ? requestedTableNames(request.get("tables")) : Collections.<String>emptySet();
        Map<String,Object> previousSnapshot = null;
        if (incremental) {
            if (changedTables.isEmpty()) return AjaxResult.error("增量更新请填写至少一个发生变化的表名");
            if (changedTables.size() > 20) return AjaxResult.error("单次增量更新最多选择20张表");
            List<Map<String,Object>> previous = jdbc.queryForList("select config_json from ontology_proposal where task_id=? order by proposal_id desc limit 1", id);
            if (previous.isEmpty()) return AjaxResult.error("该任务还没有历史草案，请先执行一次全量分析");
            try {
                Map<String,Object> config = json.readValue(String.valueOf(previous.get(0).get("config_json")), new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() { });
                Object snapshot = config.get("schemaSnapshot");
                if (!(snapshot instanceof Map)) return AjaxResult.error("历史草案没有可用于增量更新的结构快照，请先执行一次全量分析");
                previousSnapshot = (Map<String,Object>) snapshot;
            } catch (Exception e) { return AjaxResult.error("历史草案结构快照无法解析，请先执行一次全量分析"); }
        }
        if (jdbc.update("update analysis_task set status='RUNNING',summary=?,update_by=?,update_time=now() where task_id=? and status<>'RUNNING'", "1/4 正在连接数据源，读取表结构和字段…", SecurityUtils.getUsername(), id) == 0) return AjaxResult.error("该分析任务正在执行");
        String ask = request == null ? null : request.get("question");
        String prompt;
        Map<String,Object> schema;
        try {
            int sampleLimit = request == null || request.get("sampleLimit") == null ? 20 : Math.max(0, Math.min(Integer.parseInt(request.get("sampleLimit")), 20));
            Map<String,Object> scanOptions = new HashMap<>();
            if (incremental) {
                scanOptions.put("tables", changedTables);
                schema = generation.mergeSchemaSnapshot(previousSnapshot, scanner.scan(source, scanOptions), changedTables);
            } else if (sampleLimit > 0 && request != null && request.get("tables") != null) {
                scanOptions.put("sampleLimit", sampleLimit);
                scanOptions.put("tables", Arrays.asList(request.get("tables").split(",")));
                schema = scanner.scan(source, scanOptions);
            } else {
                schema = scanner.scan(source, scanOptions);
            }
            updateAnalysisProgress(id, "2/4 已读取 " + schema.get("tableCount") + " 张表，正在生成对象、属性和关系候选…");
            prompt = "请基于数据库元数据和确定性候选结果补充本体业务语义。缺乏证据的内容标记为待确认，不要编造。请用中文回答；如有候选，请在回答末尾输出JSON对象，候选使用 objects、properties、relations、logics、actions、terms 数组。所有写动作必须标记高风险并说明依据。\n任务范围：" + scopeForPrompt(task.get("scope")) + "\n工程师问题：" + (ask == null ? "分析该数据库" : ask) + "\n数据库元数据与样例统计：" + json.writeValueAsString(schemaForPrompt(schema));
        } catch (Exception e) { String message = e instanceof IllegalArgumentException && e.getMessage() != null ? e.getMessage() : "读取数据源或构建分析请求失败，请检查连接和分析参数"; jdbc.update("update analysis_task set status='FAILED',summary=?,update_by=?,update_time=now() where task_id=?", clip(message, 1000), SecurityUtils.getUsername(), id); return AjaxResult.error(message); }
        try {
            List<Map<String,Object>> documents = loadWikiDocuments(wikiSpaceId);
            List<Map<String,Object>> businessRules = loadBusinessRules(wikiSpaceId);
            String confirmedKnowledge = llmWiki.confirmedKnowledgeContext(id, wikiSpaceId);
            List<String> queryPatterns = request != null && request.get("queryPatterns") != null
                    ? Arrays.asList(request.get("queryPatterns").split("\\r?\\n")) : queryPatternsFromScope(task.get("scope"));
            Set<Integer> stages = parseStages(request == null ? null : request.get("stages"));
            Map<String,Object> deterministic = generation.generate(schema, documents, businessRules, queryPatterns, stages);
            deterministic.put("schemaSnapshot", generation.schemaMetadataSnapshot(schema));
            updateAnalysisProgress(id, "3/4 候选结构已生成，正在结合 Wiki 资料调用模型补充业务语义…");
            // Keep the full deterministic bundle in the proposal, but send only valid compact examples to the LLM.
            prompt = clip(prompt, 60000) + "\n确定性生成引擎候选摘要：" + candidateSummary(deterministic) + "\n请只补充有证据的业务语义，返回分析说明和可选JSON。";
            if (!confirmedKnowledge.isEmpty()) prompt += "\n已确认分析知识（优先参考，若与当前结构冲突请明确提示）：\n" + clip(confirmedKnowledge, 24000);
            Map<String,Object> analysisResponse = llmWiki.enabled()
                    ? llmWiki.queryResult(prompt, false, wikiSpaceId, Collections.<Map<String,Object>>emptyList())
                    : Collections.singletonMap("answer", "LLM Wiki 未启用，已生成数据库结构候选，请工程师审核。");
            String answer = String.valueOf(analysisResponse.getOrDefault("answer", ""));
            updateAnalysisProgress(id, "4/4 模型分析完成，正在校验并保存待审核配置草案…");
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
            Map<String,Object> result = new LinkedHashMap<>(); result.put("answer", answer);
            result.put("proposalConfig", deterministic);
            if (analysisResponse.containsKey("citedPages")) result.put("citedPages", analysisResponse.get("citedPages"));
            if (config != null) {
                KeyHolder holder = new GeneratedKeyHolder();
                jdbc.update(connection -> { java.sql.PreparedStatement ps = connection.prepareStatement("insert into ontology_proposal(proposal_name,proposal_type,task_id,config_json,status,create_by,create_time) values(?, 'ONTOLOGY_BUNDLE', ?, ?, 'DRAFT', ?, now())", new String[]{"proposal_id"}); ps.setString(1, String.valueOf(task.get("task_name")) + " 配置草案"); ps.setLong(2, id); ps.setString(3, config); ps.setString(4, SecurityUtils.getUsername()); return ps; }, holder);
                result.put("proposalId", holder.getKey() == null ? null : holder.getKey().longValue());
                Map<String,Object> memory = llmWiki.saveAnalysisAnswer(id, answer, false, SecurityUtils.getUsername());
                result.put("analysisMemory", memory);
            }
            jdbc.update("update analysis_task set summary=?,status='DONE',update_by=?,update_time=now() where task_id=?", clip(answer, 60000), SecurityUtils.getUsername(), id);
            return AjaxResult.success(result);
        } catch (Exception e) { log.error("本体分析任务执行失败 taskId={}", id, e); jdbc.update("update analysis_task set status='FAILED',summary=?,update_by=?,update_time=now() where task_id=?", "LLM Wiki 分析失败，请检查外部服务配置和数据源状态", SecurityUtils.getUsername(), id); return AjaxResult.error("LLM Wiki 分析失败，请检查外部服务配置和数据源状态"); }
    }

    private void updateAnalysisProgress(long taskId, String message) {
        jdbc.update("update analysis_task set summary=?,update_by=?,update_time=now() where task_id=? and status='RUNNING'", message, SecurityUtils.getUsername(), taskId);
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
                ? jdbc.queryForList("select t.task_id,t.summary,p.proposal_id,p.config_json from analysis_task t left join ontology_proposal p on p.proposal_id=(select p2.proposal_id from ontology_proposal p2 where p2.task_id=t.task_id order by p2.proposal_id desc limit 1) where t.task_id=?", id)
                : jdbc.queryForList("select t.task_id,t.summary,p.proposal_id,p.config_json from analysis_task t join ontology_proposal p on p.task_id=t.task_id and p.proposal_id=? where t.task_id=?", proposalId, id);
        return rows.isEmpty() ? AjaxResult.error(proposalId == null ? "分析任务不存在" : "配置草案不属于当前分析任务") : AjaxResult.success(rows.get(0));
    }

    @PreAuthorize("@ss.hasPermi('ontology:analyses:edit')")
    @PostMapping("/analyses/{id}/answer/refine")
    public AjaxResult refineAnalysisAnswer(@PathVariable("id") long id, @RequestBody Map<String,Object> request) {
        String answer = request == null || request.get("answer") == null ? "" : String.valueOf(request.get("answer"));
        String question = request == null || request.get("question") == null ? "" : String.valueOf(request.get("question"));
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
            List<Map<String,Object>> assetRows = jdbc.queryForList("select space_id from wiki_asset where asset_id=? and status='0'", scopeId);
            if (assetRows.isEmpty()) return AjaxResult.error("所选 Wiki 资产不存在或已停用");
            spaceId = assetRows.get(0).get("space_id") == null ? null : ((Number) assetRows.get(0).get("space_id")).longValue();
        }
        if ("ANALYSIS".equals(scopeType)) {
            List<Map<String,Object>> taskRows = jdbc.queryForList("select wiki_space_id from analysis_task where task_id=?", scopeId);
            if (taskRows.isEmpty()) return AjaxResult.error("所选分析任务不存在");
            spaceId = taskRows.get(0).get("wiki_space_id") == null ? null : ((Number) taskRows.get(0).get("wiki_space_id")).longValue();
        }
        Long proposalId = null;
        if ("ANALYSIS".equals(scopeType) && request.get("proposalId") != null && !String.valueOf(request.get("proposalId")).trim().isEmpty()) {
            try { proposalId = Long.valueOf(String.valueOf(request.get("proposalId"))); }
            catch (NumberFormatException e) { return AjaxResult.error("配置草案编号无效，请重新打开草案问答"); }
            if (jdbc.queryForObject("select count(*) from ontology_proposal where proposal_id=? and task_id=?", Integer.class, proposalId, scopeId) == 0) return AjaxResult.error("所选配置草案不属于当前分析任务");
        }
        if (spaceId != null && jdbc.queryForObject("select count(*) from wiki_space where space_id=? and status='0'", Integer.class, spaceId) == 0) return AjaxResult.error("关联 Wiki 空间不存在或已停用");
        if (!llmWiki.enabled() && !"ALL".equals(scopeType)) return AjaxResult.error("当前 Wiki 问答服务未启用，无法按所选范围问答");
        String username = SecurityUtils.getUsername();
        List<Map<String,Object>> sessionRows = jdbc.queryForList("select space_id,scope_type,scope_id,context_state_json from wiki_chat_session where session_id=? and created_by=?", sessionId, username);
        Map<String,Object> contextState = Collections.emptyMap();
        if (sessionRows.isEmpty()) {
            List<Object> sessionArgs = new ArrayList<>();
            sessionArgs.add(sessionId);
            String spaceValue = "null";
            if (spaceId != null) { spaceValue = "?"; sessionArgs.add(spaceId); }
            sessionArgs.add(scopeType);
            String scopeValue = "null";
            if (scopeId != null) { scopeValue = "?"; sessionArgs.add(scopeId); }
            sessionArgs.add(clip(question,80)); sessionArgs.add(username);
            jdbc.update("insert into wiki_chat_session(session_id,space_id,scope_type,scope_id,title,created_by,create_time,update_time) values(?," + spaceValue + ",?," + scopeValue + ",?,?,now(),now())", sessionArgs.toArray());
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
            jdbc.update("update wiki_chat_session set update_time=now() where session_id=? and created_by=?", sessionId, username);
        }
        List<Map<String,Object>> history = jdbc.queryForList("select role,content from wiki_chat_message where session_id=? and created_by=? order by message_id desc limit 12", sessionId, username);
        Collections.reverse(history);
        jdbc.update("insert into wiki_chat_message(session_id,role,content,created_by,create_time) values(?,?,?,?,now())", sessionId, "user", question, username);
        try {
            Map<String,Object> wikiResponse = llmWiki.enabled()
                    ? llmWiki.queryResult(question, Boolean.parseBoolean(String.valueOf(request.get("saveAsPage"))), spaceId, history, scopeType, scopeId, proposalId,
                            request.get("analysisChanges") instanceof List ? (List<?>) request.get("analysisChanges") : Collections.emptyList(), contextState, "ANALYSIS".equals(scopeType))
                    : Collections.singletonMap("answer", askZhiwen(question, sessionId));
            String answer = String.valueOf(wikiResponse.getOrDefault("answer", ""));
            final String chatSessionId = sessionId;
            KeyHolder messageKey = new GeneratedKeyHolder();
            jdbc.update((PreparedStatementCreator) connection -> {
                java.sql.PreparedStatement statement = connection.prepareStatement("insert into wiki_chat_message(session_id,role,content,created_by,create_time) values(?,?,?,?,now())", new String[] {"message_id"});
                statement.setString(1, chatSessionId); statement.setString(2, "assistant"); statement.setString(3, answer); statement.setString(4, username); return statement;
            }, messageKey);
            if (messageKey.getKey() != null && wikiResponse.get("citationIds") instanceof List) {
                for (Object citedId : (List<?>) wikiResponse.get("citationIds")) if (citedId instanceof Number) jdbc.update("insert ignore into wiki_chat_citation(message_id,asset_id) values(?,?)", messageKey.getKey().longValue(), ((Number) citedId).longValue());
            }
            if (wikiResponse.get("contextState") instanceof Map) try {
                jdbc.update("update wiki_chat_session set context_state_json=?,update_time=now() where session_id=? and created_by=?",
                        json.writeValueAsString(wikiResponse.get("contextState")), sessionId, username);
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
        String username = SecurityUtils.getUsername();
        return AjaxResult.success(jdbc.queryForList("select session_id sessionId,space_id spaceId,scope_type scopeType,scope_id scopeId,title,update_time updatedAt from wiki_chat_session where created_by=? order by update_time desc limit 50", username));
    }

    @PreAuthorize("@ss.hasPermi('ontology:dashboard:list')")
    @GetMapping("/wiki/messages")
    public AjaxResult wikiMessages(@RequestParam("sessionId") String sessionId) {
        if (sessionId == null || !sessionId.matches("[A-Za-z0-9_-]{8,64}")) return AjaxResult.error("会话编号无效");
        List<Map<String,Object>> messages = jdbc.queryForList("select message_id,role,content,create_time from wiki_chat_message where session_id=? and created_by=? order by message_id desc limit 100", sessionId, SecurityUtils.getUsername());
        if (!messages.isEmpty()) {
            List<Object> ids = new ArrayList<>(); for (Map<String,Object> message : messages) ids.add(message.get("message_id"));
            String marks = String.join(",", Collections.nCopies(ids.size(), "?"));
            Map<Long,List<Map<String,Object>>> sourcesByMessage = new HashMap<>();
            for (Map<String,Object> row : jdbc.queryForList("select c.message_id,c.asset_id,a.asset_name,a.asset_type from wiki_chat_citation c join wiki_asset a on a.asset_id=c.asset_id where c.message_id in (" + marks + ")", ids.toArray())) {
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

    @PreAuthorize("@ss.hasPermi('ontology:wiki:settings')")
    @GetMapping("/wiki/settings")
    public AjaxResult modelSettings() { return AjaxResult.success(llmWiki.modelSettings()); }

    @PreAuthorize("@ss.hasPermi('ontology:wiki:settings')")
    @PutMapping("/wiki/settings")
    public AjaxResult saveModelSettings(@RequestBody(required=false) Map<String,Object> body) {
        try { return AjaxResult.success(llmWiki.saveModelSettings(body, SecurityUtils.getUsername())); }
        catch (Exception e) { return AjaxResult.error(e.getMessage() == null ? "模型设置保存失败" : e.getMessage()); }
    }

    @PreAuthorize("@ss.hasPermi('ontology:wiki:settings')")
    @PostMapping("/wiki/settings/test")
    public AjaxResult testModelSettings() {
        try { return AjaxResult.success(llmWiki.testModelSettings()); }
        catch (Exception e) { return AjaxResult.error(e.getMessage() == null ? "模型 API 调用失败" : e.getMessage()); }
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
        String sql = "select asset_id sourceId,asset_name title,domain_name domain,content from wiki_asset where status='0' and space_id=? and upper(asset_type)<>'URL' and content is not null and char_length(trim(content))>0 order by update_time desc,asset_id desc limit 100";
        List<Map<String,Object>> rows = jdbc.queryForList(sql, spaceId);
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
        String sql = "select asset_id sourceId,asset_name title,content from wiki_asset where status='0' and space_id=? and content is not null and char_length(trim(content))>0 and upper(asset_type) in ('RULE','BUSINESS_RULE','LOGIC','业务规则') order by update_time desc,asset_id desc limit 100";
        List<Map<String,Object>> rows = jdbc.queryForList(sql, spaceId);
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

    private Resource resource(String key) { Resource r = RESOURCES.get(key); if (r == null) throw new IllegalArgumentException("未知资源类型"); return r; }
    private String validateIndustrySpace(String resource, Map<String,Object> data, Long id) {
        if (!"analyses".equals(resource) && !"assets".equals(resource)) return null;
        String field = "analyses".equals(resource) ? "wiki_space_id" : "space_id";
        Object rawSpaceId = data.get(field);
        if (rawSpaceId == null && id != null) {
            String table = "analyses".equals(resource) ? "analysis_task" : "wiki_asset";
            String idColumn = "analyses".equals(resource) ? "task_id" : "asset_id";
            List<Map<String,Object>> rows = jdbc.queryForList("select " + field + " from " + table + " where " + idColumn + "=?", id);
            if (!rows.isEmpty()) rawSpaceId = rows.get(0).get(field);
        }
        if (rawSpaceId == null) return "assets".equals(resource) ? "请选择所属行业知识空间；未归类资料不会参与本体分析" : "请选择行业知识空间；一个分析任务只使用该空间绑定的 Wiki 资产";
        try {
            return jdbc.queryForObject("select count(*) from wiki_space where space_id=? and status='0'", Integer.class, Long.parseLong(String.valueOf(rawSpaceId))) == 0
                    ? "所选行业知识空间不存在或已停用" : null;
        } catch (NumberFormatException e) { return "所选行业知识空间无效"; }
    }
    private void addProposalTaskNames(List<Map<String,Object>> rows) {
        List<Long> taskIds = rows.stream().map(row -> row.get("task_id")).filter(Number.class::isInstance)
                .map(value -> ((Number) value).longValue()).distinct().collect(java.util.stream.Collectors.toList());
        Map<Long,String> names = new HashMap<>();
        if (!taskIds.isEmpty()) {
            String marks = String.join(",", Collections.nCopies(taskIds.size(), "?"));
            jdbc.queryForList("select task_id,task_name from analysis_task where task_id in (" + marks + ")", taskIds.toArray())
                    .forEach(row -> names.put(((Number) row.get("task_id")).longValue(), String.valueOf(row.get("task_name"))));
        }
        rows.forEach(row -> row.put("task_name", row.get("task_id") == null ? "手工创建" : names.getOrDefault(((Number) row.get("task_id")).longValue(), "来源任务已删除")));
    }
    private Map<String,Object> safeData(Resource r, Map<String,Object> input) {
        Map<String,Object> out = new LinkedHashMap<>(); Set<String> allowed = new HashSet<>(Arrays.asList(r.columns.split(",")));
        if (input != null) input.forEach((k,v) -> { if (allowed.contains(k) && v != null) out.put(k, v); }); return out;
    }
    private boolean hasRequired(Resource r, Map<String,Object> data) {
        for (String column : r.required.split(",")) if (!data.containsKey(column) || String.valueOf(data.get(column)).trim().isEmpty()) return false;
        return true;
    }
    private boolean validJdbcUrl(String resource, Map<String,Object> data) {
        if (!"datasources".equals(resource) || !data.containsKey("jdbc_url")) return true;
        String value = String.valueOf(data.get("jdbc_url"));
        if (value.trim().isEmpty()) return true;
        if (value.matches("(?is).*([?;&](password|passwd|pwd)=|//[^/@]+@).*?")) return false;
        String type = data.get("db_type") == null ? "" : String.valueOf(data.get("db_type")).trim().toLowerCase(Locale.ROOT);
        return ("mysql".equals(type) && value.matches("(?is)^jdbc:mysql://.*"))
                || (("postgresql".equals(type) || "postgres".equals(type)) && value.matches("(?is)^jdbc:(postgresql|postgres)://.*"))
                || ("hive".equals(type) && value.matches("(?is)^jdbc:hive2://.*"));
    }
    private boolean isPostgres(Object type) { String value = type == null ? "" : String.valueOf(type).trim().toLowerCase(Locale.ROOT); return "postgresql".equals(value) || "postgres".equals(value); }
    private Object[] append(Object[] values, Object... extra) { Object[] all = Arrays.copyOf(values, values.length + extra.length); System.arraycopy(extra, 0, all, values.length, extra.length); return all; }
    private static class Resource {
        final String table, id, columns, searchColumns, required;
        Resource(String table, String id, String columns, String required) { this.table=table; this.id=id; this.columns=columns; this.required=required; this.searchColumns=String.join(",", columns.replace(",", " ").split(" ")); }
        String selectColumns() { return id + "," + columns + ("ontology_proposal".equals(table) ? ",review_by,review_time,review_comment" : "") + ",create_time,update_by,update_time"; }
    }
}

package com.ontologywiki.ontology.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ontologywiki.ontology.config.OntologyIntegrationProperties;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import javax.annotation.PostConstruct;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

/** Java 内置 LLM Wiki：原始资料与增量生成页面均持久化在本地 MySQL。 */
@Service
public class LlmWikiClient {
    private final OntologyIntegrationProperties properties;
    private final RestTemplate rest;
    private final JdbcTemplate jdbc;
    private final CredentialService credentials;
    private final ObjectMapper json = new ObjectMapper();
    private final AnalysisCandidateResolver candidateResolver = new AnalysisCandidateResolver();
    private final WikiLexicalRetriever wikiRetriever = new WikiLexicalRetriever();

    public LlmWikiClient(OntologyIntegrationProperties properties, RestTemplate rest, JdbcTemplate jdbc, CredentialService credentials) {
        this.properties = properties; this.rest = rest; this.jdbc = jdbc; this.credentials = credentials;
    }

    @PostConstruct
    public void ensureChatSessionTable() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS ontology_llm_settings (setting_id tinyint NOT NULL, enabled tinyint(1) NOT NULL DEFAULT 0, api_base_url varchar(1000), api_key_enc text, model varchar(200), update_by varchar(64), update_time datetime, PRIMARY KEY(setting_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbc.execute("CREATE TABLE IF NOT EXISTS ontology_analysis_memory (memory_id bigint NOT NULL AUTO_INCREMENT, space_id bigint NULL, task_id bigint NOT NULL, proposal_id bigint NULL, memory_type varchar(40) NOT NULL DEFAULT 'ANALYSIS_ANSWER', title varchar(255) NOT NULL, content longtext NOT NULL, source_json longtext NULL, status varchar(30) NOT NULL DEFAULT 'DRAFT', version int NOT NULL DEFAULT 1, created_by varchar(64), create_time datetime, update_by varchar(64), update_time datetime, reviewed_by varchar(64), review_time datetime, PRIMARY KEY(memory_id), KEY idx_analysis_memory_task(task_id,status), KEY idx_analysis_memory_space(space_id,status)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbc.execute("CREATE TABLE IF NOT EXISTS wiki_chat_session (session_id varchar(64) NOT NULL, space_id bigint NULL, scope_type varchar(20) NOT NULL DEFAULT 'ALL', scope_id bigint NULL, context_state_json longtext NULL, title varchar(200) NOT NULL, created_by varchar(64) NOT NULL, create_time datetime NULL, update_time datetime NULL, PRIMARY KEY(session_id,created_by), KEY idx_wiki_chat_session_user(created_by,update_time)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbc.execute("CREATE TABLE IF NOT EXISTS wiki_chat_citation (message_id bigint NOT NULL, asset_id bigint NOT NULL, PRIMARY KEY(message_id,asset_id), KEY idx_wiki_chat_citation_asset(asset_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        try { jdbc.execute("select scope_type from wiki_chat_session where 1=0"); }
        catch (BadSqlGrammarException e) { jdbc.execute("alter table wiki_chat_session add column scope_type varchar(20) NOT NULL DEFAULT 'ALL' after space_id"); }
        try { jdbc.execute("select scope_id from wiki_chat_session where 1=0"); }
        catch (BadSqlGrammarException e) { jdbc.execute("alter table wiki_chat_session add column scope_id bigint NULL after scope_type"); }
        try { jdbc.execute("select context_state_json from wiki_chat_session where 1=0"); }
        catch (BadSqlGrammarException e) { jdbc.execute("alter table wiki_chat_session add column context_state_json longtext NULL after scope_id"); }
        jdbc.update("update wiki_chat_session set scope_type='SPACE',scope_id=space_id where space_id is not null and scope_type='ALL'");
        try { jdbc.execute("select wiki_space_id from analysis_task where 1=0"); }
        catch (BadSqlGrammarException e) { jdbc.execute("alter table analysis_task add column wiki_space_id bigint null after source_id"); }
        try { jdbc.execute("select review_by from ontology_proposal where 1=0"); }
        catch (BadSqlGrammarException e) { jdbc.execute("alter table ontology_proposal add column review_by varchar(64) null after status"); }
        try { jdbc.execute("select review_time from ontology_proposal where 1=0"); }
        catch (BadSqlGrammarException e) { jdbc.execute("alter table ontology_proposal add column review_time datetime null after review_by"); }
        try { jdbc.execute("select review_comment from ontology_proposal where 1=0"); }
        catch (BadSqlGrammarException e) { jdbc.execute("alter table ontology_proposal add column review_comment varchar(1000) null after review_time"); }
        jdbc.update("update ontology_proposal set status='DRAFT' where status='REVIEWED' or (status='APPROVED' and review_by is null)");
        jdbc.update("INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time) VALUES (2043,'草案审核',2005,5,'','','1',0,'F','1','0','ontology:proposals:review','','admin',now())");
        jdbc.update("INSERT IGNORE INTO sys_role_menu(role_id,menu_id) VALUES (1,2043)");
        jdbc.update("INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time) VALUES (2080,'大模型设置',2008,1,'','','1',0,'F','1','0','ontology:wiki:settings','','admin',now())");
        jdbc.update("INSERT IGNORE INTO sys_role_menu(role_id,menu_id) VALUES (1,2080)");
        jdbc.update("update sys_menu set menu_name='行业知识空间',update_by='system',update_time=now() where path='spaces' and component='ontology/spaces/index' and menu_name='Wiki空间'");
        jdbc.update("update sys_menu set menu_name='业务知识问答',update_by='system',update_time=now() where path='chat' and component='ontology/chat/index' and menu_name in ('Wiki问答','LLM Wiki 问答')");
        jdbc.update("INSERT IGNORE INTO wiki_chat_session(session_id,space_id,title,created_by,create_time,update_time) SELECT m.session_id,NULL,COALESCE((SELECT SUBSTRING(m2.content,1,80) FROM wiki_chat_message m2 WHERE m2.session_id=m.session_id AND m2.created_by=m.created_by AND m2.role='user' ORDER BY m2.message_id LIMIT 1),'历史问答'),m.created_by,MIN(m.create_time),MAX(m.create_time) FROM wiki_chat_message m WHERE m.created_by IS NOT NULL GROUP BY m.session_id,m.created_by");
        loadStoredSettings();
    }

    private void loadStoredSettings() {
        List<Map<String,Object>> rows = jdbc.queryForList("select enabled,api_base_url,api_key_enc,model from ontology_llm_settings where setting_id=1");
        if (rows.isEmpty()) return;
        Map<String,Object> row = rows.get(0);
        OntologyIntegrationProperties.LlmWiki cfg = properties.getLlmWiki();
        cfg.setEnabled(Boolean.TRUE.equals(row.get("enabled")) || "1".equals(String.valueOf(row.get("enabled"))));
        cfg.setApiBaseUrl(value(row.get("api_base_url")));
        if (row.get("model") != null) cfg.setModel(String.valueOf(row.get("model")));
        cfg.setApiKey("");
        if (row.get("api_key_enc") != null && !String.valueOf(row.get("api_key_enc")).trim().isEmpty()) cfg.setApiKey(credentials.decrypt(String.valueOf(row.get("api_key_enc"))));
    }

    public Map<String,Object> modelSettings() {
        OntologyIntegrationProperties.LlmWiki cfg = properties.getLlmWiki();
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("enabled", cfg.isEnabled()); result.put("apiBaseUrl", cfg.getApiBaseUrl());
        result.put("model", cfg.getModel()); result.put("apiKeyConfigured", text(cfg.getApiKey()) != null);
        return result;
    }

    public Map<String,Object> saveModelSettings(Map<String,Object> input, String username) {
        if (input == null) throw new IllegalArgumentException("请填写模型配置");
        OntologyIntegrationProperties.LlmWiki cfg = properties.getLlmWiki();
        String baseUrl = input.get("apiBaseUrl") == null ? "" : String.valueOf(input.get("apiBaseUrl")).trim();
        String model = input.get("model") == null ? "" : String.valueOf(input.get("model")).trim();
        String apiKey = input.get("apiKey") == null ? "" : String.valueOf(input.get("apiKey"));
        boolean enabled = Boolean.parseBoolean(String.valueOf(input.get("enabled")));
        boolean clearKey = Boolean.parseBoolean(String.valueOf(input.get("clearApiKey")));
        if (baseUrl.length() > 1000 || model.isEmpty() || model.length() > 200 || apiKey.length() > 4096) throw new IllegalArgumentException("模型地址、名称或密钥长度无效");
        if (!baseUrl.isEmpty() || enabled) try {
            URI uri = URI.create(baseUrl);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) { throw new IllegalArgumentException("模型 API 地址必须是有效的 http 或 https 地址，不能包含账号、查询参数或片段"); }
        String effectiveKey = clearKey ? "" : (apiKey.trim().isEmpty() ? cfg.getApiKey() : apiKey.trim());
        if (enabled && effectiveKey == null || enabled && effectiveKey.trim().isEmpty()) throw new IllegalArgumentException("启用模型前请配置 API Key");
        List<Map<String,Object>> existing = jdbc.queryForList("select setting_id from ontology_llm_settings where setting_id=1");
        if (existing.isEmpty()) {
            jdbc.update("insert into ontology_llm_settings(setting_id,enabled,api_base_url,api_key_enc,model,update_by,update_time) values(1,?,?,?,?,?,now())", enabled, baseUrl, effectiveKey.trim().isEmpty() ? null : credentials.encrypt(effectiveKey), model, username);
        } else if (clearKey) {
            jdbc.update("update ontology_llm_settings set enabled=?,api_base_url=?,api_key_enc=null,model=?,update_by=?,update_time=now() where setting_id=1", enabled, baseUrl, model, username);
        } else if (!apiKey.trim().isEmpty()) {
            jdbc.update("update ontology_llm_settings set enabled=?,api_base_url=?,api_key_enc=?,model=?,update_by=?,update_time=now() where setting_id=1", enabled, baseUrl, credentials.encrypt(effectiveKey), model, username);
        } else {
            jdbc.update("update ontology_llm_settings set enabled=?,api_base_url=?,model=?,update_by=?,update_time=now() where setting_id=1", enabled, baseUrl, model, username);
        }
        cfg.setEnabled(enabled); cfg.setApiBaseUrl(baseUrl); cfg.setModel(model); cfg.setApiKey(effectiveKey);
        return modelSettings();
    }

    public Map<String,Object> testModelSettings() {
        Map<String,Object> result = new LinkedHashMap<>();
        if (!enabled()) throw new IllegalStateException("请先保存并启用模型配置");
        String answer = openAiText("请仅回复：连接成功");
        if (text(answer) == null) throw new IllegalStateException("模型 API 未返回有效内容");
        result.put("connected", true); result.put("model", properties.getLlmWiki().getModel()); result.put("message", "模型 API 调用成功");
        return result;
    }

    private String value(Object value) { return value == null ? "" : String.valueOf(value); }

    /** 保存分析答案的一个可追溯版本，并同步最新草案中的业务语义说明。 */
    public Map<String, Object> saveAnalysisAnswer(long taskId, String answer, boolean confirm, String username) {
        return saveAnalysisAnswer(taskId, null, answer, confirm, username);
    }

    /** 保存答案到当前分析任务指定的草案版本；未指定时兼容旧行为，写入最新版本。 */
    public Map<String, Object> saveAnalysisAnswer(long taskId, Long requestedProposalId, String answer, boolean confirm, String username) {
        if (answer == null || answer.trim().isEmpty() || answer.length() > 60000) throw new IllegalArgumentException("分析答案不能为空且不超过60000字");
        Map<String, Object> task = jdbc.queryForMap("select task_name,wiki_space_id from analysis_task where task_id=?", taskId);
        List<Map<String, Object>> proposals = requestedProposalId == null
                ? jdbc.queryForList("select proposal_id,config_json from ontology_proposal where task_id=? order by proposal_id desc limit 1", taskId)
                : jdbc.queryForList("select proposal_id,config_json from ontology_proposal where task_id=? and proposal_id=? limit 1", taskId, requestedProposalId);
        if (requestedProposalId != null && proposals.isEmpty()) throw new IllegalArgumentException("配置草案不存在或不属于当前分析任务");
        Long proposalId = proposals.isEmpty() ? null : ((Number) proposals.get(0).get("proposal_id")).longValue();
        int version = 1;
        List<Map<String, Object>> versions = jdbc.queryForList("select coalesce(max(version),0) version from ontology_analysis_memory where task_id=?", taskId);
        if (!versions.isEmpty() && versions.get(0).get("version") != null) version = ((Number) versions.get(0).get("version")).intValue() + 1;
        String status = confirm ? "CONFIRMED" : "DRAFT";
        String sourceJson = "{}";
        Map<String,Object> savedConfig = Collections.emptyMap();
        try {
            Map<String, Object> source = new LinkedHashMap<>(); source.put("taskId", taskId); source.put("proposalId", proposalId); source.put("kind", "ANALYSIS_ANSWER"); sourceJson = json.writeValueAsString(source);
            if (!proposals.isEmpty()) {
                Map<String, Object> config = json.readValue(String.valueOf(proposals.get(0).get("config_json")), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
                config.put("llmAnswer", answer.trim()); config.put("answerStatus", status); config.put("answerVersion", version);
                savedConfig = config;
                if (config.get("citedSources") != null) { source.put("citedSources", config.get("citedSources")); sourceJson = json.writeValueAsString(source); }
                jdbc.update("update ontology_proposal set config_json=?,update_by=?,update_time=now() where proposal_id=?", json.writeValueAsString(config), username, proposalId);
            }
        } catch (Exception e) { throw new IllegalStateException("分析答案保存失败"); }
        String memoryContent = confirm ? analysisKnowledgeMarkdown(answer.trim(), savedConfig) : answer.trim();
        jdbc.update("insert into ontology_analysis_memory(space_id,task_id,proposal_id,memory_type,title,content,source_json,status,version,created_by,create_time,update_by,update_time,reviewed_by,review_time) values(?,?,?,'ANALYSIS_ANSWER',?,?,?,?,?,?,now(),?,now(),?,case when ?='CONFIRMED' then now() else null end)",
                task.get("wiki_space_id"), taskId, proposalId, String.valueOf(task.get("task_name")) + " · 分析答案 v" + version, memoryContent, sourceJson, status, version, username, username, confirm ? username : null, status);
        jdbc.update("update analysis_task set summary=?,update_by=?,update_time=now() where task_id=?", clip(answer.trim(), 60000), username, taskId);
        List<Map<String, Object>> latest = jdbc.queryForList("select memory_id,task_id,proposal_id,memory_type,title,content,status,version,created_by,create_time,reviewed_by,review_time from ontology_analysis_memory where task_id=? order by memory_id desc limit 1", taskId);
        if (confirm && !latest.isEmpty()) {
            Map<String,Object> memory = latest.get(0);
            String tag = "ANALYSIS_MEMORY:" + memory.get("memory_id");
            String title = "分析知识 · " + task.get("task_name") + " · v" + version;
            String content = "# " + title + "\n\n" + memoryContent + "\n\n> 来源：分析任务 #" + taskId + "，配置草案 #" + (proposalId == null ? "—" : proposalId) + "。该页面由分析者确认后沉淀，可在后续分析中作为已确认知识参考。";
            List<Map<String,Object>> pages = jdbc.queryForList("select asset_id from wiki_asset where tags=? limit 1", tag);
            if (pages.isEmpty()) jdbc.update("insert into wiki_asset(space_id,asset_name,asset_type,description,content,tags,status,create_by,create_time,update_by,update_time) values(?,?,'WIKI_PAGE',?,?,?,'0',?,now(),?,now())", task.get("wiki_space_id"), title, "已确认分析知识", content, tag, username, username);
            else jdbc.update("update wiki_asset set space_id=?,asset_name=?,description=?,content=?,update_by=?,update_time=now() where asset_id=?", task.get("wiki_space_id"), title, "已确认分析知识", content, username, pages.get(0).get("asset_id"));
            refreshIndex();
        }
        return latest.isEmpty() ? Collections.<String, Object>emptyMap() : latest.get(0);
    }

    /** 保存问答确认后的结构修正，并在同一事务中沉淀结构与说明。 */
    @Transactional
    public Map<String,Object> saveAnalysisResult(long taskId, Long proposalId, String answer, List<?> requestedChanges, String username) {
        if (answer == null || answer.trim().isEmpty()) throw new IllegalArgumentException("请先完成分析说明");
        if (proposalId == null) throw new IllegalArgumentException("当前分析没有关联配置草案，无法保存结构");
        List<Map<String,Object>> proposals = jdbc.queryForList("select config_json from ontology_proposal where task_id=? and proposal_id=? limit 1", taskId, proposalId);
        if (proposals.isEmpty()) throw new IllegalArgumentException("配置草案不存在或不属于当前分析任务");
        Map<String,Object> base = readAnalysisConfig(proposals.get(0).get("config_json"));
        List<Map<String,Object>> changes = normalizeAnalysisChanges(base, requestedChanges, true);
        Map<String,Object> updated = applyAnalysisChanges(base, changes);
        updated.put("analysisCorrections", mergeAnalysisChangeMarkers(base,
                normalizeAnalysisChanges(base, base.get("analysisCorrections") instanceof List ? (List<?>) base.get("analysisCorrections") : Collections.emptyList(), false, false), changes));
        if (!changes.isEmpty()) {
            jdbc.update("update ontology_proposal set config_json=?,status='DRAFT',review_by=null,review_time=null,review_comment=null,update_by=?,update_time=now() where task_id=? and proposal_id=?",
                    writeJson(updated), username, taskId, proposalId);
        }
        return saveAnalysisAnswer(taskId, proposalId, answer, true, username);
    }

    public List<Map<String, Object>> listAnalysisMemory(long taskId) {
        return jdbc.queryForList("select memory_id,task_id,proposal_id,memory_type,title,content,status,version,created_by,create_time,reviewed_by,review_time from ontology_analysis_memory where task_id=? order by memory_id desc", taskId);
    }

    public Map<String, Object> latestAnalysisMemory(long taskId) {
        List<Map<String, Object>> rows = jdbc.queryForList("select memory_id,task_id,proposal_id,memory_type,title,content,status,version,created_by,create_time,reviewed_by,review_time from ontology_analysis_memory where task_id=? order by memory_id desc limit 1", taskId);
        return rows.isEmpty() ? Collections.<String, Object>emptyMap() : rows.get(0);
    }

    /** 在当前分析范围内使用 Wiki、草案和已确认知识优化答案，返回未保存的候选版本。 */
    public Map<String, Object> refineAnalysisAnswer(long taskId, String currentAnswer, String question) {
        if (!enabled()) throw new IllegalStateException("内置 LLM Wiki 未配置模型 API");
        if (question == null || question.trim().isEmpty()) throw new IllegalArgumentException("请输入希望优化的内容");
        Map<String, Object> task = jdbc.queryForMap("select wiki_space_id from analysis_task where task_id=?", taskId);
        Long spaceId = task.get("wiki_space_id") == null ? null : ((Number) task.get("wiki_space_id")).longValue();
        String prompt = "当前分析答案：\n" + clip(currentAnswer == null ? "" : currentAnswer, 30000) + "\n\n分析者希望优化：\n" + clip(question.trim(), 8000) + "\n\n请结合当前分析任务的 Wiki 资料、配置候选和已确认分析知识，输出一份可以直接替换当前答案的完整中文版本。保留可验证事实，明确标记仍需确认的内容，不要输出 JSON，不要添加与任务无关的内容。";
        Map<String, Object> result = queryResult(prompt, false, spaceId, Collections.<Map<String, Object>>emptyList(), "ANALYSIS", taskId);
        result.put("draft", true); return result;
    }

    /** 给后续分析和分析问答提供空间级已确认知识。 */
    public String confirmedKnowledgeContext(Long taskId, Long spaceId) {
        List<Map<String, Object>> rows;
        if (taskId == null && spaceId == null) return "";
        if (taskId != null && spaceId != null) rows = jdbc.queryForList("select title,content,status,version,task_id from ontology_analysis_memory where status='CONFIRMED' and (task_id=? or space_id=?) order by update_time desc,memory_id desc limit 80", taskId, spaceId);
        else if (taskId != null) rows = jdbc.queryForList("select title,content,status,version,task_id from ontology_analysis_memory where status='CONFIRMED' and task_id=? order by update_time desc,memory_id desc limit 80", taskId);
        else rows = jdbc.queryForList("select title,content,status,version,task_id from ontology_analysis_memory where status='CONFIRMED' and space_id=? order by update_time desc,memory_id desc limit 80", spaceId);
        StringBuilder out = new StringBuilder();
        for (Map<String, Object> row : rows) out.append("\n- ").append(row.get("title")).append("（任务 #").append(row.get("task_id")).append("，版本 ").append(row.get("version")).append("）\n  ").append(clip(String.valueOf(row.get("content")), 3000));
        return out.toString().trim();
    }

    public boolean enabled() {
        OntologyIntegrationProperties.LlmWiki cfg = properties.getLlmWiki();
        return cfg != null && cfg.isEnabled() && text(cfg.getApiBaseUrl()) != null && text(cfg.getApiKey()) != null;
    }

    public String query(String question) { return query(question, false); }

    public String query(String question, boolean saveAsPage) {
        Object answer = queryResult(question, saveAsPage).get("answer"); return answer == null ? "" : String.valueOf(answer);
    }

    public Map<String, Object> queryResult(String question, boolean saveAsPage) {
        return queryResult(question, saveAsPage, null, Collections.<Map<String, Object>>emptyList());
    }

    public Map<String, Object> queryResult(String question, boolean saveAsPage, Long spaceId, List<Map<String, Object>> history) {
        return queryResult(question, saveAsPage, spaceId, history, spaceId == null ? "ALL" : "SPACE", null);
    }

    public Map<String, Object> queryResult(String question, boolean saveAsPage, Long spaceId, List<Map<String, Object>> history, String scopeType, Long scopeId) {
        return queryResult(question, saveAsPage, spaceId, history, scopeType, scopeId, null);
    }

    public Map<String, Object> queryResult(String question, boolean saveAsPage, Long spaceId, List<Map<String, Object>> history, String scopeType, Long scopeId, Long proposalId) {
        return queryResult(question, saveAsPage, spaceId, history, scopeType, scopeId, proposalId, Collections.emptyList());
    }

    public Map<String, Object> queryResult(String question, boolean saveAsPage, Long spaceId, List<Map<String, Object>> history, String scopeType, Long scopeId, Long proposalId, List<?> previousChanges) {
        return queryResult(question, saveAsPage, spaceId, history, scopeType, scopeId, proposalId, previousChanges, Collections.<String, Object>emptyMap(), false);
    }

    public Map<String, Object> queryResult(String question, boolean saveAsPage, Long spaceId, List<Map<String, Object>> history, String scopeType, Long scopeId, Long proposalId, List<?> previousChanges, Map<String, Object> contextState) {
        return queryResult(question, saveAsPage, spaceId, history, scopeType, scopeId, proposalId, previousChanges, contextState, false);
    }

    public Map<String, Object> queryResult(String question, boolean saveAsPage, Long spaceId, List<Map<String, Object>> history, String scopeType, Long scopeId, Long proposalId, List<?> previousChanges, Map<String, Object> contextState, boolean allowDeterministicChange) {
        requireEnabled();
        if (text(question) == null || question.length() > 100000) throw new IllegalArgumentException("问题不能为空且不超过100000字");
        boolean analysisScope = "ANALYSIS".equals(scopeType);
        String assetFilter = "ASSET".equals(scopeType) ? " and (asset_id=? or tags=?)" : (analysisScope && spaceId == null) ? " and 1=0" : spaceId == null ? "" : " and space_id=?";
        String sourceTypes = "ASSET".equals(scopeType) ? "asset_type<>'WIKI_PAGE'" : "asset_type not in ('WIKI_PAGE','URL')";
        String sourceSql = "select asset_id,asset_name,asset_type,domain_name,description from wiki_asset where status='0' and " + sourceTypes + " and content is not null and char_length(trim(content))>0" + assetFilter + " order by update_time desc,asset_id desc limit 100";
        String pageSql = "select asset_id,asset_name,asset_type,domain_name,description from wiki_asset where status='0' and asset_type='WIKI_PAGE' and coalesce(tags,'')<>'SYSTEM_INDEX' and content is not null and char_length(trim(content))>0" + assetFilter + " order by update_time desc,asset_id desc limit 200";
        Object[] filterArgs = "ASSET".equals(scopeType) ? new Object[] {scopeId, "SOURCE:" + scopeId} : (spaceId == null ? new Object[0] : new Object[] {spaceId});
        List<Map<String, Object>> sources = analysisScope ? new ArrayList<>() : jdbc.queryForList(sourceSql, filterArgs);
        List<Map<String, Object>> pages = analysisScope ? new ArrayList<>() : jdbc.queryForList(pageSql, filterArgs);
        List<Map<String, Object>> index = new ArrayList<>(); index.addAll(pages); index.addAll(sources);
        String conversation = conversationText(history);
        String retrievalQuestion = clip(question, 2000) + (conversation.isEmpty() ? "" : "\n会话上下文：" + clip(conversation, 1600));
        Map<String,Object> analysisState = analysisScope ? analysisTaskState(scopeId, proposalId, previousChanges, question, contextState, allowDeterministicChange) : Collections.<String,Object>emptyMap();
        String taskContext = String.valueOf(analysisState.getOrDefault("context", ""));
        List<Map<String, Object>> selected = analysisScope ? Collections.<Map<String, Object>>emptyList() : wikiRetriever.rank(index, retrievalQuestion, 3);
        String context = analysisScope ? "" : selected.isEmpty() ? "暂无匹配的 Wiki 资料。" : selected.stream().map(r -> {
            Map<String,Object> content = jdbc.queryForMap("select content from wiki_asset where asset_id=?", ((Number) r.get("asset_id")).longValue());
            String evidence = wikiRetriever.evidenceSnippet(value(content.get("content")), retrievalQuestion, 1200);
            return "[" + r.get("asset_type") + "] " + r.get("asset_name") + "\n" + evidence;
        }).collect(Collectors.joining("\n\n"));
        String prompt = analysisScope
                ? "你是本体分析结果校对助手。只依据本轮提供的少量草案候选和会话焦点回答，不要假设未提供的字段。精确修改由服务端按草案候选生成；只有无法确定目标或修改内容时才在 changes 中提出候选修正。不得编造候选 ID。只返回 JSON：{\"answer\":\"面向用户的中文说明\",\"changes\":[{\"category\":\"properties\",\"id\":\"候选 ID\",\"field\":\"dataType\",\"value\":\"TEXT\",\"reason\":\"修改原因\"}]}。changes 只能修改提供候选中的可编辑字段。目标不唯一时先追问，changes 返回空数组。不要返回配置草案 JSON、Wiki Markdown 或泛化资料摘要。\n\n当前分析任务与相关候选：\n" + clip(taskContext, 4500)
                : "请根据以下本体 Wiki 页面、来源资料和分析任务上下文回答问题；业务问题没有资料证据时，明确说明当前资料中未找到依据，不要猜测。若用户是在问候或闲聊，请自然、简短地回应，不要求 Wiki 资料。分析任务中的候选配置尚待人工审核。中文作答，并结合对话上下文回答本轮问题。\n\n资料：\n" + clip(context, 4200) + (taskContext.isEmpty() ? "" : "\n\n关联分析任务与候选内容：\n" + clip(taskContext, 1500));
        prompt += (conversation.isEmpty() ? "" : "\n\n此前对话：\n" + clip(conversation, 1800)) + "\n\n本轮问题：\n" + clipQuestion(question, 1800);
        String rawAnswer = openAiText(prompt);
        JsonNode analysisReply = analysisScope ? parse(rawAnswer) : null;
        String answer = analysisScope && analysisReply != null && analysisReply.path("answer").isTextual()
                ? analysisReply.path("answer").asText() : rawAnswer;
        List<Map<String,Object>> citedSources = selected.stream().map(r -> {
            Map<String,Object> cited = new LinkedHashMap<>();
            cited.put("assetId", ((Number) r.get("asset_id")).longValue());
            cited.put("assetType", r.get("asset_type"));
            cited.put("title", r.get("asset_name"));
            return cited;
        }).collect(Collectors.toList());
        Map<String,Object> result = new LinkedHashMap<>(); result.put("answer", answer); result.put("provider", "internal");
        if (analysisScope) {
            List<Map<String,Object>> combined = new ArrayList<>((List<Map<String,Object>>) analysisState.getOrDefault("changes", Collections.emptyList()));
            List<Map<String,Object>> deterministicChanges = (List<Map<String,Object>>) analysisState.getOrDefault("deterministicChanges", Collections.emptyList());
            combined.addAll(deterministicChanges);
            List<Map<String,Object>> modelChanges = new ArrayList<>();
            if (deterministicChanges.isEmpty() && !Boolean.TRUE.equals(analysisState.get("blockModelChanges"))
                    && analysisReply != null && analysisReply.path("changes").isArray()) {
                for (JsonNode change : analysisReply.path("changes")) {
                    if (change.isObject()) modelChanges.add(json.convertValue(change, new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() { }));
                }
            }
            @SuppressWarnings("unchecked") Map<String,Object> base = (Map<String,Object>) analysisState.getOrDefault("baseConfig", Collections.emptyMap());
            combined.addAll(modelChanges);
            List<Map<String,Object>> activeChanges = normalizeAnalysisChanges(base, combined, false);
            List<Map<String,Object>> storedChanges = normalizeAnalysisChanges(base,
                    base.get("analysisCorrections") instanceof List ? (List<?>) base.get("analysisCorrections") : Collections.emptyList(), false, false);
            List<Map<String,Object>> changes = mergeAnalysisChangeMarkers(base, storedChanges, activeChanges);
            result.put("analysisChanges", changes);
            List<Map<String,Object>> newChanges = new ArrayList<>(deterministicChanges); newChanges.addAll(modelChanges);
            result.put("analysisUpdatedChanges", normalizeAnalysisChanges(base, newChanges, false));
            Map<String,Object> working = applyAnalysisChanges(base, activeChanges); working.put("analysisCorrections", changes);
            result.put("analysisConfig", working);
            if (allowDeterministicChange) result.put("contextState", analysisState.get("contextState"));
        }
        result.put("citedPages", citedSources.stream().map(r -> r.get("title")).collect(Collectors.toList()));
        result.put("citedSources", citedSources);
        result.put("citationIds", citedSources.stream().map(r -> r.get("assetId")).collect(Collectors.toList()));
        if (saveAsPage && !analysisScope && !selected.isEmpty()) {
            String title = "问答 " + LocalDateTime.now().toString().replace(':', '-');
            Map<String,String> page = new LinkedHashMap<>(); page.put("slug", title); page.put("content", "# " + title + "\n\n" + result.get("answer")); createPage(page, spaceId);
            result.put("savedToWiki", true); result.put("pageTitle", title);
        }
        return result;
    }

    private Map<String,Object> analysisTaskState(Long taskId, Long proposalId, List<?> previousChanges, String question, Map<String, Object> savedContextState, boolean allowDeterministicChange) {
        if (taskId == null) return Collections.emptyMap();
        Map<String,Object> task = proposalId == null
                ? jdbc.queryForMap("select t.task_name,t.scope,t.status,t.summary,t.wiki_space_id,d.name datasource_name,d.db_type,d.database_name,p.proposal_id,p.config_json from analysis_task t left join ontology_datasource d on d.datasource_id=t.source_id left join ontology_proposal p on p.proposal_id=(select p2.proposal_id from ontology_proposal p2 where p2.task_id=t.task_id order by p2.update_time desc,p2.proposal_id desc limit 1) where t.task_id=?", taskId)
                : jdbc.queryForMap("select t.task_name,t.scope,t.status,t.summary,t.wiki_space_id,d.name datasource_name,d.db_type,d.database_name,p.proposal_id,p.config_json from analysis_task t left join ontology_datasource d on d.datasource_id=t.source_id left join ontology_proposal p on p.proposal_id=? and p.task_id=t.task_id where t.task_id=?", proposalId, taskId);
        Map<String,Object> baseConfig = readAnalysisConfig(task.get("config_json"));
        List<Map<String,Object>> changes = normalizeAnalysisChanges(baseConfig, previousChanges, true);
        Map<String,Object> workingConfig = applyAnalysisChanges(baseConfig, changes);
        Object activeProposalId = task.get("proposal_id");
        Map<String,Object> savedFocus = Collections.emptyMap();
        if (savedContextState != null && Objects.equals(value(activeProposalId), value(savedContextState.get("proposalId")))
                && savedContextState.get("focusCandidate") instanceof Map) {
            savedFocus = (Map<String,Object>) savedContextState.get("focusCandidate");
        }
        AnalysisCandidateResolver.Result match = candidateResolver.resolve(workingConfig, question, savedFocus);
        List<Map<String,Object>> deterministicChanges = allowDeterministicChange ? analysisTypeChange(question, match.unique) : Collections.<Map<String,Object>>emptyList();
        StringBuilder context = new StringBuilder("任务：").append(task.get("task_name"));
        // 先放已定位候选，最终长度裁剪时优先保留字段 ID 与物理名称。
        appendContext(context, "对象、字段与关系候选", analysisCandidateContext(workingConfig, question, match));
        appendContext(context, "分析范围", task.get("scope"));
        appendContext(context, "数据源", task.get("datasource_name"));
        appendContext(context, "数据库类型", task.get("db_type"));
        appendContext(context, "数据库名", task.get("database_name"));
        appendContext(context, "任务状态", task.get("status"));
        appendContext(context, "当前分析答案", clip(value(task.get("summary")), 600));

        Map<String,Object> nextState = new LinkedHashMap<>();
        nextState.put("version", 1); nextState.put("proposalId", activeProposalId);
        if (match.unique != null && !match.unique.id.isEmpty()) {
            Map<String,Object> focus = new LinkedHashMap<>();
            focus.put("category", "properties"); focus.put("id", match.unique.id); focus.put("qualifiedName", match.unique.qualifiedName());
            nextState.put("focusCandidate", focus);
            context.append("\n会话焦点：").append(match.unique.qualifiedName()).append("；候选ID=").append(match.unique.id);
        }
        Map<String,Object> state = new LinkedHashMap<>();
        state.put("context", clip(context.toString(), 5000)); state.put("baseConfig", baseConfig); state.put("config", workingConfig);
        state.put("changes", changes); state.put("deterministicChanges", deterministicChanges); state.put("contextState", nextState);
        state.put("blockModelChanges", match.ambiguous);
        return state;
    }

    String analysisCandidateContext(Object rawConfig) {
        return analysisCandidateContext(rawConfig, "");
    }

    String analysisCandidateContext(Object rawConfig, String question) {
        if (rawConfig == null || String.valueOf(rawConfig).trim().isEmpty()) return "";
        Map<String,Object> config = readAnalysisConfig(rawConfig);
        AnalysisCandidateResolver.Result match = candidateResolver.resolve(config, question, null);
        return analysisCandidateContext(config, question, match);
    }

    private String analysisCandidateContext(Map<String,Object> config, String question, AnalysisCandidateResolver.Result match) {
        StringBuilder result = new StringBuilder();
        result.append("候选总数：对象=").append(size(config.get("objects")))
                .append("，字段=").append(size(config.get("properties")))
                .append("，关系=").append(size(config.get("relations")))
                .append("，逻辑=").append(size(config.get("logics")))
                .append("，动作=").append(size(config.get("actions")))
                .append("，服务流=").append(size(config.get("serviceFlows")))
                .append("，权限=").append(size(config.get("permissionCandidates"))).append('\n');
        if (!match.matches.isEmpty()) {
            result.append(match.ambiguous ? "字段候选（存在歧义，请先确认）：\n" : "相关字段候选：\n");
            for (AnalysisCandidateResolver.Candidate candidate : match.matches) appendResolvedCandidate(result, candidate);
        } else {
            boolean overview = question == null || question.trim().isEmpty() || containsAny(question, "概述", "有哪些", "当前", "结构", "总体", "全部");
            appendCandidateNodes(result, "对象", config.get("objects"), question, overview, "id", "name", "table", "technicalName", "description");
            appendCandidateNodes(result, "字段", config.get("properties"), question, overview, "id", "name", "column", "technicalName", "dataType", "objectId", "description");
            appendCandidateNodes(result, "关系", config.get("relations"), question, overview, "id", "name", "sourceObjectId", "targetObjectId", "sourceColumn", "targetColumn", "cardinality", "description");
            appendCandidateNodes(result, "业务逻辑", config.get("logics"), question, overview, "id", "name", "conditionText", "risk", "status", "trigger", "description");
            appendCandidateNodes(result, "业务动作", config.get("actions"), question, overview, "id", "name", "operation", "objectId", "risk", "status", "trigger", "description");
            appendCandidateNodes(result, "服务流", config.get("serviceFlows"), question, overview, "id", "name", "steps", "description");
            appendCandidateNodes(result, "权限候选", config.get("permissionCandidates"), question, overview, "id", "actionId", "resourceId", "operation", "subjectType", "suggestedSubjects", "rowFilterRequired", "status");
        }
        return clip(result.toString(), 3500);
    }

    private void appendResolvedCandidate(StringBuilder result, AnalysisCandidateResolver.Candidate candidate) {
        result.append("- id=").append(candidate.id).append("；name=").append(candidate.name)
                .append("；table=").append(candidate.table).append("；objectName=").append(candidate.objectName)
                .append("；column=").append(candidate.column).append("；dataType=").append(candidate.dataType).append('\n');
    }

    private void appendCandidateNodes(StringBuilder result, String section, Object rawNodes, String question, boolean includeExamples, String... fields) {
        if (!(rawNodes instanceof List) || ((List<?>) rawNodes).isEmpty()) return;
        List<Map<String,Object>> nodes = new ArrayList<>();
        for (Object node : (List<?>) rawNodes) if (node instanceof Map) nodes.add((Map<String,Object>) node);
        List<Map<String,Object>> relevant = new ArrayList<>();
        for (Map<String,Object> node : nodes) if (matchesNode(node, question, fields)) relevant.add(node);
        if (relevant.isEmpty() && includeExamples) relevant.addAll(nodes.subList(0, Math.min(2, nodes.size())));
        if (relevant.isEmpty()) return;
        result.append(section).append("：\n");
        for (int i = 0; i < Math.min(2, relevant.size()); i++) {
            Map<String,Object> node = relevant.get(i);
            result.append("- "); boolean wrote = false;
            for (String field : fields) {
                Object value = node.get(field);
                if (value == null || String.valueOf(value).trim().isEmpty()) continue;
                if (wrote) result.append("；");
                result.append(field).append('=').append(clip(String.valueOf(value), 140)); wrote = true;
            }
            result.append('\n');
        }
    }

    private boolean matchesNode(Map<String,Object> node, String question, String... fields) {
        if (question == null || question.trim().isEmpty()) return false;
        String normalizedQuestion = question.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        for (String field : fields) {
            Object value = node.get(field);
            if (value == null) continue;
            String candidate = String.valueOf(value).trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
            if (candidate.length() >= 2 && normalizedQuestion.contains(candidate)) return true;
        }
        return false;
    }

    private boolean containsAny(String text, String... terms) {
        if (text == null) return false;
        for (String term : terms) if (text.contains(term)) return true;
        return false;
    }

    private String clipQuestion(String question, int max) {
        if (question == null || question.length() <= max) return question == null ? "" : question;
        int side = (max - 18) / 2;
        return question.substring(0, side) + "\n……问题中段省略……\n" + question.substring(question.length() - side);
    }

    private int size(Object value) { return value instanceof List ? ((List<?>) value).size() : 0; }

    List<Map<String,Object>> analysisTypeChange(String question, AnalysisCandidateResolver.Candidate candidate) {
        if (candidate == null || question == null) return Collections.emptyList();
        java.util.regex.Matcher matcher = Pattern.compile("(?i)(?:改(?:成|为)|设置为|设为|应该(?:是|为)|类型(?:为|是)|调整为|变为)\\s*[：:=]?\\s*[`\\\"“”']?([A-Z][A-Z0-9_]*(?:\\s*\\(\\s*\\d+(?:\\s*,\\s*\\d+)?\\s*\\))?)(?![A-Z0-9_])").matcher(question);
        if (!matcher.find()) return Collections.emptyList();
        String type = matcher.group(1).replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        if (!type.matches("[A-Z][A-Z0-9_]*(?:\\(\\d+(?:,\\d+)?\\))?")) return Collections.emptyList();
        Map<String,Object> change = new LinkedHashMap<>(); change.put("category", "properties"); change.put("id", candidate.id);
        change.put("field", "dataType"); change.put("value", type); change.put("reason", "根据用户明确指定的字段类型生成");
        return Collections.<Map<String,Object>>singletonList(change);
    }

    private static final Map<String,Set<String>> ANALYSIS_EDITABLE_FIELDS = new LinkedHashMap<>();
    static {
        ANALYSIS_EDITABLE_FIELDS.put("objects", new HashSet<>(Arrays.asList("name", "description")));
        ANALYSIS_EDITABLE_FIELDS.put("properties", new HashSet<>(Arrays.asList("name", "dataType", "description", "nullable", "isPrimaryKey")));
        ANALYSIS_EDITABLE_FIELDS.put("relations", new HashSet<>(Arrays.asList("name", "description", "cardinality")));
        ANALYSIS_EDITABLE_FIELDS.put("logics", new HashSet<>(Arrays.asList("name", "conditionText", "risk", "description")));
        ANALYSIS_EDITABLE_FIELDS.put("actions", new HashSet<>(Arrays.asList("name", "operation", "risk", "description")));
        ANALYSIS_EDITABLE_FIELDS.put("serviceFlows", new HashSet<>(Arrays.asList("name", "description", "trigger")));
        ANALYSIS_EDITABLE_FIELDS.put("permissionCandidates", new HashSet<>(Arrays.asList("operation", "subjectType", "rowFilterRequired")));
    }

    private Map<String,Object> readAnalysisConfig(Object raw) {
        if (raw == null || String.valueOf(raw).trim().isEmpty()) return new LinkedHashMap<>();
        try { return json.readValue(String.valueOf(raw), new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() { }); }
        catch (Exception e) { throw new IllegalStateException("分析草案结构无法解析"); }
    }

    private String writeJson(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("分析草案结构序列化失败"); }
    }

    List<Map<String,Object>> normalizeAnalysisChanges(Map<String,Object> base, List<?> requested, boolean strict) {
        return normalizeAnalysisChanges(base, requested, strict, true);
    }

    private List<Map<String,Object>> normalizeAnalysisChanges(Map<String,Object> base, List<?> requested, boolean strict, boolean removeUnchanged) {
        Map<String,Map<String,Object>> latest = new LinkedHashMap<>();
        if (requested == null) return new ArrayList<>();
        if (requested.size() > 200) throw new IllegalArgumentException("单次分析修正不能超过200项");
        for (Object raw : requested) {
            if (!(raw instanceof Map)) { if (strict) throw new IllegalArgumentException("分析修正格式无效"); else continue; }
            Map<?,?> item = (Map<?,?>) raw;
            String category = item.get("category") == null ? "" : String.valueOf(item.get("category"));
            String id = item.get("id") == null ? "" : String.valueOf(item.get("id"));
            String field = item.get("field") == null ? "" : String.valueOf(item.get("field"));
            Object value = item.get("value");
            boolean validValue = value instanceof String || value instanceof Number || value instanceof Boolean;
            List<?> candidates = base.get(category) instanceof List ? (List<?>) base.get(category) : Collections.emptyList();
            boolean found = candidates.stream().anyMatch(candidate -> candidate instanceof Map && Objects.equals(id, String.valueOf(((Map<?,?>) candidate).get("id"))));
            if (id.trim().isEmpty() || !found || !ANALYSIS_EDITABLE_FIELDS.containsKey(category) || !ANALYSIS_EDITABLE_FIELDS.get(category).contains(field) || !validValue) {
                if (strict) throw new IllegalArgumentException("分析修正必须对应现有结构中的可编辑字段");
                continue;
            }
            String text = String.valueOf(value).trim();
            if (text.isEmpty() || text.length() > 4000) { if (strict) throw new IllegalArgumentException("分析修正内容不能为空且不超过4000字"); else continue; }
            if ("risk".equals(field) && !Arrays.asList("LOW", "MEDIUM", "HIGH", "CRITICAL").contains(text.toUpperCase(Locale.ROOT))) { if (strict) throw new IllegalArgumentException("风险等级无效"); else continue; }
            if ("operation".equals(field) && !Arrays.asList("READ", "SELECT", "INSERT", "UPDATE", "DELETE").contains(text.toUpperCase(Locale.ROOT))) { if (strict) throw new IllegalArgumentException("操作类型无效"); else continue; }
            if ("subjectType".equals(field) && !Arrays.asList("ROLE", "USER", "GROUP", "SERVICE").contains(text.toUpperCase(Locale.ROOT))) { if (strict) throw new IllegalArgumentException("主体类型无效"); else continue; }
            if ("trigger".equals(field) && !Arrays.asList("MCP_REQUEST", "MANUAL", "SCHEDULE", "EVENT", "WEBHOOK").contains(text.toUpperCase(Locale.ROOT))) { if (strict) throw new IllegalArgumentException("触发方式无效"); else continue; }
            if ("cardinality".equals(field) && !Arrays.asList("ONE_TO_ONE", "ONE_TO_MANY", "MANY_TO_ONE", "MANY_TO_MANY").contains(text.toUpperCase(Locale.ROOT))) { if (strict) throw new IllegalArgumentException("关系基数无效"); else continue; }
            Object normalizedValue = value instanceof String ? text : value;
            if (Arrays.asList("risk", "operation", "subjectType", "trigger", "cardinality").contains(field)) normalizedValue = text.toUpperCase(Locale.ROOT);
            if (Arrays.asList("nullable", "isPrimaryKey", "rowFilterRequired").contains(field)) {
                if (!("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text))) { if (strict) throw new IllegalArgumentException("布尔值无效"); else continue; }
                normalizedValue = Boolean.parseBoolean(text);
            }
            Map<String,Object> change = new LinkedHashMap<>();
            change.put("category", category); change.put("id", id); change.put("field", field); change.put("value", normalizedValue);
            if (item.get("reason") != null) change.put("reason", String.valueOf(item.get("reason")).trim());
            latest.put(category + "\u0000" + id + "\u0000" + field, change);
        }
        List<Map<String,Object>> changes = new ArrayList<>();
        for (Map<String,Object> change : latest.values()) {
            Map<?,?> candidate = findAnalysisCandidate(base, String.valueOf(change.get("category")), String.valueOf(change.get("id")));
            if (removeUnchanged && candidate != null && Objects.equals(candidate.get(change.get("field")), change.get("value"))) continue;
            changes.add(change);
        }
        return changes;
    }

    private List<Map<String,Object>> mergeAnalysisChangeMarkers(Map<String,Object> base, List<Map<String,Object>> existing, List<Map<String,Object>> updates) {
        Map<String,Map<String,Object>> merged = new LinkedHashMap<>();
        for (Map<String,Object> change : existing) merged.put(changeKey(change), change);
        for (Map<String,Object> change : updates) merged.put(changeKey(change), change);
        return new ArrayList<>(merged.values());
    }

    private String changeKey(Map<String,Object> change) {
        return change.get("category") + "\u0000" + change.get("id") + "\u0000" + change.get("field");
    }

    private Map<?,?> findAnalysisCandidate(Map<String,Object> config, String category, String id) {
        Object raw = config.get(category);
        if (!(raw instanceof List)) return null;
        for (Object candidate : (List<?>) raw) if (candidate instanceof Map && Objects.equals(id, String.valueOf(((Map<?,?>) candidate).get("id")))) return (Map<?,?>) candidate;
        return null;
    }

    Map<String,Object> applyAnalysisChanges(Map<String,Object> base, List<Map<String,Object>> changes) {
        Map<String,Object> config = readAnalysisConfig(writeJson(base));
        for (Map<String,Object> change : changes) {
            Object raw = config.get(change.get("category"));
            if (!(raw instanceof List)) continue;
            for (Object candidate : (List<?>) raw) if (candidate instanceof Map && Objects.equals(String.valueOf(change.get("id")), String.valueOf(((Map<?,?>) candidate).get("id")))) {
                ((Map<String,Object>) candidate).put(String.valueOf(change.get("field")), change.get("value"));
                break;
            }
        }
        return config;
    }

    private String analysisKnowledgeMarkdown(String answer, Map<String,Object> config) {
        StringBuilder out = new StringBuilder("## 分析说明\n\n").append(answer);
        appendKnowledgeSection(out, "对象与字段", config, "objects", "properties");
        appendKnowledgeSection(out, "关系", config, "relations");
        appendKnowledgeSection(out, "业务逻辑", config, "logics");
        appendKnowledgeSection(out, "业务动作", config, "actions");
        appendKnowledgeSection(out, "服务流", config, "serviceFlows");
        appendKnowledgeSection(out, "权限候选", config, "permissionCandidates");
        return out.toString();
    }

    private void appendKnowledgeSection(StringBuilder out, String title, Map<String,Object> config, String... categories) {
        List<?> objects = config.get("objects") instanceof List ? (List<?>) config.get("objects") : Collections.emptyList();
        boolean wroteHeader = false;
        for (String category : categories) {
            Object raw = config.get(category);
            if (!(raw instanceof List) || ((List<?>) raw).isEmpty()) continue;
            if (!wroteHeader) { out.append("\n\n## ").append(title).append("\n"); wroteHeader = true; }
            for (Object item : (List<?>) raw) {
                if (!(item instanceof Map)) continue;
                Map<?,?> row = (Map<?,?>) item;
                Object nameValue = row.get("name");
                if (nameValue == null) nameValue = row.get("table");
                if (nameValue == null) nameValue = row.get("column");
                String name = nameValue == null ? "未命名" : String.valueOf(nameValue);
                out.append("- **").append(name).append("**");
                if (row.get("table") != null) out.append("（表：").append(row.get("table")).append("）");
                if (row.get("column") != null) out.append("（字段：").append(row.get("column")).append("）");
                if (row.get("dataType") != null) out.append("；类型：").append(row.get("dataType"));
                if (row.get("nullable") != null) out.append("；可空：").append(row.get("nullable"));
                if (row.get("isPrimaryKey") != null) out.append("；主键：").append(row.get("isPrimaryKey"));
                if (row.get("sourceObjectId") != null) out.append("；源对象：").append(row.get("sourceObjectId"));
                if (row.get("targetObjectId") != null) out.append("；目标对象：").append(row.get("targetObjectId"));
                if (row.get("sourceColumn") != null) out.append("；源字段：").append(row.get("sourceColumn"));
                if (row.get("targetColumn") != null) out.append("；目标字段：").append(row.get("targetColumn"));
                if (row.get("cardinality") != null) out.append("；关系基数：").append(row.get("cardinality"));
                if (row.get("objectId") != null) {
                    Object owner = row.get("objectId");
                    for (Object object : objects) if (object instanceof Map && Objects.equals(String.valueOf(row.get("objectId")), String.valueOf(((Map<?,?>) object).get("id")))) {
                        if (((Map<?,?>) object).get("name") != null) owner = ((Map<?,?>) object).get("name");
                        break;
                    }
                    out.append("；所属对象：").append(owner);
                }
                if (row.get("description") != null) out.append("；说明：").append(row.get("description"));
                if (row.get("conditionText") != null) out.append("；规则：").append(row.get("conditionText"));
                if (row.get("risk") != null) out.append("；风险：").append(row.get("risk"));
                if (row.get("operation") != null) out.append("；操作：").append(row.get("operation"));
                if (row.get("trigger") != null) out.append("；触发：").append(row.get("trigger"));
                if (row.get("subjectType") != null) out.append("；主体类型：").append(row.get("subjectType"));
                if (row.get("rowFilterRequired") != null) out.append("；需要行级过滤：").append(row.get("rowFilterRequired"));
                if (row.get("steps") != null) out.append("；步骤：").append(row.get("steps"));
                out.append('\n');
            }
        }
    }

    private void appendContext(StringBuilder context, String label, Object value) {
        if (value != null && !String.valueOf(value).trim().isEmpty()) context.append('\n').append(label).append("：").append(clip(String.valueOf(value), "配置候选 JSON".equals(label) ? 30000 : 5000));
    }

    private String conversationText(List<Map<String, Object>> history) {
        if (history == null || history.isEmpty()) return "";
        StringBuilder result = new StringBuilder();
        int start = Math.max(0, history.size() - 4);
        for (int i = start; i < history.size(); i++) {
            Map<String, Object> item = history.get(i);
            if (item == null) continue;
            String role = String.valueOf(item.get("role"));
            if (!("user".equals(role) || "assistant".equals(role))) continue;
            String content = String.valueOf(item.get("content"));
            if (content.trim().isEmpty()) continue;
            String line = ("user".equals(role) ? "用户：" : "助手：") + clip(content, 500);
            if (result.length() + line.length() > 2400) break;
            result.append(line).append('\n');
        }
        return result.toString().trim();
    }

    /** 把单份原始资产整理成一张可持续维护的 Wiki 页面。 */
    public Map<String,Object> ingestAsset(long assetId) {
        requireEnabled();
        Map<String,Object> source = jdbc.queryForMap("select asset_id,space_id,asset_name,asset_type,domain_name,description,content from wiki_asset where asset_id=? and status='0'", assetId);
        String prompt = "请把输入的业务资料整理成一张持久化 Wiki 页面。保留可核验事实、术语、规则及来源，不推测缺失信息。只返回 JSON：{\"title\":\"...\",\"summary\":\"...\",\"content\":\"Markdown...\"}。\n资料标题：" + source.get("asset_name") + "\n资料类型：" + source.get("asset_type") + "\n业务域：" + source.get("domain_name") + "\n说明：" + source.get("description") + "\n正文：\n" + clip(String.valueOf(source.get("content")), 30000);
        JsonNode page = parse(openAiText(prompt));
        if (page == null || !page.path("content").isTextual() || page.path("content").asText().trim().isEmpty()) throw new IllegalStateException("模型未返回有效 Wiki 页面 JSON");
        String title = clip(page.path("title").asText(String.valueOf(source.get("asset_name"))), 200);
        String content = clip(page.path("content").asText(), 1000000);
        String tag = "SOURCE:" + assetId;
        List<Map<String,Object>> existing = jdbc.queryForList("select asset_id from wiki_asset where asset_type='WIKI_PAGE' and tags=? limit 1", tag);
        if (existing.isEmpty()) jdbc.update("insert into wiki_asset(space_id,asset_name,asset_type,domain_name,description,content,tags,status,create_by,create_time,update_by,update_time) values(?,?,'WIKI_PAGE',?,?,?,?,'0','system',now(),'system',now())", source.get("space_id"), title, source.get("domain_name"), clip(page.path("summary").asText("来源资料摘要"),1000), content, tag);
        else jdbc.update("update wiki_asset set space_id=?,asset_name=?,domain_name=?,description=?,content=?,update_by='system',update_time=now() where asset_id=?", source.get("space_id"), title, source.get("domain_name"), clip(page.path("summary").asText("来源资料摘要"),1000), content, existing.get(0).get("asset_id"));
        refreshIndex();
        Map<String,Object> result = new LinkedHashMap<>(); result.put("title", title); result.put("sourceId", assetId); result.put("updated", !existing.isEmpty()); return result;
    }

    public Map<String, Object> health() {
        Map<String,Object> result = new LinkedHashMap<>(); result.put("enabled", enabled()); result.put("provider", "internal");
        if (!enabled()) { result.put("connected", false); result.put("message", "请配置 LLM_API_BASE_URL 和 LLM_API_KEY"); return result; }
        try { result.put("connected", true); result.put("response", parse(openAiExchange("/models", HttpMethod.GET, null))); }
        catch (Exception e) { result.put("connected", false); result.put("message", "模型 API 不可用"); }
        return result;
    }

    /** URL 作为待整理来源记录；服务端不主动抓取任意地址，避免内网 SSRF。 */
    public Object addUrlSource(String url) {
        try {
            URI source = URI.create(url == null ? "" : url.trim());
            if (!("http".equalsIgnoreCase(source.getScheme()) || "https".equalsIgnoreCase(source.getScheme())) || source.getHost() == null || source.getUserInfo() != null) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) { throw new IllegalArgumentException("来源地址必须是有效的 http 或 https URL，且不能包含账号凭证"); }
        String value = url.trim(); jdbc.update("insert into wiki_asset(asset_name,asset_type,description,content,status,create_by,create_time,update_by,update_time) values(?,?,?,?, '0','system',now(),'system',now())", value, "URL", "URL 待整理来源", value);
        return Collections.singletonMap("saved", true);
    }

    public Object listPages() { return jdbc.queryForList("select asset_id id,asset_name slug,asset_name title,domain_name,description,content,update_time from wiki_asset where asset_type='WIKI_PAGE' and coalesce(tags,'')<>'SYSTEM_INDEX' and status='0' order by update_time desc,asset_id desc"); }
    public Object getPage(String slug) { List<Map<String,Object>> rows=jdbc.queryForList("select asset_id id,asset_name slug,asset_name title,domain_name,description,content,update_time from wiki_asset where asset_type='WIKI_PAGE' and asset_name=? and status='0' limit 1", segment(slug)); if(rows.isEmpty()) throw new IllegalArgumentException("Wiki 页面不存在"); return rows.get(0); }
    public Object createPage(Map<String,String> body) { return createPage(body, null); }
    public Object createPage(Map<String,String> body, Long spaceId) { if(body==null||text(body.get("slug"))==null) throw new IllegalArgumentException("Wiki 页面标题不能为空"); String slug=clip(segment(body.get("slug")),200); jdbc.update("insert into wiki_asset(space_id,asset_name,asset_type,description,content,status,create_by,create_time,update_by,update_time) values(?,?,'WIKI_PAGE','人工维护页面',?,'0','system',now(),'system',now())",spaceId,slug,clip(body.get("content"),1000000)); refreshIndex(); return getPage(slug); }
    public Object updatePage(String slug,Map<String,String> body) { String key=segment(slug); int n=jdbc.update("update wiki_asset set content=?,update_by='system',update_time=now() where asset_type='WIKI_PAGE' and asset_name=? and status='0'",clip(body==null?null:body.get("content"),1000000),key); if(n==0) throw new IllegalArgumentException("Wiki 页面不存在"); refreshIndex(); return getPage(key); }
    public Object deletePage(String slug) { int n=jdbc.update("update wiki_asset set status='1',update_by='system',update_time=now() where asset_type='WIKI_PAGE' and asset_name=?",segment(slug)); refreshIndex(); return n>0; }
    public Object categoryPages(String category) { return jdbc.queryForList("select asset_id id,asset_name slug,domain_name,description,update_time from wiki_asset where asset_type='WIKI_PAGE' and status='0' and domain_name=? order by update_time desc",category); }
    public Object fileTree() { return listPages(); }
    public Object fileContent(String path) { return getPage(path.replaceFirst("^wiki/","").replaceFirst("\\.md$","")); }
    public Object index() { List<Map<String,Object>> rows=jdbc.queryForList("select content from wiki_asset where asset_type='WIKI_PAGE' and tags='SYSTEM_INDEX' and status='0' limit 1"); return rows.isEmpty()?Collections.singletonMap("content","# Wiki Index\\n暂无已整理页面"):rows.get(0); }
    public Object log() { return jdbc.queryForList("select role,content,create_time from wiki_chat_message order by message_id desc limit 100"); }

    void refreshIndex() {
        List<Map<String,Object>> pages=jdbc.queryForList("select asset_name,domain_name,description,update_time from wiki_asset where asset_type='WIKI_PAGE' and coalesce(tags,'')<>'SYSTEM_INDEX' and status='0' order by asset_name limit 500");
        StringBuilder md=new StringBuilder("# Wiki Index\n\n"); for(Map<String,Object> p:pages) md.append("- ").append(p.get("asset_name")).append(" | ").append(p.get("domain_name")).append(" | ").append(p.get("description")).append("\n");
        List<Map<String,Object>> index=jdbc.queryForList("select asset_id from wiki_asset where asset_type='WIKI_PAGE' and tags='SYSTEM_INDEX' limit 1");
        if(index.isEmpty()) jdbc.update("insert into wiki_asset(asset_name,asset_type,description,content,tags,status,create_by,create_time,update_by,update_time) values('index','WIKI_PAGE','LLM Wiki 页面索引',?,'SYSTEM_INDEX','0','system',now(),'system',now())",md.toString());
        else jdbc.update("update wiki_asset set content=?,update_by='system',update_time=now() where asset_id=?",md.toString(),index.get(0).get("asset_id"));
    }

    private String openAiText(String prompt) {
        Map<String,Object> body=new LinkedHashMap<>(); body.put("model",properties.getLlmWiki().getModel()); body.put("temperature",0.1);
        Map<String,Object> message=new LinkedHashMap<>(); message.put("role","user"); message.put("content",prompt); body.put("messages",Collections.singletonList(message));
        JsonNode root=parse(openAiExchange("/chat/completions",HttpMethod.POST,body)); JsonNode content=root==null?null:root.path("choices").path(0).path("message").path("content"); return content==null||content.isMissingNode()?"":content.asText();
    }
    private String openAiExchange(String path,HttpMethod method,Object body) { OntologyIntegrationProperties.LlmWiki cfg=properties.getLlmWiki(); HttpHeaders headers=new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON); headers.setBearerAuth(cfg.getApiKey().trim()); ResponseEntity<String> response=rest.exchange(URI.create(join(cfg.getApiBaseUrl(),path)),method,new HttpEntity<>(body,headers),String.class); if(!response.getStatusCode().is2xxSuccessful()) throw new IllegalStateException("模型 API 返回 HTTP "+response.getStatusCodeValue()); return response.getBody()==null?"":response.getBody(); }
    private JsonNode parse(String raw) { try { if(raw==null||raw.trim().isEmpty())return null; String s=raw.trim().replaceFirst("(?s)^```(?:json)?\\s*","").replaceFirst("\\s*```$",""); int a=s.indexOf('{'),b=s.lastIndexOf('}'); if(a>=0&&b>a)s=s.substring(a,b+1); return json.readTree(s); } catch(Exception e) { return null; } }
    private void requireEnabled() { if(!enabled()) throw new IllegalStateException("内置 LLM Wiki 未配置模型 API"); }
    private String join(String base,String path) { String a=base==null?"":base.trim(),b=path==null?"":path.trim(); return a.endsWith("/")&&b.startsWith("/")?a.substring(0,a.length()-1)+b:!a.endsWith("/")&&!b.startsWith("/")?a+"/"+b:a+b; }
    private String segment(String value) { String v=text(value); if(v==null||v.indexOf('/')>=0||v.indexOf('\\')>=0||".".equals(v)||"..".equals(v)) throw new IllegalArgumentException("Wiki 页面标识无效"); return v; }
    private String text(String value) { return value==null||value.trim().isEmpty()?null:value.trim(); }
    private String clip(String value,int max) { return value==null?"":value.substring(0,Math.min(value.length(),max)); }
}

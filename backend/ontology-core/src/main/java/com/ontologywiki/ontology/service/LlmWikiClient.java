package com.ontologywiki.ontology.service;

import com.ontologywiki.ontology.repository.RowIds;
import cn.hutool.core.collection.CollUtil;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ontologywiki.ontology.config.OntologyIntegrationProperties;
import com.ontologywiki.ontology.domain.WikiAssetType;
import com.ontologywiki.ontology.mapper.LlmSettingsMapper;
import com.ontologywiki.ontology.mapper.LlmWikiAnalysisMapper;
import com.ontologywiki.ontology.mapper.WikiAssetMapper;
import com.ontologywiki.ontology.service.wiki.WikiIndexService;
import com.ontologywiki.ontology.service.llm.ModelCompletionGateway;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import javax.annotation.PostConstruct;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

/**
 * Java 内置 LLM Wiki。
 *
 * <p>原始资料与增量生成页面均持久化在本地 MySQL：
 * <ul>
 *   <li>启动期结构迁移（DDL）保留在本类，因为属于应用引导逻辑；</li>
 *   <li>业务读写全部下沉至 {@link LlmWikiAnalysisMapper} 与 {@link WikiAssetMapper}；</li>
 *   <li>本类只负责提示词构建、结果解析、检索排序与路由决策。</li>
 * </ul>
 */
@Service
public class LlmWikiClient {
    /** 资产启用状态 */
    private static final String STATUS_ACTIVE = "0";

    /** 系统索引页固定标签 */
    private static final String SYSTEM_INDEX_TAG = "SYSTEM_INDEX";

    /** 已确认知识条目类型 */
    private static final String MEMORY_TYPE_KNOWLEDGE_ITEM = "KNOWLEDGE_ITEM";

    /** 分析说明记忆类型 */
    private static final String MEMORY_TYPE_ANALYSIS_ANSWER = "ANALYSIS_ANSWER";

    /** 已确认状态 */
    private static final String MEMORY_STATUS_CONFIRMED = "CONFIRMED";

    private static final int EVIDENCE_CANDIDATES = 12;

    /** 已确认知识：结构化条目与分析说明的最大取数 */
    private static final int KNOWLEDGE_ITEM_LIMIT = 200;
    private static final int ANALYSIS_ANSWER_LIMIT = 5;

    /** 已确认知识：无查询条件时默认注入的条目数 */
    private static final int DEFAULT_KNOWLEDGE_ITEMS = 15;

    private final OntologyIntegrationProperties properties;
    private final ModelCompletionGateway modelGateway;
    private final CredentialService credentials;
    private final LlmWikiAnalysisMapper analysisMapper;
    private final WikiAssetMapper assetMapper;
    private final LlmSettingsMapper settingsMapper;
    private final WikiIndexService indexService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ontologywiki.ontology.service.wiki.WikiRelationService relationService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ontologywiki.ontology.service.wiki.WikiChunkIndexService chunkIndexService;
    private final ObjectMapper json = new ObjectMapper();
    @org.springframework.beans.factory.annotation.Autowired
    private com.ontologywiki.ontology.service.analysis.ProposalChangeService proposalChanges = new com.ontologywiki.ontology.service.analysis.ProposalChangeService();
    @org.springframework.beans.factory.annotation.Autowired
    private com.ontologywiki.ontology.service.analysis.ProposalRevisionService proposalRevisions;
    private final AnalysisCandidateResolver candidateResolver = new AnalysisCandidateResolver();
    private final WikiLexicalRetriever wikiRetriever = new WikiLexicalRetriever();

    public LlmWikiClient(OntologyIntegrationProperties properties, RestTemplate rest,
                         CredentialService credentials, LlmWikiAnalysisMapper analysisMapper,
                         WikiAssetMapper assetMapper, LlmSettingsMapper settingsMapper,
                         WikiIndexService indexService) {
        this.properties = properties;
        this.modelGateway = new ModelCompletionGateway(rest, json);
        this.credentials = credentials;
        this.analysisMapper = analysisMapper;
        this.assetMapper = assetMapper;
        this.settingsMapper = settingsMapper;
        this.indexService = indexService;
    }

    /**
     * 启动时把数据库中的启用配置载入内存，仅作为「数据库不可用时的兜底」。
     *
     * <p>模型路由以 usage_scene 为准（见 {@link #configForScene}），内存配置不再承担默认模型职责，
     * 避免旧的 setting_id=1 单配置与多配置并存时出现「配置了却不生效」。
     *
     * <p>注意：建表、加列、数据修正、菜单初始化等结构变更全部由
     * {@code backend/sql/ontology-wiki.sql} 负责，应用启动不再执行任何 DDL。
     */
    @PostConstruct
    public void loadSettingsOnStartup() {
        loadStoredSettings();
    }

    /**
     * 启动时把数据库中的启用配置载入内存，仅作为「数据库不可用时的兜底」。
     *
     * <p>模型路由以 usage_scene 为准（见 {@link #configForScene}），
     * 避免旧的 setting_id=1 单配置与多配置并存时出现「配置了却不生效」。
     */
    private void loadStoredSettings() {
        List<Map<String,Object>> rows = settingsMapper.selectLatestEnabled();
        if (rows.isEmpty()) return;
        Map<String,Object> row = rows.get(0);
        OntologyIntegrationProperties.LlmWiki cfg = properties.getLlmWiki();
        cfg.setEnabled(true);
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
        List<Map<String,Object>> existing = settingsMapper.selectBySettingId(1);
        if (existing.isEmpty()) {
            settingsMapper.insertSingleSetting(1, enabled, baseUrl,
                    effectiveKey.trim().isEmpty() ? null : credentials.encrypt(effectiveKey), model, username);
        } else if (clearKey) {
            settingsMapper.updateSingleSettingClearKey(1, enabled, baseUrl, model, username);
        } else if (!apiKey.trim().isEmpty()) {
            settingsMapper.updateSingleSetting(1, enabled, baseUrl, credentials.encrypt(effectiveKey), model, username);
        } else {
            settingsMapper.updateSingleSettingKeepKey(1, enabled, baseUrl, model, username);
        }
        cfg.setEnabled(enabled); cfg.setApiBaseUrl(baseUrl); cfg.setModel(model); cfg.setApiKey(effectiveKey);
        return modelSettings();
    }

    public Map<String,Object> testModelSettings() {
        Map<String,Object> result = new LinkedHashMap<>();
        if (!enabled()) throw new IllegalStateException("请先保存并启用模型配置");
        Map<String,Object> selected = configForScene("ALL");
        String answer = modelGateway.complete("请仅回复：连接成功", "ALL", selected);
        if (text(answer) == null) throw new IllegalStateException("模型 API 未返回有效内容");
        result.put("connected", true); result.put("model", selected.get("model")); result.put("message", "模型 API 调用成功");
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
        Map<String, Object> task = analysisMapper.selectTaskInfo(taskId);
        // 未指定草案时取最新版本，指定时必须属于当前任务
        List<Map<String, Object>> proposals = requestedProposalId == null
                ? analysisMapper.selectLatestProposal(taskId)
                : analysisMapper.selectProposal(taskId, requestedProposalId);
        if (requestedProposalId != null && proposals.isEmpty()) throw new IllegalArgumentException("配置草案不存在或不属于当前分析任务");
        Long proposalId = proposals.isEmpty() ? null : RowIds.first(proposals, "proposal_id");
        Integer maxVersion = analysisMapper.selectMaxVersion(taskId);
        int version = (maxVersion == null ? 0 : maxVersion) + 1;
        String status = confirm ? MEMORY_STATUS_CONFIRMED : "DRAFT";
        String sourceJson = "{}";
        Map<String,Object> savedConfig = Collections.emptyMap();
        try {
            Map<String, Object> source = new LinkedHashMap<>(); source.put("taskId", taskId); source.put("proposalId", proposalId); source.put("kind", MEMORY_TYPE_ANALYSIS_ANSWER); sourceJson = json.writeValueAsString(source);
            if (!proposals.isEmpty()) {
                Map<String, Object> config = json.readValue(String.valueOf(proposals.get(0).get("config_json")), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
                config.put("llmAnswer", answer.trim()); config.put("answerStatus", status); config.put("answerVersion", version);
                savedConfig = config;
                if (config.get("citedSources") != null) { source.put("citedSources", config.get("citedSources")); sourceJson = json.writeValueAsString(source); }
                analysisMapper.updateProposalConfig(proposalId, json.writeValueAsString(config), username);
            }
        } catch (Exception e) { throw new IllegalStateException("分析答案保存失败"); }
        String memoryContent = confirm ? analysisKnowledgeMarkdown(answer.trim(), savedConfig) : answer.trim();
        analysisMapper.insertAnswerMemory(task.get("wiki_space_id"), taskId, proposalId,
                String.valueOf(task.get("task_name")) + " · 分析答案 v" + version,
                memoryContent, sourceJson, status, version, username, confirm);
        analysisMapper.updateTaskSummary(taskId, clip(answer.trim(), 60000), username);
        List<Map<String, Object>> latest = analysisMapper.selectLatestMemory(taskId);
        if (confirm && !latest.isEmpty()) {
            Map<String,Object> memory = latest.get(0);
            String tag = "ANALYSIS_MEMORY:" + memory.get("memory_id");
            String title = "分析知识 · " + task.get("task_name") + " · v" + version;
            String content = "# " + title + "\n\n" + memoryContent + "\n\n> 来源：分析任务 #" + taskId + "，配置草案 #" + (proposalId == null ? "—" : proposalId) + "。该页面由分析者确认后沉淀，可在后续分析中作为已确认知识参考。";
            List<Map<String,Object>> pages = assetMapper.selectPageByTag(tag);
            boolean updatingPage = !pages.isEmpty();
            Long previousSpaceId = null;
            if (updatingPage) {
                long pageAssetId = RowIds.first(pages, "asset_id");
                previousSpaceId = pageSpaceId(pageAssetId);
                assetMapper.updateKnowledgePage(task.get("wiki_space_id"), title, "已确认分析知识", content, username, pageAssetId);
            } else {
                assetMapper.insertKnowledgePage(task.get("wiki_space_id"), title, "已确认分析知识", content, tag, username);
            }
            pages = assetMapper.selectPageByTag(tag);
            if (!pages.isEmpty()) {
                long pageAssetId = RowIds.first(pages, "asset_id");
                refreshChunkIndex(pageAssetId);
                refreshPageRelations(pageAssetId, updatingPage, previousSpaceId);
            }
            refreshIndex();
            // 拆出结构化知识条目，提升下次分析的召回精度
            if (!savedConfig.isEmpty()) {
                saveKnowledgeItems(taskId, task.get("wiki_space_id"), proposalId, savedConfig, version, username);
            }
        }
        return latest.isEmpty() ? Collections.<String, Object>emptyMap() : latest.get(0);
    }

    /** 保存问答确认后的结构修正，并在同一事务中沉淀结构与说明。 */
    @Transactional
    public Map<String,Object> saveAnalysisResult(long taskId, Long proposalId, String answer, List<?> requestedChanges, String username) {
        if (answer == null || answer.trim().isEmpty()) throw new IllegalArgumentException("请先完成分析说明");
        if (proposalId == null) throw new IllegalArgumentException("当前分析没有关联配置草案，无法保存结构");
        Map<String,Object> revised = proposalRevisions.save(taskId, proposalId, requestedChanges, username);
        Long savedProposalId = revised.get("proposalId") instanceof Number ? ((Number) revised.get("proposalId")).longValue() : proposalId;
        Map<String,Object> result = saveAnalysisAnswer(taskId, savedProposalId, answer, true, username);
        result.put("proposalId", savedProposalId);
        return result;
    }

    public List<Map<String, Object>> listAnalysisMemory(long taskId) {
        return analysisMapper.selectMemoriesByTask(taskId);
    }

    public Map<String, Object> latestAnalysisMemory(long taskId) {
        List<Map<String, Object>> rows = analysisMapper.selectLatestMemory(taskId);
        return rows.isEmpty() ? Collections.<String, Object>emptyMap() : rows.get(0);
    }

    /** 在当前分析范围内使用 Wiki、草案和已确认知识优化答案，返回未保存的候选版本。 */
    public Map<String, Object> refineAnalysisAnswer(long taskId, String currentAnswer, String question) {
        if (!enabled()) throw new IllegalStateException("内置 LLM Wiki 未配置模型 API");
        if (question == null || question.trim().isEmpty()) throw new IllegalArgumentException("请输入希望优化的内容");
        Map<String, Object> task = analysisMapper.selectTaskSpace(taskId);
        Long spaceId = task.get("wiki_space_id") == null ? null : ((Number) task.get("wiki_space_id")).longValue();
        String prompt = "当前分析答案：\n" + clip(currentAnswer == null ? "" : currentAnswer, 30000) + "\n\n分析者希望优化：\n" + clip(question.trim(), 8000) + "\n\n请结合当前分析任务的 Wiki 资料、配置候选和已确认分析知识，输出一份可以直接替换当前答案的完整中文版本。保留可验证事实，明确标记仍需确认的内容，不要输出 JSON，不要添加与任务无关的内容。";
        Map<String, Object> result = queryResult(prompt, false, spaceId, Collections.<Map<String, Object>>emptyList(), "ANALYSIS", taskId);
        result.put("draft", true); return result;
    }

    /** 给后续分析和分析问答提供空间级已确认知识。 */
    public String confirmedKnowledgeContext(Long taskId, Long spaceId) {
        return confirmedKnowledgeContext(taskId, spaceId, null);
    }

    /** 给后续分析和分析问答提供空间级已确认知识，按 query 对结构化条目做相关性排序。 */
    public String confirmedKnowledgeContext(Long taskId, Long spaceId, String query) {
        if (taskId == null && spaceId == null) return "";
        // 1. 取结构化知识条目（最多 200 条）
        List<Map<String,Object>> itemRows = queryConfirmedMemory(taskId, spaceId, "KNOWLEDGE_ITEM", 200);
        // 2. 取分析说明摘要（最多 5 条）
        List<Map<String,Object>> answerRows = queryConfirmedMemory(taskId, spaceId, "ANALYSIS_ANSWER", 5);
        // 3. 按 query 排序 KNOWLEDGE_ITEM；无 query 取最新 15 条
        List<Map<String,Object>> relevantItems;
        if (query != null && !query.trim().isEmpty() && !itemRows.isEmpty()) {
            List<Map<String,Object>> adapted = new ArrayList<>();
            for (Map<String,Object> row : itemRows) {
                Map<String,Object> a = new LinkedHashMap<>();
                a.put("asset_name", row.get("title"));
                a.put("description", row.get("keywords") != null && !String.valueOf(row.get("keywords")).trim().isEmpty()
                        ? row.get("keywords") : row.get("title"));
                a.put("domain_name", "");
                a.put("_ref", row);
                adapted.add(a);
            }
            List<Map<String,Object>> ranked = wikiRetriever.rank(adapted, query, 15);
            relevantItems = new ArrayList<>();
            for (Map<String,Object> a : ranked) {
                @SuppressWarnings("unchecked") Map<String,Object> orig = (Map<String,Object>) a.get("_ref");
                if (orig != null) relevantItems.add(orig);
            }
        } else {
            relevantItems = itemRows.size() > 15 ? itemRows.subList(0, 15) : itemRows;
        }
        // 4. 拼装上下文
        StringBuilder out = new StringBuilder();
        if (!relevantItems.isEmpty()) {
            out.append("【结构化知识条目】\n");
            for (Map<String,Object> row : relevantItems) {
                out.append("- ").append(row.get("title")).append("：")
                   .append(clip(String.valueOf(row.get("content")), 200)).append("\n");
            }
        }
        if (!answerRows.isEmpty()) {
            out.append("\n【分析说明摘要】\n");
            for (Map<String,Object> row : answerRows) {
                out.append("\n- ").append(row.get("title")).append("（任务 #").append(row.get("task_id"))
                   .append("，版本 ").append(row.get("version")).append("）\n  ")
                   .append(clip(String.valueOf(row.get("content")), 1500));
            }
        }
        return out.toString().trim();
    }

    private List<Map<String,Object>> queryConfirmedMemory(Long taskId, Long spaceId, String type, int limit) {
        if (taskId != null && spaceId != null) {
            return analysisMapper.selectConfirmedItemsByTaskAndSpace(MEMORY_STATUS_CONFIRMED, type, taskId, spaceId, limit);
        } else if (taskId != null) {
            return analysisMapper.selectConfirmedItemsByTask(MEMORY_STATUS_CONFIRMED, type, taskId, limit);
        }
        return analysisMapper.selectConfirmedItemsBySpace(MEMORY_STATUS_CONFIRMED, type, spaceId, limit);
    }

    /**
     * 是否存在可用的模型配置。
     *
     * <p>以数据库中的启用配置为准：只要有任一启用的配置能解析出地址和密钥即视为可用，
     * 不再依赖旧的内存单配置，避免「大模型管理里已启用、系统却判定未启用」。
     */
    public boolean enabled() {
        List<Map<String,Object>> rows = analysisMapper.selectEnabledSettings(1);
        for (Map<String,Object> row : rows) {
            if (value(row.get("api_base_url")).trim().isEmpty()) continue;
            // 本地协议可选鉴权。远程 OpenAI 兼容服务需要 API Key。
            String provider = value(row.get("provider_type"));
            if ("OLLAMA".equalsIgnoreCase(provider) || "LOCAL_COMPAT".equalsIgnoreCase(provider)) return true;
            if (!value(row.get("api_key_enc")).trim().isEmpty()) return true;
        }
        return false;
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
        boolean assetScope = "ASSET".equals(scopeType);
        String conversation = conversationText(history);
        List<Map<String,Object>> citedSources = Collections.emptyList();
        if (!analysisScope) {
            List<String> terms = wikiRetriever.searchTerms(clip(question, 2000));
            // For short follow-up questions, include the most recent user question as context.
            if (question.length() <= 40 && history != null
                    && question.matches(".*(它|上述|前面|刚才|这[个些项条]|继续|举例|详细说|为什么呢).*")) {
                for (int i = history.size() - 1; i >= 0; i--) {
                    Map<String,Object> previous = history.get(i);
                    if (previous != null && "user".equals(previous.get("role"))) {
                        terms = wikiRetriever.searchTerms(question + "\n" + clip(value(previous.get("content")), 240));
                        break;
                    }
                }
            }
            if (!terms.isEmpty()) {
                if (chunkIndexService != null) {
                    citedSources = chunkIndexService.search(terms, assetScope, scopeId, spaceId, 3);
                }
                if (citedSources.isEmpty()) {
                    List<Map<String,Object>> candidates = assetMapper.selectEvidenceCandidates(
                            terms, assetScope, scopeId, spaceId, EVIDENCE_CANDIDATES);
                    citedSources = wikiRetriever.evidence(candidates, terms, 3);
                }
            }
        }
        Map<String,Object> analysisState = analysisScope ? analysisTaskState(scopeId, proposalId, previousChanges, question, contextState, allowDeterministicChange) : Collections.<String,Object>emptyMap();
        String taskContext = String.valueOf(analysisState.getOrDefault("context", ""));
        String context = citedSources.isEmpty() ? "暂无匹配的 Wiki 资料。" : citedSources.stream()
                .map(hit -> "[" + hit.get("assetType") + "] " + hit.get("title") + " · 第"
                        + hit.get("paragraph") + "段\n" + hit.get("excerpt"))
                .collect(Collectors.joining("\n\n"));
        String prompt = analysisScope
                ? "你是本体分析结果助手。必须只依据当前提供的完整分析配置回答，不得编造配置外的信息。回答任何关于对象、字段、关系、逻辑、动作、服务流、权限、指标、维度和术语的问题。用户明确要求新增、修改或删除时，先在 answer 说明变更，changes 返回待确认差异；没有明确变更意图时 changes 必须为空。严格只返回 JSON：{\"answer\":\"面向用户的中文说明\",\"changes\":[{\"op\":\"UPDATE\",\"category\":\"properties\",\"id\":\"候选ID\",\"field\":\"dataType\",\"value\":\"TEXT\",\"reason\":\"修改原因\"}]}。op 只能是 UPDATE、ADD、DELETE：UPDATE 修改已有项的白名单字段；ADD 的 value 必须是完整的新对象，必须包含稳定 id 和该分类要求的字段；DELETE 只能删除用户明确指定的已有 id。不得执行 SQL，不得返回配置草案 JSON 或 Wiki Markdown；目标不唯一、字段不足或新增信息不完整时先追问，changes 返回空数组。\n\n当前分析任务与完整配置：\n" + clip(taskContext, 18000)
                : "请根据以下本体 Wiki 页面、来源资料和分析任务上下文回答问题；业务问题没有资料证据时，明确说明当前资料中未找到依据，不要猜测。若用户是在问候或闲聊，请自然、简短地回应，不要求 Wiki 资料。分析任务中的候选配置尚待人工审核。中文作答，并结合对话上下文回答本轮问题。\n\n资料：\n" + clip(context, 4200) + (taskContext.isEmpty() ? "" : "\n\n关联分析任务与候选内容：\n" + clip(taskContext, 1500));
        prompt += (conversation.isEmpty() ? "" : "\n\n此前对话：\n" + clip(conversation, 1800)) + "\n\n本轮问题：\n" + clipQuestion(question, 1800);
        String rawAnswer = openAiText(prompt, analysisScope ? "ANALYSIS" : "FAST");
        JsonNode analysisReply = analysisScope ? parse(rawAnswer) : null;
        String answer = analysisScope && analysisReply != null && analysisReply.path("answer").isTextual()
                ? analysisReply.path("answer").asText() : rawAnswer;
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
            List<Map<String,Object>> storedChanges = proposalChanges.recordedChanges(base);
            List<Map<String,Object>> changes = mergeAnalysisChangeMarkers(base, storedChanges, activeChanges);
            result.put("analysisChanges", changes);
            result.put("analysisPendingChanges", activeChanges);
            List<Map<String,Object>> newChanges = new ArrayList<>(deterministicChanges); newChanges.addAll(modelChanges);
            result.put("analysisUpdatedChanges", normalizeAnalysisChanges(base, newChanges, false));
            Map<String,Object> working = applyAnalysisChanges(base, activeChanges); working.put("analysisCorrections", changes);
            result.put("analysisConfig", working);
            if (allowDeterministicChange) result.put("contextState", analysisState.get("contextState"));
        }
        result.put("citedPages", citedSources.stream().map(r -> r.get("title")).collect(Collectors.toList()));
        result.put("citedSources", citedSources);
        result.put("citationIds", citedSources.stream().map(r -> r.get("assetId")).collect(Collectors.toList()));
        if (saveAsPage && !analysisScope && !citedSources.isEmpty()) {
            String title = "问答 " + LocalDateTime.now().toString().replace(':', '-');
            Map<String,String> page = new LinkedHashMap<>(); page.put("slug", title); page.put("content", "# " + title + "\n\n" + result.get("answer")); createPage(page, spaceId);
            result.put("savedToWiki", true); result.put("pageTitle", title);
        }
        return result;
    }

    private Map<String,Object> analysisTaskState(Long taskId, Long proposalId, List<?> previousChanges, String question, Map<String, Object> savedContextState, boolean allowDeterministicChange) {
        if (taskId == null) return Collections.emptyMap();
        Map<String,Object> task = proposalId == null
                ? analysisMapper.selectTaskAnalysisContext(taskId)
                : analysisMapper.selectTaskAnalysisContextByProposal(taskId, proposalId);
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
        try { context.append("\n完整分析配置 JSON：").append(clip(writeJson(workingConfig), 24000)); }
        catch (Exception ignored) { /* 配置已由候选文本提供，序列化失败时仍可回答 */ }
        state.put("context", clip(context.toString(), 24000)); state.put("baseConfig", baseConfig); state.put("config", workingConfig);
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
                .append("，权限=").append(size(config.get("permissionCandidates")))
                .append("，指标=").append(candidateRows(config, "metrics").size())
                .append("，维度=").append(candidateRows(config, "dimensions").size()).append('\n');
        Set<String> mentionedObjects = asksForFields(question) && match.matches.isEmpty() ? appendObjectFields(result, config, question) : Collections.<String>emptySet();
        boolean overview = question == null || question.trim().isEmpty() || containsAny(question, "概述", "有哪些", "当前", "结构", "总体", "全部");
        if (!mentionedObjects.isEmpty()) {
            for (Map<String,Object> object : maps(config.get("objects"))) if (mentionedObjects.contains(value(object.get("id")))) {
                result.append("对象：id=").append(value(object.get("id"))).append("；name=").append(value(object.get("name")))
                        .append("；table=").append(value(object.get("table"))).append('\n');
            }
        } else if (!match.matches.isEmpty()) {
            result.append(match.ambiguous ? "字段候选（存在歧义，请先确认）：\n" : "相关字段候选：\n");
            for (AnalysisCandidateResolver.Candidate candidate : match.matches) appendResolvedCandidate(result, candidate);
        } else {
            appendCandidateNodes(result, "对象", config.get("objects"), question, overview, "id", "name", "table", "technicalName", "description");
            appendCandidateNodes(result, "字段", config.get("properties"), question, overview, "id", "name", "column", "technicalName", "dataType", "objectId", "description");
            appendCandidateNodes(result, "关系", config.get("relations"), question, overview, "id", "name", "sourceObjectId", "targetObjectId", "sourceColumn", "targetColumn", "cardinality", "description");
            appendCandidateNodes(result, "业务逻辑", config.get("logics"), question, overview, "id", "name", "conditionText", "risk", "status", "trigger", "description");
            appendCandidateNodes(result, "业务动作", config.get("actions"), question, overview, "id", "name", "operation", "objectId", "risk", "status", "trigger", "description");
            appendCandidateNodes(result, "服务流", config.get("serviceFlows"), question, overview, "id", "name", "steps", "description");
            appendCandidateNodes(result, "权限候选", config.get("permissionCandidates"), question, overview, "id", "actionId", "resourceId", "operation", "subjectType", "suggestedSubjects", "rowFilterRequired", "status");
        }
        appendCandidateNodes(result, "指标候选", candidateRows(config, "metrics"), question, overview, "id", "name", "definition", "aggregation", "propertyId", "synonyms");
        appendCandidateNodes(result, "维度候选", candidateRows(config, "dimensions"), question, overview, "id", "name", "definition", "propertyId", "synonyms");
        appendCandidateNodes(result, "时间维度候选", candidateRows(config, "timeDimensions"), question, overview, "id", "name", "definition", "propertyId", "granularities");
        return clip(result.toString(), 3500);
    }

    /** 用户指定对象并询问字段时，带出该对象的完整字段候选，而非只匹配字段名。 */
    private Set<String> appendObjectFields(StringBuilder result, Map<String,Object> config, String question) {
        Set<String> objectIds = new LinkedHashSet<>();
        if (question == null || question.trim().isEmpty()) return objectIds;
        List<Map<String,Object>> objects = maps(config.get("objects"));
        for (Map<String,Object> object : objects) {
            if (matchesNode(object, question, "id", "name", "table", "technicalName")) {
                String id = value(object.get("id"));
                if (!id.isEmpty()) objectIds.add(id);
            }
        }
        if (objectIds.isEmpty()) return objectIds;
        List<Map<String,Object>> fields = new ArrayList<>();
        for (Map<String,Object> property : maps(config.get("properties"))) {
            if (objectIds.contains(value(property.get("objectId")))) fields.add(property);
        }
        if (fields.isEmpty()) return objectIds;
        // 若问题同时点名了字段，先放该字段，再补齐对象字段，避免长列表裁剪掉精确候选。
        fields.sort((left, right) -> Boolean.compare(
                matchesNode(right, question, "id", "name", "column", "technicalName"),
                matchesNode(left, question, "id", "name", "column", "technicalName")));
        result.append("相关字段候选：指定对象完整字段清单\n");
        int count = Math.min(fields.size(), 40);
        for (int i = 0; i < count; i++) {
            Map<String,Object> field = fields.get(i);
            result.append("- id=").append(value(field.get("id")))
                    .append("；name=").append(value(field.get("name")))
                    .append("；column=").append(value(field.get("column")))
                    .append("；dataType=").append(value(field.get("dataType")))
                    .append("；objectId=").append(value(field.get("objectId")));
            if (!value(field.get("description")).isEmpty()) result.append("；description=").append(clip(value(field.get("description")), 100));
            result.append('\n');
        }
        if (fields.size() > count) result.append("（字段较多，仅列出前").append(count).append("项）\n");
        return objectIds;
    }

    private boolean asksForFields(String question) {
        return question != null && containsAny(question, "字段", "列名", "有哪些列", "包含什么列", "字段清单", "表结构");
    }

    private List<Map<String,Object>> maps(Object value) {
        if (!(value instanceof List)) return Collections.emptyList();
        List<Map<String,Object>> result = new ArrayList<>();
        for (Object row : (List<?>) value) if (row instanceof Map) result.add((Map<String,Object>) row);
        return result;
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

    private Map<String,Object> readAnalysisConfig(Object raw) {
        if (raw == null || String.valueOf(raw).trim().isEmpty()) return new LinkedHashMap<>();
        try { return json.readValue(String.valueOf(raw), new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() { }); }
        catch (Exception failure) { throw new IllegalStateException("分析草案结构无法解析", failure); }
    }

    private String writeJson(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception failure) { throw new IllegalStateException("分析草案结构序列化失败", failure); }
    }

    List<Map<String,Object>> normalizeAnalysisChanges(Map<String,Object> base, List<?> requested, boolean strict) {
        return proposalChanges.normalizeAnalysisChanges(base, requested, strict);
    }

    private List<Map<String,Object>> normalizeAnalysisChanges(Map<String,Object> base, List<?> requested, boolean strict, boolean removeUnchanged) {
        return proposalChanges.normalizeAnalysisChanges(base, requested, strict, removeUnchanged);
    }

    Map<String,Object> applyAnalysisChanges(Map<String,Object> base, List<Map<String,Object>> changes) {
        return proposalChanges.applyAnalysisChanges(base, changes);
    }

    private List<Map<String,Object>> mergeAnalysisChangeMarkers(Map<String,Object> base, List<Map<String,Object>> existing, List<Map<String,Object>> updates) {
        return proposalChanges.mergeAnalysisChangeMarkers(base, existing, updates);
    }

    private List<?> candidateRows(Map<String,Object> config, String category) {
        return proposalChanges.candidateRows(config, category);
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
        List<Map<String,Object>> sourceRows = assetMapper.selectSourceAsset(assetId, STATUS_ACTIVE);
        if (sourceRows.isEmpty()) throw new IllegalArgumentException("Wiki 资产不存在或已停用: " + assetId);
        Map<String,Object> source = sourceRows.get(0);
        String prompt = "请把输入的业务资料整理成一张持久化 Wiki 页面。保留可核验事实、术语、规则及来源，不推测缺失信息。只返回 JSON：{\"title\":\"...\",\"summary\":\"...\",\"content\":\"Markdown...\"}。\n资料标题：" + source.get("asset_name") + "\n资料类型：" + source.get("asset_type") + "\n业务域：" + source.get("domain_name") + "\n说明：" + source.get("description") + "\n正文：\n" + clip(String.valueOf(source.get("content")), 30000);
        JsonNode page = parse(openAiText(prompt, "WIKI"));
        if (page == null || !page.path("content").isTextual() || page.path("content").asText().trim().isEmpty()) throw new IllegalStateException("模型未返回有效 Wiki 页面 JSON");
        String title = clip(page.path("title").asText(String.valueOf(source.get("asset_name"))), 200);
        String content = clip(page.path("content").asText(), 1000000);
        String tag = "SOURCE:" + assetId;
        List<Map<String,Object>> existing = assetMapper.selectPageBySourceTag(WikiAssetType.WIKI_PAGE.getCode(), tag);
        boolean updatingPage = !existing.isEmpty();
        Long previousSpaceId = null;
        if (updatingPage) {
            long pageAssetId = RowIds.first(existing, "asset_id");
            previousSpaceId = pageSpaceId(pageAssetId);
            assetMapper.updateIngestedPage(source.get("space_id") == null ? null : ((Number) source.get("space_id")).longValue(), title, source.get("domain_name"), clip(page.path("summary").asText("来源资料摘要"),1000), content, pageAssetId);
        } else {
            assetMapper.insertIngestedPage(source.get("space_id") == null ? null : ((Number) source.get("space_id")).longValue(), title, WikiAssetType.WIKI_PAGE.getCode(), source.get("domain_name"), clip(page.path("summary").asText("来源资料摘要"),1000), content, tag, STATUS_ACTIVE);
            existing = assetMapper.selectPageBySourceTag(WikiAssetType.WIKI_PAGE.getCode(), tag);
        }
        if (!existing.isEmpty()) {
            long pageAssetId = RowIds.first(existing, "asset_id");
            refreshChunkIndex(pageAssetId);
            refreshPageRelations(pageAssetId, updatingPage, previousSpaceId);
        }
        refreshIndex();
        Map<String,Object> result = new LinkedHashMap<>(); result.put("title", title); result.put("sourceId", assetId); result.put("updated", updatingPage); return result;
    }

    public Map<String, Object> health() {
        return Collections.singletonMap("enabled", enabled());
    }

    private void refreshChunkIndex(long assetId) {
        if (chunkIndexService != null) chunkIndexService.refreshAsset(assetId);
    }

    private Long pageSpaceId(long assetId) {
        return relationService == null ? null : relationService.pageSpaceId(assetId);
    }

    private void refreshPageRelations(long assetId, boolean updatingPage, Long previousSpaceId) {
        if (relationService == null) return;
        if (!updatingPage) relationService.refreshPage(assetId);
        else relationService.refreshPage(assetId, previousSpaceId);
    }

    /** URL 作为待整理来源记录；服务端不主动抓取任意地址，避免内网 SSRF。 */
    public Object addUrlSource(String url) {
        try {
            URI source = URI.create(url == null ? "" : url.trim());
            if (!("http".equalsIgnoreCase(source.getScheme()) || "https".equalsIgnoreCase(source.getScheme())) || source.getHost() == null || source.getUserInfo() != null) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) { throw new IllegalArgumentException("来源地址必须是有效的 http 或 https URL，且不能包含账号凭证"); }
        assetMapper.insertUrlSource(url.trim());
        return Collections.singletonMap("saved", true);
    }

    public Object listPages() { return assetMapper.selectPages(WikiAssetType.WIKI_PAGE.getCode(), STATUS_ACTIVE); }
    public Object getPage(String slug) { List<Map<String,Object>> rows=assetMapper.selectPageBySlug(WikiAssetType.WIKI_PAGE.getCode(), segment(slug), STATUS_ACTIVE); if(rows.isEmpty()) throw new IllegalArgumentException("Wiki 页面不存在"); return rows.get(0); }
    public Object createPage(Map<String,String> body) { return createPage(body, null); }
    public Object createPage(Map<String,String> body, Long spaceId) { if(body==null||text(body.get("slug"))==null) throw new IllegalArgumentException("Wiki 页面标题不能为空"); String slug=clip(segment(body.get("slug")),200); assetMapper.insertPage(spaceId, slug, WikiAssetType.WIKI_PAGE.getCode(), "人工维护页面", clip(body.get("content"),1000000), STATUS_ACTIVE); Map<String,Object> page=CollUtil.getFirst(assetMapper.selectPageBySlug(WikiAssetType.WIKI_PAGE.getCode(), slug, STATUS_ACTIVE)); long assetId=RowIds.required(page, "id"); refreshChunkIndex(assetId); refreshPageRelations(assetId, false, null); refreshIndex(); return page; }
    public Object updatePage(String slug,Map<String,String> body) { String key=segment(slug); int n=assetMapper.updatePageContent(clip(body==null?null:body.get("content"),1000000), WikiAssetType.WIKI_PAGE.getCode(), key, STATUS_ACTIVE); if(n==0) throw new IllegalArgumentException("Wiki 页面不存在"); Map<String,Object> page=CollUtil.getFirst(assetMapper.selectPageBySlug(WikiAssetType.WIKI_PAGE.getCode(), key, STATUS_ACTIVE)); long assetId=RowIds.required(page, "id"); refreshChunkIndex(assetId); refreshPageRelations(assetId, true, null); refreshIndex(); return page; }
    public Object deletePage(String slug) { String key=segment(slug); int n=assetMapper.softDeletePage("1", WikiAssetType.WIKI_PAGE.getCode(), key); if(n>0) { List<Map<String,Object>> page=assetMapper.selectExistingPage(WikiAssetType.WIKI_PAGE.getCode(), key, "1"); if(!page.isEmpty()) { long assetId=RowIds.first(page, "asset_id"); refreshChunkIndex(assetId); refreshPageRelations(assetId, true, null); } } refreshIndex(); return n>0; }
    public Object categoryPages(String category) { return assetMapper.selectPagesByCategory(WikiAssetType.WIKI_PAGE.getCode(), STATUS_ACTIVE, category); }
    public Object fileTree() { return listPages(); }
    public Object fileContent(String path) { return getPage(path.replaceFirst("^wiki/","").replaceFirst("\\.md$","")); }
    public Object index() { List<Map<String,Object>> rows=assetMapper.selectSystemIndex(WikiAssetType.WIKI_PAGE.getCode(), SYSTEM_INDEX_TAG, STATUS_ACTIVE); return rows.isEmpty()?Collections.singletonMap("content","# Wiki Index\\n暂无已整理页面"):rows.get(0); }
    public Object log() { return analysisMapper.selectRecentChatMessages(100); }

    /** 把草案中的对象、关系、术语拆成独立的结构化知识条目，便于下次分析按相关性精确召回。 */
    private void saveKnowledgeItems(long taskId, Object spaceId, Long proposalId,
            Map<String,Object> config, int version, String username) {
        List<Map<String,Object>> items = new ArrayList<>();
        collectItems(items, config, "objects",   "对象");
        collectItems(items, config, "relations", "关系");
        collectItems(items, config, "terms",     "术语");
        for (Map<String,Object> item : items) {
            String name  = value(item.get("name"));
            String label = value(item.get("label"));
            String desc  = value(item.get("description"));
            String table = value(item.get("table"));
            if (name.isEmpty() && label.isEmpty()) continue;
            String itemType = String.valueOf(item.get("_itemType"));
            String displayName = label.isEmpty() ? name : label + "（" + name + "）";
            String title    = itemType + "：" + displayName;
            String content  = desc.isEmpty() ? title : clip(desc, 300);
            Set<String> kws = new LinkedHashSet<>();
            if (!name.isEmpty())  kws.add(name);
            if (!label.isEmpty()) kws.add(label);
            if (!table.isEmpty()) kws.add(table);
            String keywords = String.join(",", kws);
            try {
                analysisMapper.insertKnowledgeItem(spaceId, taskId, proposalId,
                        title, content, keywords, "{\"kind\":\"KNOWLEDGE_ITEM\"}", version, username);
            } catch (Exception ignored) { /* 单条失败不阻断整体 */ }
        }
    }

    private void collectItems(List<Map<String,Object>> out, Map<String,Object> config,
            String key, String typeLabel) {
        Object raw = config.get(key);
        if (!(raw instanceof List)) return;
        for (Object item : (List<?>) raw) {
            if (!(item instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String,Object> m = new LinkedHashMap<>((Map<String,Object>) item);
            m.put("_itemType", typeLabel);
            out.add(m);
        }
    }

    /** 重建系统索引页；具体逻辑统一由 {@link WikiIndexService} 维护，避免两处实现漂移。 */
    void refreshIndex() {
        indexService.refreshIndex();
    }

    public Map<String, Object> testModelConnection(String baseUrl, String apiKey, String model,
                                                   String provider, Object settingId, Object usageScene) {
        return modelGateway.testConnection(baseUrl, apiKey, model, provider, settingId, usageScene);
    }

    /**
     * 供「执行分析」主链路使用的文本补全，固定走 ANALYSIS 场景模型。
     *
     * <p>分析主链路只需要一段自然语言说明和可选 JSON，不涉及 Wiki 页面检索，
     * 因此不复用 {@code queryResult}，避免被问答范围推导成 FAST 场景而丢失高能力模型配置。
     */
    public String analysisCompletion(String prompt) {
        requireEnabled();
        return openAiText(prompt, "ANALYSIS");
    }

    private String openAiText(String prompt, String scene) {
        return modelGateway.complete(prompt, scene, configForScene(scene));
    }

    /**
     * 按使用场景（ALL/ANALYSIS/WIKI/FAST）从 ontology_llm_settings 中选择模型配置。
     * 找不到对应场景时依次回退到 ALL，再找不到时回退内存配置（保持向后兼容）。
     */
    private Map<String,Object> configForScene(String scene) {
        // 先按精确场景查，找不到再查 ALL
        List<Map<String,Object>> rows = analysisMapper.selectEnabledSettingByScene(1, scene);
        if (rows.isEmpty() && !"ALL".equals(scene)) {
            rows = analysisMapper.selectEnabledSettingByScene(1, "ALL");
        }
        if (rows.isEmpty()) {
            throw new IllegalStateException("尚未配置可用的大模型，请先在「模型配置」中添加并启用一个模型");
        }
        Map<String,Object> row = rows.get(0);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("settingId", row.get("id"));
        result.put("usageScene", row.get("usage_scene") == null ? scene : row.get("usage_scene"));
        result.put("providerType", row.get("provider_type") == null ? "OPENAI_COMPAT" : row.get("provider_type"));
        result.put("apiBaseUrl", row.get("api_base_url") == null ? "" : String.valueOf(row.get("api_base_url")));
        result.put("model", row.get("model") == null ? "" : String.valueOf(row.get("model")));
        String apiKey = "";
        if (row.get("api_key_enc") != null && !String.valueOf(row.get("api_key_enc")).trim().isEmpty()) {
            try { apiKey = credentials.decrypt(String.valueOf(row.get("api_key_enc"))); } catch (Exception ignored) { }
        }
        result.put("apiKey", apiKey);
        result.put("configSource", row.get("usage_scene") == null || scene.equals(row.get("usage_scene")) ? "SCENE" : "ALL_FALLBACK");
        return result;
    }

    private JsonNode parse(String raw) { try { if(raw==null||raw.trim().isEmpty())return null; String s=raw.trim().replaceFirst("(?s)^```(?:json)?\\s*","").replaceFirst("\\s*```$",""); int a=s.indexOf('{'),b=s.lastIndexOf('}'); if(a>=0&&b>a)s=s.substring(a,b+1); return json.readTree(s); } catch(Exception e) { return null; } }
    private void requireEnabled() { if(!enabled()) throw new IllegalStateException("内置 LLM Wiki 未配置模型 API"); }
    private String segment(String value) { String v=text(value); if(v==null||v.indexOf('/')>=0||v.indexOf('\\')>=0||".".equals(v)||"..".equals(v)) throw new IllegalArgumentException("Wiki 页面标识无效"); return v; }
    private String text(String value) { return value==null||value.trim().isEmpty()?null:value.trim(); }
    private String clip(String value,int max) { return value==null?"":value.substring(0,Math.min(value.length(),max)); }
}

package com.ontologywiki.ontology.service;

import com.ontologywiki.ontology.mapper.LlmWikiFacadeMapper;
import com.ontologywiki.ontology.service.analysis.AnalysisMemoryService;
import com.ontologywiki.ontology.service.llm.LlmClient;
import com.ontologywiki.ontology.service.llm.LlmConfigService;
import com.ontologywiki.ontology.service.wiki.WikiIndexService;
import com.ontologywiki.ontology.service.wiki.WikiIngestionService;
import com.ontologywiki.ontology.service.wiki.WikiPageService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM Wiki 门面服务
 *
 * <p>提供统一的 LLM Wiki 能力入口，整合各个子服务。
 * <p>该类是重构过渡期的适配器，用于保持与旧代码的兼容性。
 * <p>新代码应该直接使用拆分后的服务（WikiPageService、LlmClient 等）。
 *
 * <p>SQL 已下沉至 {@link LlmWikiFacadeMapper}；仅建表语句保留在本类，
 * 因为 DDL 属于启动期结构迁移，不放在 Mapper 中。
 *
 * @author ontology-wiki
 * @deprecated 请直接使用拆分后的服务
 */
@Service
@Deprecated
public class LlmWikiFacade {

    /** 问答日志默认返回条数 */
    private static final int CHAT_LOG_LIMIT = 100;

    private final LlmConfigService configService;
    private final LlmClient llmClient;
    private final WikiPageService pageService;
    private final WikiIndexService indexService;
    private final WikiIngestionService ingestionService;
    private final AnalysisMemoryService memoryService;
    private final LlmWikiFacadeMapper facadeMapper;

    public LlmWikiFacade(LlmConfigService configService,
                         LlmClient llmClient,
                         WikiPageService pageService,
                         WikiIndexService indexService,
                         WikiIngestionService ingestionService,
                         AnalysisMemoryService memoryService,
                         LlmWikiFacadeMapper facadeMapper) {
        this.configService = configService;
        this.llmClient = llmClient;
        this.pageService = pageService;
        this.indexService = indexService;
        this.ingestionService = ingestionService;
        this.memoryService = memoryService;
        this.facadeMapper = facadeMapper;
    }

    /**
     * 兼容旧方法：仅重新加载配置，不再建表。
     *
     * <p>建表、加列、数据修正与菜单初始化已全部迁到
     * {@code backend/sql/ontology-wiki.sql}，由部署时人工执行，应用启动不再做任何结构变更。
     *
     * @deprecated 结构变更请执行 SQL 脚本，本方法只保留配置热加载语义
     */
    @Deprecated
    public void ensureChatSessionTable() {
        configService.loadStoredSettings();
    }

    // ==================== 配置管理 ====================

    public Map<String, Object> modelSettings() {
        return configService.getSettings();
    }

    public Map<String, Object> saveModelSettings(Map<String, Object> input, String username) {
        return configService.saveSettings(input, username);
    }

    public Map<String, Object> testModelSettings() {
        return configService.testConnection();
    }

    public Map<String, Object> health() {
        try {
            String models = llmClient.listModels();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("enabled", enabled());
            result.put("connected", true);
            result.put("provider", "internal");
            result.put("models", models);
            return result;
        } catch (Exception e) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("enabled", enabled());
            result.put("connected", false);
            result.put("provider", "internal");
            result.put("message", "模型 API 不可用");
            return result;
        }
    }

    public boolean enabled() {
        return configService.getSettings().get("enabled") == Boolean.TRUE;
    }

    // ==================== Wiki 页面管理 ====================

    public Object listPages() {
        return pageService.listPages();
    }

    public Object getPage(String slug) {
        return pageService.getPage(slug);
    }

    public Object createPage(Map<String, String> request) {
        String slug = request.get("slug");
        String content = request.get("content");
        return pageService.createPage(slug, content, null);
    }

    public Object updatePage(String slug, Map<String, String> request) {
        String content = request.get("content");
        return pageService.updatePage(slug, content);
    }

    public Object deletePage(String slug) {
        return pageService.deletePage(slug);
    }

    public Object categoryPages(String category) {
        return pageService.listPagesByCategory(category);
    }

    public Object fileTree() {
        return pageService.getFileTree();
    }

    public Object fileContent(String path) {
        return pageService.getFileContent(path);
    }

    public Object index() {
        return indexService.getIndex();
    }

    public Object log() {
        return facadeMapper.selectRecentChatMessages(CHAT_LOG_LIMIT);
    }

    public void refreshIndex() {
        indexService.refreshIndex();
    }

    // ==================== Wiki 整理 ====================

    public Map<String, Object> ingestAsset(Long assetId) {
        return ingestionService.ingestAsset(assetId);
    }

    public Object addUrlSource(String url) {
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("来源 URL 不能为空");
        }

        facadeMapper.insertUrlSource(url.trim());

        Map<String, Object> savedResult = new LinkedHashMap<>();
        savedResult.put("saved", true);
        return savedResult;
    }

    // ==================== 分析知识管理 ====================

    public Map<String, Object> saveAnalysisAnswer(Long taskId, String answer, boolean confirm, String username) {
        Long memoryId = confirm
                ? memoryService.confirmAnswer(taskId, null, answer, username, null)
                : memoryService.saveDraftAnswer(taskId, null, answer, username);

        return memoryService.getLatestMemory(taskId);
    }

    public Map<String, Object> saveAnalysisAnswer(Long taskId, Long proposalId, String answer, boolean confirm, String username) {
        Long memoryId = confirm
                ? memoryService.confirmAnswer(taskId, proposalId, answer, username, null)
                : memoryService.saveDraftAnswer(taskId, proposalId, answer, username);

        return memoryService.getLatestMemory(taskId);
    }

    public List<Map<String, Object>> listAnalysisMemory(Long taskId) {
        return memoryService.listMemories(taskId);
    }

    public Map<String, Object> latestAnalysisMemory(Long taskId) {
        return memoryService.getLatestMemory(taskId);
    }

    public String confirmedKnowledgeContext(Long taskId, Long spaceId) {
        return memoryService.getConfirmedKnowledgeContext(taskId, spaceId);
    }
}

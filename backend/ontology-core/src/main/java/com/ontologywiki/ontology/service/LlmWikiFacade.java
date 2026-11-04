package com.ontologywiki.ontology.service;

import com.ontologywiki.ontology.service.analysis.AnalysisMemoryService;
import com.ontologywiki.ontology.service.llm.LlmClient;
import com.ontologywiki.ontology.service.llm.LlmConfigService;
import com.ontologywiki.ontology.service.wiki.WikiIndexService;
import com.ontologywiki.ontology.service.wiki.WikiIngestionService;
import com.ontologywiki.ontology.service.wiki.WikiPageService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.List;
import java.util.Map;

/**
 * LLM Wiki 门面服务
 *
 * <p>提供统一的 LLM Wiki 能力入口，整合各个子服务。
 * <p>该类是重构过渡期的适配器，用于保持与旧代码的兼容性。
 * <p>新代码应该直接使用拆分后的服务（WikiPageService、LlmClient 等）。
 *
 * @author ontology-wiki
 * @deprecated 请直接使用拆分后的服务
 */
@Service
@Deprecated
public class LlmWikiFacade {

    private final LlmConfigService configService;
    private final LlmClient llmClient;
    private final WikiPageService pageService;
    private final WikiIndexService indexService;
    private final WikiIngestionService ingestionService;
    private final AnalysisMemoryService memoryService;
    private final JdbcTemplate jdbcTemplate;

    public LlmWikiFacade(LlmConfigService configService,
                         LlmClient llmClient,
                         WikiPageService pageService,
                         WikiIndexService indexService,
                         WikiIngestionService ingestionService,
                         AnalysisMemoryService memoryService,
                         JdbcTemplate jdbcTemplate) {
        this.configService = configService;
        this.llmClient = llmClient;
        this.pageService = pageService;
        this.indexService = indexService;
        this.ingestionService = ingestionService;
        this.memoryService = memoryService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 初始化表结构（兼容旧方法）
     */
    @PostConstruct
    public void ensureChatSessionTable() {
        // 创建 LLM 配置表
        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS ontology_llm_settings (" +
                        "setting_id TINYINT NOT NULL, " +
                        "enabled TINYINT(1) NOT NULL DEFAULT 0, " +
                        "api_base_url VARCHAR(1000), " +
                        "api_key_enc TEXT, " +
                        "model VARCHAR(200), " +
                        "update_by VARCHAR(64), " +
                        "update_time DATETIME, " +
                        "PRIMARY KEY(setting_id)" +
                        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
        );

        // 创建分析知识表
        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS ontology_analysis_memory (" +
                        "memory_id BIGINT NOT NULL AUTO_INCREMENT, " +
                        "space_id BIGINT NULL, " +
                        "task_id BIGINT NOT NULL, " +
                        "proposal_id BIGINT NULL, " +
                        "memory_type VARCHAR(40) NOT NULL DEFAULT 'ANALYSIS_ANSWER', " +
                        "title VARCHAR(255) NOT NULL, " +
                        "content LONGTEXT NOT NULL, " +
                        "source_json LONGTEXT NULL, " +
                        "status VARCHAR(30) NOT NULL DEFAULT 'DRAFT', " +
                        "version INT NOT NULL DEFAULT 1, " +
                        "created_by VARCHAR(64), " +
                        "create_time DATETIME, " +
                        "update_by VARCHAR(64), " +
                        "update_time DATETIME, " +
                        "reviewed_by VARCHAR(64), " +
                        "review_time DATETIME, " +
                        "PRIMARY KEY(memory_id), " +
                        "KEY idx_analysis_memory_task(task_id,status), " +
                        "KEY idx_analysis_memory_space(space_id,status)" +
                        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
        );

        // 创建问答会话表
        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS wiki_chat_session (" +
                        "session_id VARCHAR(64) NOT NULL, " +
                        "space_id BIGINT NULL, " +
                        "scope_type VARCHAR(20) NOT NULL DEFAULT 'ALL', " +
                        "scope_id BIGINT NULL, " +
                        "context_state_json LONGTEXT NULL, " +
                        "title VARCHAR(200) NOT NULL, " +
                        "created_by VARCHAR(64) NOT NULL, " +
                        "create_time DATETIME NULL, " +
                        "update_time DATETIME NULL, " +
                        "PRIMARY KEY(session_id,created_by), " +
                        "KEY idx_wiki_chat_session_user(created_by,update_time)" +
                        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
        );

        // 创建引用关联表
        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS wiki_chat_citation (" +
                        "message_id BIGINT NOT NULL, " +
                        "asset_id BIGINT NOT NULL, " +
                        "PRIMARY KEY(message_id,asset_id), " +
                        "KEY idx_wiki_chat_citation_asset(asset_id)" +
                        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
        );

        // 加载已存储的配置
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
            return Map.of(
                    "enabled", enabled(),
                    "connected", true,
                    "provider", "internal",
                    "models", models
            );
        } catch (Exception e) {
            return Map.of(
                    "enabled", enabled(),
                    "connected", false,
                    "provider", "internal",
                    "message", "模型 API 不可用"
            );
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
        List<Map<String, Object>> messages = jdbcTemplate.queryForList(
                "SELECT role, content, create_time " +
                        "FROM wiki_chat_message " +
                        "ORDER BY message_id DESC " +
                        "LIMIT 100"
        );
        return messages;
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

        jdbcTemplate.update(
                "INSERT INTO wiki_asset " +
                        "(asset_name, asset_type, description, content, status, " +
                        "create_by, create_time, update_by, update_time) " +
                        "VALUES (?, 'URL', 'URL 待整理来源', ?, '0', 'system', NOW(), 'system', NOW())",
                url.trim(),
                url.trim()
        );

        return Map.of("saved", true);
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

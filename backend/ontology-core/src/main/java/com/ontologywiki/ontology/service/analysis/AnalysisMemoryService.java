package com.ontologywiki.ontology.service.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ontologywiki.ontology.domain.AnalysisMemoryStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 分析知识记忆服务
 *
 * <p>职责：
 * <ul>
 *   <li>保存分析答案的版本历史</li>
 *   <li>管理已确认知识的沉淀</li>
 *   <li>提供已确认知识的检索</li>
 *   <li>生成知识 Wiki 页面</li>
 * </ul>
 *
 * @author ontology-wiki
 */
@Service
public class AnalysisMemoryService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AnalysisMemoryService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 保存分析答案（草稿状态）
     *
     * @param taskId     分析任务 ID
     * @param proposalId 配置草案 ID（可选）
     * @param answer     分析答案
     * @param username   操作人
     * @return 保存后的记忆 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public Long saveDraftAnswer(Long taskId, Long proposalId, String answer, String username) {
        return saveAnswer(taskId, proposalId, answer, false, username, null);
    }

    /**
     * 确认并沉淀分析答案
     *
     * @param taskId         分析任务 ID
     * @param proposalId     配置草案 ID（可选）
     * @param answer         分析答案
     * @param username       操作人
     * @param proposalConfig 配置草案 JSON（用于生成知识 Markdown）
     * @return 保存后的记忆 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public Long confirmAnswer(Long taskId, Long proposalId, String answer,
                              String username, Map<String, Object> proposalConfig) {
        return saveAnswer(taskId, proposalId, answer, true, username, proposalConfig);
    }

    /**
     * 保存分析答案
     */
    private Long saveAnswer(Long taskId, Long proposalId, String answer,
                            boolean confirm, String username, Map<String, Object> proposalConfig) {
        if (answer == null || answer.trim().isEmpty()) {
            throw new IllegalArgumentException("分析答案不能为空");
        }

        if (answer.length() > 60000) {
            throw new IllegalArgumentException("分析答案不能超过 60000 字");
        }

        // 获取任务信息
        Map<String, Object> task = jdbcTemplate.queryForMap(
                "SELECT task_name, wiki_space_id FROM analysis_task WHERE task_id = ?",
                taskId
        );

        // 计算版本号
        int version = calculateNextVersion(taskId);

        // 确定状态
        AnalysisMemoryStatus status = confirm
                ? AnalysisMemoryStatus.CONFIRMED
                : AnalysisMemoryStatus.DRAFT;

        // 构建知识内容
        String content = confirm && proposalConfig != null
                ? buildKnowledgeMarkdown(answer, proposalConfig)
                : answer.trim();

        // 构建来源 JSON
        String sourceJson = buildSourceJson(taskId, proposalId);

        // 插入记忆记录
        jdbcTemplate.update(
                "INSERT INTO ontology_analysis_memory " +
                        "(space_id, task_id, proposal_id, memory_type, title, content, source_json, status, version, " +
                        "created_by, create_time, update_by, update_time, reviewed_by, review_time) " +
                        "VALUES (?, ?, ?, 'ANALYSIS_ANSWER', ?, ?, ?, ?, ?, ?, NOW(), ?, NOW(), ?, ?)",
                task.get("wiki_space_id"),
                taskId,
                proposalId,
                String.valueOf(task.get("task_name")) + " · 分析答案 v" + version,
                content,
                sourceJson,
                status.getCode(),
                version,
                username,
                username,
                confirm ? username : null,
                confirm ? new Date() : null
        );

        // 查询刚插入的记录 ID
        Long memoryId = jdbcTemplate.queryForObject(
                "SELECT LAST_INSERT_ID()",
                Long.class
        );

        // 如果是确认状态，生成 Wiki 页面
        if (confirm && memoryId != null) {
            createKnowledgeWikiPage(memoryId, taskId, proposalId, task, content, version, username);
        }

        // 更新任务摘要
        jdbcTemplate.update(
                "UPDATE analysis_task SET summary = ?, update_by = ?, update_time = NOW() WHERE task_id = ?",
                clip(answer.trim(), 60000),
                username,
                taskId
        );

        return memoryId;
    }

    /**
     * 创建知识 Wiki 页面
     */
    private void createKnowledgeWikiPage(Long memoryId, Long taskId, Long proposalId,
                                         Map<String, Object> task, String content,
                                         int version, String username) {
        String tag = "ANALYSIS_MEMORY:" + memoryId;
        String title = "分析知识 · " + task.get("task_name") + " · v" + version;

        String pageContent = "# " + title + "\n\n" + content + "\n\n" +
                "> 来源：分析任务 #" + taskId + "，配置草案 #" +
                (proposalId == null ? "—" : proposalId) +
                "。该页面由分析者确认后沉淀，可在后续分析中作为已确认知识参考。";

        List<Map<String, Object>> existingPages = jdbcTemplate.queryForList(
                "SELECT asset_id FROM wiki_asset WHERE tags = ? LIMIT 1",
                tag
        );

        if (existingPages.isEmpty()) {
            jdbcTemplate.update(
                    "INSERT INTO wiki_asset " +
                            "(space_id, asset_name, asset_type, description, content, tags, status, " +
                            "create_by, create_time, update_by, update_time) " +
                            "VALUES (?, ?, 'WIKI_PAGE', '已确认分析知识', ?, ?, '0', ?, NOW(), ?, NOW())",
                    task.get("wiki_space_id"),
                    title,
                    pageContent,
                    tag,
                    username,
                    username
            );
        } else {
            jdbcTemplate.update(
                    "UPDATE wiki_asset " +
                            "SET space_id = ?, asset_name = ?, description = '已确认分析知识', content = ?, " +
                            "update_by = ?, update_time = NOW() " +
                            "WHERE asset_id = ?",
                    task.get("wiki_space_id"),
                    title,
                    pageContent,
                    username,
                    existingPages.get(0).get("asset_id")
            );
        }
    }

    /**
     * 查询任务的所有记忆
     *
     * @param taskId 任务 ID
     * @return 记忆列表
     */
    public List<Map<String, Object>> listMemories(Long taskId) {
        return jdbcTemplate.queryForList(
                "SELECT memory_id, task_id, proposal_id, memory_type, title, content, status, version, " +
                        "created_by, create_time, reviewed_by, review_time " +
                        "FROM ontology_analysis_memory " +
                        "WHERE task_id = ? " +
                        "ORDER BY memory_id DESC",
                taskId
        );
    }

    /**
     * 查询任务的最新记忆
     *
     * @param taskId 任务 ID
     * @return 最新记忆，不存在时返回空 Map
     */
    public Map<String, Object> getLatestMemory(Long taskId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT memory_id, task_id, proposal_id, memory_type, title, content, status, version, " +
                        "created_by, create_time, reviewed_by, review_time " +
                        "FROM ontology_analysis_memory " +
                        "WHERE task_id = ? " +
                        "ORDER BY memory_id DESC " +
                        "LIMIT 1",
                taskId
        );

        return rows.isEmpty() ? Collections.emptyMap() : rows.get(0);
    }

    /**
     * 获取已确认知识的上下文（用于分析时参考）
     *
     * @param taskId  当前任务 ID
     * @param spaceId 知识空间 ID
     * @return 已确认知识摘要
     */
    public String getConfirmedKnowledgeContext(Long taskId, Long spaceId) {
        if (taskId == null && spaceId == null) {
            return "";
        }

        List<Map<String, Object>> rows;

        if (taskId != null && spaceId != null) {
            rows = jdbcTemplate.queryForList(
                    "SELECT title, content, version, task_id " +
                            "FROM ontology_analysis_memory " +
                            "WHERE status = ? AND (task_id = ? OR space_id = ?) " +
                            "ORDER BY update_time DESC, memory_id DESC " +
                            "LIMIT 80",
                    AnalysisMemoryStatus.CONFIRMED.getCode(),
                    taskId,
                    spaceId
            );
        } else if (taskId != null) {
            rows = jdbcTemplate.queryForList(
                    "SELECT title, content, version, task_id " +
                            "FROM ontology_analysis_memory " +
                            "WHERE status = ? AND task_id = ? " +
                            "ORDER BY update_time DESC, memory_id DESC " +
                            "LIMIT 80",
                    AnalysisMemoryStatus.CONFIRMED.getCode(),
                    taskId
            );
        } else {
            rows = jdbcTemplate.queryForList(
                    "SELECT title, content, version, task_id " +
                            "FROM ontology_analysis_memory " +
                            "WHERE status = ? AND space_id = ? " +
                            "ORDER BY update_time DESC, memory_id DESC " +
                            "LIMIT 80",
                    AnalysisMemoryStatus.CONFIRMED.getCode(),
                    spaceId
            );
        }

        if (rows.isEmpty()) {
            return "";
        }

        StringBuilder context = new StringBuilder();
        for (Map<String, Object> row : rows) {
            context.append("\n- ")
                    .append(row.get("title"))
                    .append("（任务 #")
                    .append(row.get("task_id"))
                    .append("，版本 ")
                    .append(row.get("version"))
                    .append("）\n  ")
                    .append(clip(String.valueOf(row.get("content")), 3000));
        }

        return context.toString().trim();
    }

    /**
     * 计算下一个版本号
     */
    private int calculateNextVersion(Long taskId) {
        Integer maxVersion = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(version), 0) FROM ontology_analysis_memory WHERE task_id = ?",
                Integer.class,
                taskId
        );

        return (maxVersion == null ? 0 : maxVersion) + 1;
    }

    /**
     * 构建来源 JSON
     */
    private String buildSourceJson(Long taskId, Long proposalId) {
        try {
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("taskId", taskId);
            source.put("proposalId", proposalId);
            source.put("kind", "ANALYSIS_ANSWER");
            return objectMapper.writeValueAsString(source);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * 构建知识 Markdown
     */
    private String buildKnowledgeMarkdown(String answer, Map<String, Object> config) {
        StringBuilder markdown = new StringBuilder("## 分析说明\n\n")
                .append(answer);

        // 添加对象与字段
        appendKnowledgeSection(markdown, "对象与字段", config, "objects", "properties");

        // 添加关系
        appendKnowledgeSection(markdown, "关系", config, "relations");

        // 添加业务逻辑
        appendKnowledgeSection(markdown, "业务逻辑", config, "logics");

        // 添加业务动作
        appendKnowledgeSection(markdown, "业务动作", config, "actions");

        // 添加服务流
        appendKnowledgeSection(markdown, "服务流", config, "serviceFlows");

        // 添加权限候选
        appendKnowledgeSection(markdown, "权限候选", config, "permissionCandidates");

        return markdown.toString();
    }

    /**
     * 追加知识章节
     */
    private void appendKnowledgeSection(StringBuilder markdown, String title,
                                        Map<String, Object> config, String... categories) {
        boolean wroteHeader = false;

        for (String category : categories) {
            Object rawList = config.get(category);
            if (!(rawList instanceof List) || ((List<?>) rawList).isEmpty()) {
                continue;
            }

            if (!wroteHeader) {
                markdown.append("\n\n## ").append(title).append("\n");
                wroteHeader = true;
            }

            List<?> items = (List<?>) rawList;
            for (Object item : items) {
                if (!(item instanceof Map)) {
                    continue;
                }

                Map<?, ?> row = (Map<?, ?>) item;
                String name = getItemName(row);
                markdown.append("- **").append(name).append("**");

                // 追加关键字段
                appendFieldIfPresent(markdown, row, "table", "表");
                appendFieldIfPresent(markdown, row, "column", "字段");
                appendFieldIfPresent(markdown, row, "dataType", "类型");
                appendFieldIfPresent(markdown, row, "description", "说明");

                markdown.append("\n");
            }
        }
    }

    /**
     * 获取项目名称
     */
    private String getItemName(Map<?, ?> row) {
        Object nameValue = row.get("name");
        if (nameValue == null) {
            nameValue = row.get("table");
        }
        if (nameValue == null) {
            nameValue = row.get("column");
        }
        return nameValue == null ? "未命名" : String.valueOf(nameValue);
    }

    /**
     * 追加字段（如果存在）
     */
    private void appendFieldIfPresent(StringBuilder markdown, Map<?, ?> row, String key, String label) {
        Object value = row.get(key);
        if (value != null && !String.valueOf(value).trim().isEmpty()) {
            markdown.append("；").append(label).append("：").append(value);
        }
    }

    /**
     * 裁剪字符串
     */
    private String clip(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.substring(0, Math.min(value.length(), maxLength));
    }
}

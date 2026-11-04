package com.ontologywiki.ontology.service.wiki;

import com.ontologywiki.ontology.domain.WikiAssetStatus;
import com.ontologywiki.ontology.domain.WikiAssetType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wiki 索引维护服务
 *
 * <p>职责：
 * <ul>
 *   <li>维护 Wiki 页面的全局索引</li>
 *   <li>支持 LLM 基于索引导航选择相关页面</li>
 *   <li>自动更新索引内容</li>
 * </ul>
 *
 * @author ontology-wiki
 */
@Service
public class WikiIndexService {

    private static final String SYSTEM_INDEX_TAG = "SYSTEM_INDEX";
    private static final int MAX_INDEX_PAGES = 500;

    private final JdbcTemplate jdbcTemplate;

    public WikiIndexService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 获取 Wiki 索引内容
     *
     * @return 索引页面内容
     */
    public Map<String, Object> getIndex() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT content " +
                        "FROM wiki_asset " +
                        "WHERE asset_type = ? AND tags = ? AND status = ? " +
                        "LIMIT 1",
                WikiAssetType.WIKI_PAGE.getCode(),
                SYSTEM_INDEX_TAG,
                WikiAssetStatus.ACTIVE.getCode()
        );

        Map<String, Object> result = new LinkedHashMap<>();

        if (rows.isEmpty()) {
            result.put("content", "# Wiki Index\n\n暂无已整理页面");
        } else {
            result.put("content", rows.get(0).get("content"));
        }

        return result;
    }

    /**
     * 刷新 Wiki 索引
     *
     * <p>读取所有活动的 Wiki 页面，生成 Markdown 格式的索引列表并保存
     */
    @Transactional(rollbackFor = Exception.class)
    public void refreshIndex() {
        // 查询所有非索引页的 Wiki 页面
        List<Map<String, Object>> pages = jdbcTemplate.queryForList(
                "SELECT asset_name, domain_name, description, update_time " +
                        "FROM wiki_asset " +
                        "WHERE asset_type = ? AND status = ? AND COALESCE(tags, '') <> ? " +
                        "ORDER BY asset_name " +
                        "LIMIT ?",
                WikiAssetType.WIKI_PAGE.getCode(),
                WikiAssetStatus.ACTIVE.getCode(),
                SYSTEM_INDEX_TAG,
                MAX_INDEX_PAGES
        );

        // 构建索引内容
        StringBuilder markdown = new StringBuilder("# Wiki Index\n\n");

        for (Map<String, Object> page : pages) {
            markdown.append("- ")
                    .append(page.get("asset_name"))
                    .append(" | ")
                    .append(page.get("domain_name"))
                    .append(" | ")
                    .append(page.get("description"))
                    .append("\n");
        }

        String indexContent = markdown.toString();

        // 查找是否已有索引页
        List<Map<String, Object>> existingIndex = jdbcTemplate.queryForList(
                "SELECT asset_id " +
                        "FROM wiki_asset " +
                        "WHERE asset_type = ? AND tags = ? " +
                        "LIMIT 1",
                WikiAssetType.WIKI_PAGE.getCode(),
                SYSTEM_INDEX_TAG
        );

        if (existingIndex.isEmpty()) {
            // 首次创建索引页
            jdbcTemplate.update(
                    "INSERT INTO wiki_asset (asset_name, asset_type, description, content, tags, status, " +
                            "create_by, create_time, update_by, update_time) " +
                            "VALUES ('index', ?, 'LLM Wiki 页面索引', ?, ?, ?, 'system', NOW(), 'system', NOW())",
                    WikiAssetType.WIKI_PAGE.getCode(),
                    indexContent,
                    SYSTEM_INDEX_TAG,
                    WikiAssetStatus.ACTIVE.getCode()
            );
        } else {
            // 更新已有索引页
            Long indexAssetId = ((Number) existingIndex.get(0).get("asset_id")).longValue();
            jdbcTemplate.update(
                    "UPDATE wiki_asset " +
                            "SET content = ?, update_by = 'system', update_time = NOW() " +
                            "WHERE asset_id = ?",
                    indexContent,
                    indexAssetId
            );
        }
    }

    /**
     * 获取索引统计信息
     *
     * @return 索引统计
     */
    public Map<String, Object> getIndexStats() {
        Integer totalPages = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) " +
                        "FROM wiki_asset " +
                        "WHERE asset_type = ? AND status = ? AND COALESCE(tags, '') <> ?",
                Integer.class,
                WikiAssetType.WIKI_PAGE.getCode(),
                WikiAssetStatus.ACTIVE.getCode(),
                SYSTEM_INDEX_TAG
        );

        Integer totalSources = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) " +
                        "FROM wiki_asset " +
                        "WHERE asset_type <> ? AND status = ?",
                Integer.class,
                WikiAssetType.WIKI_PAGE.getCode(),
                WikiAssetStatus.ACTIVE.getCode()
        );

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalPages", totalPages == null ? 0 : totalPages);
        stats.put("totalSources", totalSources == null ? 0 : totalSources);
        stats.put("maxIndexPages", MAX_INDEX_PAGES);

        return stats;
    }
}

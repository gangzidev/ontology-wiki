package com.ontologywiki.ontology.service.wiki;

import com.ontologywiki.ontology.domain.WikiAssetStatus;
import com.ontologywiki.ontology.domain.WikiAssetType;
import com.ontologywiki.ontology.mapper.WikiAssetMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wiki 索引维护服务。
 *
 * <p>职责：
 * <ul>
 *   <li>维护 Wiki 页面的全局索引（系统索引页）；</li>
 *   <li>支持 LLM 基于索引导航选择相关页面；</li>
 *   <li>页面增删改后自动重建索引内容。</li>
 * </ul>
 *
 * <p>SQL 已全部下沉至 {@link WikiAssetMapper} 及对应 XML。
 */
@Service
public class WikiIndexService {

    /** 系统索引页固定标签，用于与业务页面区分 */
    private static final String SYSTEM_INDEX_TAG = "SYSTEM_INDEX";
    /** 索引最多收录的页面数，避免索引页本身过大 */
    private static final int MAX_INDEX_PAGES = 500;

    private final WikiAssetMapper assetMapper;

    public WikiIndexService(WikiAssetMapper assetMapper) {
        this.assetMapper = assetMapper;
    }

    /**
     * 获取 Wiki 索引内容。
     *
     * @return 索引页面内容
     */
    public Map<String, Object> getIndex() {
        List<Map<String, Object>> rows = assetMapper.selectSystemIndex(
                WikiAssetType.WIKI_PAGE.getCode(), SYSTEM_INDEX_TAG, WikiAssetStatus.ACTIVE.getCode());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", rows.isEmpty() ? "# Wiki Index\n\n暂无已整理页面" : rows.get(0).get("content"));
        return result;
    }

    /**
     * 刷新 Wiki 索引。
     *
     * <p>读取所有活动 Wiki 页面的标题、业务域与说明，生成 Markdown 列表后写入系统索引页。
     */
    @Transactional(rollbackFor = Exception.class)
    public void refreshIndex() {
        List<Map<String, Object>> pages = assetMapper.selectIndexablePages(
                WikiAssetType.WIKI_PAGE.getCode(), WikiAssetStatus.ACTIVE.getCode(), SYSTEM_INDEX_TAG, MAX_INDEX_PAGES);

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

        List<Map<String, Object>> existingIndex = assetMapper.selectIndexPageId(
                WikiAssetType.WIKI_PAGE.getCode(), SYSTEM_INDEX_TAG);
        if (existingIndex.isEmpty()) {
            assetMapper.insertSystemIndex(WikiAssetType.WIKI_PAGE.getCode(), indexContent,
                    SYSTEM_INDEX_TAG, WikiAssetStatus.ACTIVE.getCode());
        } else {
            Long indexAssetId = ((Number) existingIndex.get(0).get("asset_id")).longValue();
            assetMapper.updateSystemIndexContent(indexContent, indexAssetId);
        }
    }

    /**
     * 获取索引统计信息。
     *
     * @return 索引统计
     */
    public Map<String, Object> getIndexStats() {
        Integer totalPages = assetMapper.countIndexablePages(
                WikiAssetType.WIKI_PAGE.getCode(), WikiAssetStatus.ACTIVE.getCode(), SYSTEM_INDEX_TAG);
        Integer totalSources = assetMapper.countSourceAssets(
                WikiAssetType.WIKI_PAGE.getCode(), WikiAssetStatus.ACTIVE.getCode());

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalPages", totalPages == null ? 0 : totalPages);
        stats.put("totalSources", totalSources == null ? 0 : totalSources);
        stats.put("maxIndexPages", MAX_INDEX_PAGES);
        return stats;
    }
}

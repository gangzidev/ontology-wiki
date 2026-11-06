package com.ontologywiki.ontology.service.wiki;

import com.ontologywiki.ontology.domain.WikiAssetStatus;
import com.ontologywiki.ontology.domain.WikiAssetType;
import com.ontologywiki.ontology.mapper.WikiAssetMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Wiki 页面管理服务。
 *
 * <p>职责：
 * <ul>
 *   <li>Wiki 页面的增删改查与页面标识校验；</li>
 *   <li>页面变更后触发全局索引刷新；</li>
 *   <li>兼容旧的「文件树 / 文件内容」接口。</li>
 * </ul>
 *
 * <p>SQL 已全部下沉至 {@link WikiAssetMapper} 及对应 XML。
 */
@Service
public class WikiPageService {

    /** 人工维护页面的固定说明 */
    private static final String MANUAL_PAGE_DESCRIPTION = "人工维护页面";

    private final WikiAssetMapper assetMapper;
    private final WikiIndexService indexService;

    public WikiPageService(WikiAssetMapper assetMapper, WikiIndexService indexService) {
        this.assetMapper = assetMapper;
        this.indexService = indexService;
    }

    /**
     * 获取所有 Wiki 页面列表（不含系统索引页）。
     *
     * @return 页面列表
     */
    public List<Map<String, Object>> listPages() {
        return assetMapper.selectPages(WikiAssetType.WIKI_PAGE.getCode(), WikiAssetStatus.ACTIVE.getCode());
    }

    /**
     * 根据页面标识获取页面详情。
     *
     * @param slug 页面标识（slug）
     * @return 页面详情
     * @throws IllegalArgumentException 页面不存在时抛出
     */
    public Map<String, Object> getPage(String slug) {
        validateSlug(slug);
        List<Map<String, Object>> rows = assetMapper.selectPageBySlug(
                WikiAssetType.WIKI_PAGE.getCode(), slug, WikiAssetStatus.ACTIVE.getCode());
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Wiki 页面不存在: " + slug);
        }
        return rows.get(0);
    }

    /**
     * 创建新的 Wiki 页面。
     *
     * @param slug    页面标识
     * @param content 页面内容（Markdown）
     * @param spaceId 所属知识空间 ID（可选）
     * @return 创建后的页面详情
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> createPage(String slug, String content, Long spaceId) {
        validateSlug(slug);

        // 同类型同状态下的页面标识必须唯一
        List<Map<String, Object>> existing = assetMapper.selectExistingPage(
                WikiAssetType.WIKI_PAGE.getCode(), slug, WikiAssetStatus.ACTIVE.getCode());
        if (!existing.isEmpty()) {
            throw new IllegalArgumentException("Wiki 页面已存在: " + slug);
        }

        assetMapper.insertPage(spaceId, slug, WikiAssetType.WIKI_PAGE.getCode(),
                MANUAL_PAGE_DESCRIPTION, clipContent(content), WikiAssetStatus.ACTIVE.getCode());

        indexService.refreshIndex();
        return getPage(slug);
    }

    /**
     * 更新已有 Wiki 页面内容。
     *
     * @param slug    页面标识
     * @param content 新内容
     * @return 更新后的页面详情
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> updatePage(String slug, String content) {
        validateSlug(slug);
        int updated = assetMapper.updatePageContent(clipContent(content),
                WikiAssetType.WIKI_PAGE.getCode(), slug, WikiAssetStatus.ACTIVE.getCode());
        if (updated == 0) {
            throw new IllegalArgumentException("Wiki 页面不存在: " + slug);
        }
        indexService.refreshIndex();
        return getPage(slug);
    }

    /**
     * 删除 Wiki 页面（逻辑删除）。
     *
     * @param slug 页面标识
     * @return 是否删除成功
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean deletePage(String slug) {
        validateSlug(slug);
        int deleted = assetMapper.softDeletePage(WikiAssetStatus.INACTIVE.getCode(),
                WikiAssetType.WIKI_PAGE.getCode(), slug);
        if (deleted > 0) {
            indexService.refreshIndex();
        }
        return deleted > 0;
    }

    /**
     * 按分类查询 Wiki 页面。
     *
     * @param category 业务域/分类
     * @return 该分类下的页面列表
     */
    public List<Map<String, Object>> listPagesByCategory(String category) {
        if (category == null || category.trim().isEmpty()) {
            throw new IllegalArgumentException("分类名称不能为空");
        }
        return assetMapper.selectPagesByCategory(WikiAssetType.WIKI_PAGE.getCode(),
                WikiAssetStatus.ACTIVE.getCode(), category);
    }

    /**
     * 获取文件树结构（兼容旧接口）。
     *
     * @return 文件树数据
     */
    public List<Map<String, Object>> getFileTree() {
        return listPages();
    }

    /**
     * 获取文件内容（兼容旧接口）。
     *
     * @param path 文件路径
     * @return 文件内容
     */
    public Map<String, Object> getFileContent(String path) {
        String slug = path.replaceFirst("^wiki/", "").replaceFirst("\\.md$", "");
        return getPage(slug);
    }

    /** 校验页面标识合法性：不允许空值、路径分隔符与超长 */
    private void validateSlug(String slug) {
        if (slug == null || slug.trim().isEmpty()) {
            throw new IllegalArgumentException("Wiki 页面标识不能为空");
        }
        String trimmed = slug.trim();
        if (trimmed.contains("/") || trimmed.contains("\\") || ".".equals(trimmed) || "..".equals(trimmed)) {
            throw new IllegalArgumentException("Wiki 页面标识无效: " + slug);
        }
        if (trimmed.length() > 200) {
            throw new IllegalArgumentException("Wiki 页面标识不能超过 200 字符");
        }
    }

    /** 限制内容长度，避免超长 Markdown 撑爆单字段 */
    private String clipContent(String content) {
        if (content == null) {
            return "";
        }
        return content.substring(0, Math.min(content.length(), 1000000));
    }
}

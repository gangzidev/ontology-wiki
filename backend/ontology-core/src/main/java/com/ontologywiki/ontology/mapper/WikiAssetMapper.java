package com.ontologywiki.ontology.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * Wiki 资产（{@code wiki_asset}）数据访问。
 *
 * <p>覆盖三类使用场景：
 * <ul>
 *   <li>页面管理：Wiki 页面的查询、新增、更新、逻辑删除；</li>
 *   <li>索引维护：系统索引页的读取与重建；</li>
 *   <li>资料整理：原始资产读取、按来源标签查找并写入整理后的页面。</li>
 * </ul>
 */
@Mapper
public interface WikiAssetMapper {

    // ==================== 页面查询 ====================

    /**
     * 查询全部 Wiki 页面（排除系统索引页）。
     *
     * @param assetType Wiki 页面类型
     * @param status    资产状态
     * @return 页面列表
     */
    List<Map<String, Object>> selectPages(@Param("assetType") String assetType,
                                          @Param("status") String status);

    /**
     * 按页面标识查询单个 Wiki 页面。
     *
     * @param assetType Wiki 页面类型
     * @param slug      页面标识（即 asset_name）
     * @param status    资产状态
     * @return 页面，不存在时为空列表
     */
    List<Map<String, Object>> selectPageBySlug(@Param("assetType") String assetType,
                                               @Param("slug") String slug,
                                               @Param("status") String status);

    /**
     * 按业务域查询 Wiki 页面。
     *
     * @param assetType  Wiki 页面类型
     * @param status     资产状态
     * @param domainName 业务域名
     * @return 页面列表
     */
    List<Map<String, Object>> selectPagesByCategory(@Param("assetType") String assetType,
                                                    @Param("status") String status,
                                                    @Param("domainName") String domainName);

    /**
     * 判断同名 Wiki 页面是否已存在，用于创建前查重。
     *
     * @param assetType Wiki 页面类型
     * @param slug      页面标识
     * @param status    资产状态
     * @return 已存在的页面（只含 asset_id）
     */
    List<Map<String, Object>> selectExistingPage(@Param("assetType") String assetType,
                                                 @Param("slug") String slug,
                                                 @Param("status") String status);

    // ==================== 页面写入 ====================

    /**
     * 新增 Wiki 页面。
     *
     * @param spaceId      所属知识空间，可为空
     * @param slug         页面标识
     * @param assetType    Wiki 页面类型
     * @param description  页面说明
     * @param content      页面内容
     * @param status       资产状态
     * @return 影响行数
     */
    int insertPage(@Param("spaceId") Long spaceId,
                   @Param("slug") String slug,
                   @Param("assetType") String assetType,
                   @Param("description") String description,
                   @Param("content") String content,
                   @Param("status") String status);

    /**
     * 更新 Wiki 页面内容。
     *
     * @param content   新内容
     * @param assetType Wiki 页面类型
     * @param slug      页面标识
     * @param status    资产状态
     * @return 影响行数
     */
    int updatePageContent(@Param("content") String content,
                          @Param("assetType") String assetType,
                          @Param("slug") String slug,
                          @Param("status") String status);

    /**
     * 逻辑删除 Wiki 页面。
     *
     * @param inactiveStatus 停用状态值
     * @param assetType      Wiki 页面类型
     * @param slug           页面标识
     * @return 影响行数
     */
    int softDeletePage(@Param("inactiveStatus") String inactiveStatus,
                       @Param("assetType") String assetType,
                       @Param("slug") String slug);

    // ==================== 索引维护 ====================

    /**
     * 读取系统索引页内容。
     *
     * @param assetType Wiki 页面类型
     * @param tag       索引页固定标签 SYSTEM_INDEX
     * @param status    资产状态
     * @return 索引页，只含 content
     */
    List<Map<String, Object>> selectSystemIndex(@Param("assetType") String assetType,
                                                @Param("tag") String tag,
                                                @Param("status") String status);

    /**
     * 读取参与索引的页面元数据。
     *
     * @param assetType Wiki 页面类型
     * @param status    资产状态
     * @param tag       需要排除的标签 SYSTEM_INDEX
     * @param limit     最多条数
     * @return 页面元数据
     */
    List<Map<String, Object>> selectIndexablePages(@Param("assetType") String assetType,
                                                   @Param("status") String status,
                                                   @Param("tag") String tag,
                                                   @Param("limit") int limit);

    /**
     * 查找系统索引页主键，用于决定新增还是更新。
     *
     * @param assetType Wiki 页面类型
     * @param tag       索引页固定标签
     * @return 索引页（只含 asset_id）
     */
    List<Map<String, Object>> selectIndexPageId(@Param("assetType") String assetType,
                                                @Param("tag") String tag);

    /**
     * 新建系统索引页。
     *
     * @param assetType Wiki 页面类型
     * @param content   索引内容
     * @param tag       索引页固定标签
     * @param status    资产状态
     * @return 影响行数
     */
    int insertSystemIndex(@Param("assetType") String assetType,
                          @Param("content") String content,
                          @Param("tag") String tag,
                          @Param("status") String status);

    /**
     * 更新系统索引页内容。
     *
     * @param content 索引内容
     * @param assetId 索引页主键
     * @return 影响行数
     */
    int updateSystemIndexContent(@Param("content") String content,
                                 @Param("assetId") Long assetId);

    /**
     * 统计参与索引的页面数。
     *
     * @param assetType Wiki 页面类型
     * @param status    资产状态
     * @param tag       需排除的标签
     * @return 页面数
     */
    Integer countIndexablePages(@Param("assetType") String assetType,
                                @Param("status") String status,
                                @Param("tag") String tag);

    /**
     * 统计原始资料数（非 Wiki 页面）。
     *
     * @param assetType Wiki 页面类型
     * @param status    资产状态
     * @return 资料数
     */
    Integer countSourceAssets(@Param("assetType") String assetType,
                              @Param("status") String status);

    // ==================== 资料整理 ====================

    /**
     * 查询待整理的原始资产。
     *
     * @param assetId 资产主键
     * @param status  资产状态
     * @return 资产，不存在时为空列表
     */
    List<Map<String, Object>> selectSourceAsset(@Param("assetId") Long assetId,
                                                @Param("status") String status);

    /**
     * 按来源标签查找已整理的页面。
     *
     * @param assetType Wiki 页面类型
     * @param tag       来源标签，形如 SOURCE:123
     * @return 页面（只含 asset_id）
     */
    List<Map<String, Object>> selectPageBySourceTag(@Param("assetType") String assetType,
                                                    @Param("tag") String tag);

    /**
     * 新增整理后的 Wiki 页面。
     *
     * @param spaceId     所属知识空间
     * @param title       页面标题
     * @param assetType   Wiki 页面类型
     * @param domainName  业务域
     * @param description 摘要
     * @param content     正文
     * @param tag         来源标签
     * @param status      资产状态
     * @return 影响行数
     */
    int insertIngestedPage(@Param("spaceId") Long spaceId,
                           @Param("title") String title,
                           @Param("assetType") String assetType,
                           @Param("domainName") Object domainName,
                           @Param("description") String description,
                           @Param("content") String content,
                           @Param("tag") String tag,
                           @Param("status") String status);

    /**
     * 更新已整理过的 Wiki 页面。
     *
     * @param spaceId     所属知识空间
     * @param title       页面标题
     * @param domainName  业务域
     * @param description 摘要
     * @param content     正文
     * @param assetId     页面主键
     * @return 影响行数
     */
    int updateIngestedPage(@Param("spaceId") Long spaceId,
                           @Param("title") String title,
                           @Param("domainName") Object domainName,
                           @Param("description") String description,
                           @Param("content") String content,
                           @Param("assetId") Long assetId);

    /**
     * 按标签查找页面，用于分析知识沉淀页面。
     *
     * @param tag 标签，形如 ANALYSIS_MEMORY:1
     * @return 页面（只含 asset_id）
     */
    List<Map<String, Object>> selectPageByTag(@Param("tag") String tag);

    /**
     * 新增分析知识页面。
     *
     * @param spaceId     所属知识空间
     * @param title       页面标题
     * @param description 说明
     * @param content     正文
     * @param tag         标签
     * @param username    操作人
     * @return 影响行数
     */
    int insertKnowledgePage(@Param("spaceId") Object spaceId,
                            @Param("title") String title,
                            @Param("description") String description,
                            @Param("content") String content,
                            @Param("tag") String tag,
                            @Param("username") String username);

    /**
     * 更新分析知识页面。
     *
     * @param spaceId     所属知识空间
     * @param title       页面标题
     * @param description 说明
     * @param content     正文
     * @param username    操作人
     * @param assetId     页面主键
     * @return 影响行数
     */
    int updateKnowledgePage(@Param("spaceId") Object spaceId,
                            @Param("title") String title,
                            @Param("description") String description,
                            @Param("content") String content,
                            @Param("username") String username,
                            @Param("assetId") Long assetId);

    // ==================== 问答检索与 URL 来源 ====================

    /**
     * 读取单个资产的正文，用于问答时生成证据片段。
     *
     * @param assetId 资产主键
     * @return 资产（只含 content），不存在时为空列表
     */
    List<Map<String, Object>> selectAssetContent(@Param("assetId") Long assetId);

    /**
     * 问答检索：查询原始资料（非 Wiki 页面）。
     *
     * @param status     资产状态
     * @param assetScope 是否按单资产范围检索
     * @param pageType   Wiki 页面类型，用于排除整理后的页面
     * @param scopeId    单资产范围的主键
     * @param sourceTag  单资产范围的来源标签
     * @param spaceId    知识空间过滤，可为空
     * @param limit      最多条数
     * @return 资料列表
     */
    List<Map<String, Object>> selectQuerySources(@Param("status") String status,
                                                 @Param("assetScope") boolean assetScope,
                                                 @Param("pageType") String pageType,
                                                 @Param("scopeId") Long scopeId,
                                                 @Param("sourceTag") String sourceTag,
                                                 @Param("spaceId") Long spaceId,
                                                 @Param("limit") int limit);

    /**
     * 问答检索：查询 Wiki 页面（排除系统索引页）。
     *
     * @param status     资产状态
     * @param assetType  Wiki 页面类型
     * @param indexTag   系统索引页标签
     * @param assetScope 是否按单资产范围检索
     * @param scopeId    单资产范围的主键
     * @param sourceTag  单资产范围的来源标签
     * @param spaceId    知识空间过滤，可为空
     * @param limit      最多条数
     * @return 页面列表
     */
    List<Map<String, Object>> selectQueryPages(@Param("status") String status,
                                               @Param("assetType") String assetType,
                                               @Param("indexTag") String indexTag,
                                               @Param("assetScope") boolean assetScope,
                                               @Param("scopeId") Long scopeId,
                                               @Param("sourceTag") String sourceTag,
                                               @Param("spaceId") Long spaceId,
                                               @Param("limit") int limit);

    /**
     * 新增一条 URL 来源资料，等待后续整理。
     *
     * @param url 来源地址
     * @return 影响行数
     */
    int insertUrlSource(@Param("url") String url);

    // ==================== 资产图谱 ====================

    /**
     * 查询图谱节点：含页面正文前若干字符，用于解析页面间引用。
     *
     * @param status     资产状态
     * @param indexTag   系统索引页标签
     * @param spaceId    知识空间过滤，可为空
     * @param limit      最多条数
     * @param contentLen 正文截取长度
     * @return 资产列表
     */
    List<Map<String, Object>> selectGraphNodes(@Param("status") String status,
                                               @Param("indexTag") String indexTag,
                                               @Param("spaceId") Long spaceId,
                                               @Param("limit") int limit,
                                               @Param("contentLen") int contentLen);

    /**
     * 查询资产与分析记忆的关联，用于补充「分析依据」边。
     *
     * @param status   资产状态
     * @param pageType Wiki 页面类型
     * @param spaceId  知识空间过滤，可为空
     * @return 关联记录
     */
    List<Map<String, Object>> selectAnalysisMemoryLinks(@Param("status") String status,
                                                        @Param("pageType") String assetType,
                                                        @Param("spaceId") Long spaceId);

    // ==================== 分析上下文资料 ====================

    /**
     * 查询参与分析的业务资料（排除 URL 来源）。
     *
     * @param status  资产状态
     * @param spaceId 知识空间
     * @param limit   最多条数
     * @return 资料列表
     */
    List<Map<String, Object>> selectAnalyzeDocuments(@Param("status") String status,
                                                     @Param("spaceId") Long spaceId,
                                                     @Param("limit") int limit);

    /**
     * 查询参与分析的业务规则类资料。
     *
     * @param status  资产状态
     * @param spaceId 知识空间
     * @param types   规则类资产类型集合（大写匹配）
     * @param limit   最多条数
     * @return 资料列表
     */
    List<Map<String, Object>> selectAnalyzeRules(@Param("status") String status,
                                                 @Param("spaceId") Long spaceId,
                                                 @Param("types") List<String> types,
                                                 @Param("limit") int limit);
}

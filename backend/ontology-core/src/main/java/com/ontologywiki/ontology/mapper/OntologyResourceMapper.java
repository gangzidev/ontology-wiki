package com.ontologywiki.ontology.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 通用资源表数据访问。
 *
 * <p>本体 Wiki 的空间、资产、数据源、分析任务、配置草案、导入记录六类资源结构相近，
 * 统一通过本 Mapper 完成分页查询与增删改。
 *
 * <p>注意：表名、主键列名、查询列名均来自服务端 {@code OntologyResourceService} 的白名单常量，
 * 不会直接使用前端传入值，因此这里使用 {@code ${}} 拼接是安全的。
 */
@Mapper
public interface OntologyResourceMapper {

    /**
     * 统计整表记录数，用于工作台统计卡片。
     *
     * @param table 表名（服务端白名单）
     * @return 记录数
     */
    Long countAll(@Param("table") String table);

    /**
     * 统计满足筛选条件的记录数。
     *
     * @param table             表名
     * @param excludeSystemIndex 是否排除系统索引页（仅 wiki_asset 使用）
     * @param spaceId           知识空间过滤，可为空
     * @param taskId            分析任务过滤，可为空
     * @param keyword           关键字，可为空
     * @param searchColumns     参与关键字匹配的列
     * @return 记录数
     */
    long countPage(@Param("table") String table,
                   @Param("excludeSystemIndex") boolean excludeSystemIndex,
                   @Param("spaceId") Long spaceId,
                   @Param("taskId") Long taskId,
                   @Param("keyword") String keyword,
                   @Param("searchColumns") String searchColumns);

    /**
     * 分页查询资源列表。
     *
     * @param table              表名
     * @param selectColumns      查询列（含主键、业务列与审计列）
     * @param idColumn           主键列
     * @param excludeSystemIndex 是否排除系统索引页
     * @param spaceId            知识空间过滤
     * @param taskId             分析任务过滤
     * @param keyword            关键字
     * @param searchColumns      参与关键字匹配的列
     * @param limit              每页条数
     * @param offset             偏移量
     * @return 记录列表
     */
    List<Map<String, Object>> selectPage(@Param("table") String table,
                                         @Param("selectColumns") String selectColumns,
                                         @Param("idColumn") String idColumn,
                                         @Param("excludeSystemIndex") boolean excludeSystemIndex,
                                         @Param("spaceId") Long spaceId,
                                         @Param("taskId") Long taskId,
                                         @Param("keyword") String keyword,
                                         @Param("searchColumns") String searchColumns,
                                         @Param("limit") int limit,
                                         @Param("offset") long offset);

    /**
     * 按主键查询单条记录。
     *
     * @param table         表名
     * @param selectColumns 查询列
     * @param idColumn      主键列
     * @param id            主键值
     * @return 记录列表，最多一条
     */
    List<Map<String, Object>> selectById(@Param("table") String table,
                                         @Param("selectColumns") String selectColumns,
                                         @Param("idColumn") String idColumn,
                                         @Param("id") long id);

    /**
     * 按主键查询指定列的值，用于跨表校验（如取 space_id）。
     *
     * @param table    表名
     * @param column   目标列
     * @param idColumn 主键列
     * @param id       主键值
     * @return 目标列值，记录不存在时返回 null
     */
    Object selectColumnById(@Param("table") String table,
                            @Param("column") String column,
                            @Param("idColumn") String idColumn,
                            @Param("id") long id);

    /**
     * 插入一条资源记录。
     *
     * <p>由于列集合在运行期由前端字段决定，这里通过 Map 迭代拼装列名与占位符。
     * 列名已在 Service 层做白名单过滤，因此使用 {@code ${}} 是安全的。
     *
     * @param params 参数集合：table、idColumn、data、generatedId（输出）
     * @return 影响行数
     */
    int insert(Map<String, Object> params);

    /**
     * 按主键更新资源记录。
     *
     * @param params 参数集合：table、idColumn、id、data
     * @return 影响行数
     */
    int update(Map<String, Object> params);

    /**
     * 按主键批量删除资源记录。
     *
     * @param table    表名
     * @param idColumn 主键列
     * @param ids      主键集合
     * @return 影响行数
     */
    int deleteByIds(@Param("table") String table,
                    @Param("idColumn") String idColumn,
                    @Param("ids") List<Long> ids);

    /**
     * 按主键批量查询主键与名称，用于列表页补充来源任务名称。
     *
     * @param ids 主键集合
     * @return 主键与名称
     */
    List<Map<String, Object>> selectTaskNames(@Param("ids") List<Long> ids);

    // ==================== 跨表校验与行业空间 ====================

    /**
     * 统计启用状态的行业知识空间数量，用于校验归属空间是否可用。
     *
     * @param spaceId 知识空间主键
     * @param status  期望状态（'0' 表示启用）
     * @return 命中数量
     */
    int countEnabledSpace(@Param("spaceId") Long spaceId, @Param("status") String status);

    /**
     * 读取草案的类型与配置内容，用于判断内容是否发生变化。
     *
     * @param proposalId 草案主键
     * @return 草案（只含 proposal_type 与 config_json）
     */
    List<Map<String, Object>> selectProposalSnapshot(@Param("proposalId") Long proposalId);

    /**
     * 把草案重置为待审核，清空原有审核结论。
     *
     * @param proposalId 草案主键
     * @param status     重置后的状态
     * @return 影响行数
     */
    int resetProposalReview(@Param("proposalId") Long proposalId, @Param("status") String status);

    /**
     * 回写数据源加密后的密码。
     *
     * @param datasourceId 数据源主键
     * @param passwordEnc  加密后的密码
     * @param username     操作人
     * @return 影响行数
     */
    int updateDatasourcePassword(@Param("datasourceId") Long datasourceId,
                                 @Param("passwordEnc") String passwordEnc,
                                 @Param("username") String username);
}

package com.ontologywiki.ontology.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 分析知识记忆（{@code ontology_analysis_memory}）数据访问。
 *
 * <p>用于保存分析答案的草稿与确认版本，并为后续分析、分析问答提供已确认知识上下文。
 */
@Mapper
public interface AnalysisMemoryMapper {

    /**
     * 读取分析任务的名称与所属知识空间，用于生成记忆标题和知识页面。
     *
     * @param taskId 任务主键
     * @return 任务信息
     */
    Map<String, Object> selectTaskInfo(@Param("taskId") Long taskId);

    /**
     * 计算某任务下已有的最大版本号。
     *
     * @param taskId 任务主键
     * @return 最大版本号，无记录时返回 0
     */
    Integer selectMaxVersion(@Param("taskId") Long taskId);

    /**
     * 新增分析记忆记录。
     *
     * @param spaceId    知识空间
     * @param taskId     任务主键
     * @param proposalId 草案主键，可为空
     * @param title      标题
     * @param content    内容
     * @param sourceJson 来源信息 JSON
     * @param status     DRAFT 或 CONFIRMED
     * @param version    版本号
     * @param username   操作人
     * @param confirmed  是否为确认状态，决定审核人与审核时间是否写入
     * @param reviewTime 审核时间
     * @return 影响行数
     */
    int insertMemory(@Param("spaceId") Object spaceId,
                     @Param("taskId") Long taskId,
                     @Param("proposalId") Long proposalId,
                     @Param("title") String title,
                     @Param("content") String content,
                     @Param("sourceJson") String sourceJson,
                     @Param("status") String status,
                     @Param("version") int version,
                     @Param("username") String username,
                     @Param("confirmed") boolean confirmed,
                     @Param("reviewTime") Date reviewTime);

    /**
     * 插入后读取自增主键。
     *
     * @return 最近一次插入的主键
     */
    Long selectLastInsertId();

    /**
     * 回写分析任务摘要。
     *
     * @param summary  摘要内容
     * @param username 操作人
     * @param taskId   任务主键
     * @return 影响行数
     */
    int updateTaskSummary(@Param("summary") String summary,
                          @Param("username") String username,
                          @Param("taskId") Long taskId);

    /**
     * 查询某任务的全部记忆。
     *
     * @param taskId 任务主键
     * @return 记忆列表，按主键倒序
     */
    List<Map<String, Object>> selectMemoriesByTask(@Param("taskId") Long taskId);

    /**
     * 查询某任务的最新一条记忆。
     *
     * @param taskId 任务主键
     * @return 记忆列表，最多一条
     */
    List<Map<String, Object>> selectLatestMemory(@Param("taskId") Long taskId);

    /**
     * 查询已确认知识：任务与空间同时限定。
     *
     * @param status  记忆状态
     * @param taskId  任务主键
     * @param spaceId 知识空间
     * @param limit   最多条数
     * @return 已确认知识
     */
    List<Map<String, Object>> selectConfirmedByTaskAndSpace(@Param("status") String status,
                                                            @Param("taskId") Long taskId,
                                                            @Param("spaceId") Long spaceId,
                                                            @Param("limit") int limit);

    /**
     * 查询已确认知识：仅按任务限定。
     *
     * @param status 记忆状态
     * @param taskId 任务主键
     * @param limit  最多条数
     * @return 已确认知识
     */
    List<Map<String, Object>> selectConfirmedByTask(@Param("status") String status,
                                                    @Param("taskId") Long taskId,
                                                    @Param("limit") int limit);

    /**
     * 查询已确认知识：仅按空间限定。
     *
     * @param status  记忆状态
     * @param spaceId 知识空间
     * @param limit   最多条数
     * @return 已确认知识
     */
    List<Map<String, Object>> selectConfirmedBySpace(@Param("status") String status,
                                                     @Param("spaceId") Long spaceId,
                                                     @Param("limit") int limit);
}

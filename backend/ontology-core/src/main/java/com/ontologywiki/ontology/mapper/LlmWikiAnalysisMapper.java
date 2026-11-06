package com.ontologywiki.ontology.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * LLM Wiki 分析链路数据访问。
 *
 * <p>覆盖「分析答案沉淀」与「分析问答上下文」所需的读写：
 * <ul>
 *   <li>分析任务与配置草案的读取；</li>
 *   <li>分析记忆（草稿 / 确认 / 结构化知识条目）的写入与查询；</li>
 *   <li>任务摘要与草案内容的回写。</li>
 * </ul>
 */
@Mapper
public interface LlmWikiAnalysisMapper {

    // ==================== 任务与草案 ====================

    /**
     * 读取分析任务的名称与所属知识空间。
     *
     * @param taskId 任务主键
     * @return 任务信息
     */
    Map<String, Object> selectTaskInfo(@Param("taskId") Long taskId);

    /**
     * 读取分析任务绑定的知识空间。
     *
     * @param taskId 任务主键
     * @return 任务信息
     */
    Map<String, Object> selectTaskSpace(@Param("taskId") Long taskId);

    /**
     * 读取任务最新一条配置草案。
     *
     * @param taskId 任务主键
     * @return 草案（含 proposal_id 与 config_json），不存在时为空列表
     */
    List<Map<String, Object>> selectLatestProposal(@Param("taskId") Long taskId);

    /**
     * 读取任务下指定草案。
     *
     * @param taskId     任务主键
     * @param proposalId 草案主键
     * @return 草案（含 proposal_id 与 config_json），不匹配时为空列表
     */
    List<Map<String, Object>> selectProposal(@Param("taskId") Long taskId,
                                             @Param("proposalId") Long proposalId);

    /**
     * 读取草案的配置内容。
     *
     * @param taskId     任务主键
     * @param proposalId 草案主键
     * @return 草案（只含 config_json），不匹配时为空列表
     */
    List<Map<String, Object>> selectProposalConfig(@Param("taskId") Long taskId,
                                                   @Param("proposalId") Long proposalId);

    /**
     * 回写草案配置内容。
     *
     * @param proposalId 草案主键
     * @param configJson 配置 JSON
     * @param username   操作人
     * @return 影响行数
     */
    int updateProposalConfig(@Param("proposalId") Long proposalId,
                             @Param("configJson") String configJson,
                             @Param("username") String username);

    /**
     * 回写草案配置内容，并把草案重置为待审核。
     *
     * @param taskId     任务主键
     * @param proposalId 草案主键
     * @param configJson 配置 JSON
     * @param status     重置后的状态
     * @param username   操作人
     * @return 影响行数
     */
    int updateProposalConfigAndResetReview(@Param("taskId") Long taskId,
                                           @Param("proposalId") Long proposalId,
                                           @Param("configJson") String configJson,
                                           @Param("status") String status,
                                           @Param("username") String username);

    /**
     * 读取分析任务、数据源与最新草案的组合信息。
     *
     * @param taskId 任务主键
     * @return 组合信息
     */
    Map<String, Object> selectTaskAnalysisContext(@Param("taskId") Long taskId);

    /**
     * 读取分析任务、数据源与指定草案的组合信息。
     *
     * @param taskId     任务主键
     * @param proposalId 草案主键
     * @return 组合信息
     */
    Map<String, Object> selectTaskAnalysisContextByProposal(@Param("taskId") Long taskId,
                                                            @Param("proposalId") Long proposalId);

    /**
     * 回写任务摘要。
     *
     * @param taskId   任务主键
     * @param summary  摘要
     * @param username 操作人
     * @return 影响行数
     */
    int updateTaskSummary(@Param("taskId") Long taskId,
                          @Param("summary") String summary,
                          @Param("username") String username);

    // ==================== 分析记忆 ====================

    /**
     * 读取某任务下已有的最大版本号。
     *
     * @param taskId 任务主键
     * @return 最大版本号
     */
    Integer selectMaxVersion(@Param("taskId") Long taskId);

    /**
     * 新增一条分析答案记忆。
     *
     * @param spaceId    知识空间
     * @param taskId     任务主键
     * @param proposalId 草案主键，可为空
     * @param title      标题
     * @param content    正文
     * @param sourceJson 来源 JSON
     * @param status     DRAFT 或 CONFIRMED
     * @param version    版本号
     * @param username   操作人
     * @param confirmed  是否确认，决定审核人是否写入
     * @return 影响行数
     */
    int insertAnswerMemory(@Param("spaceId") Object spaceId,
                           @Param("taskId") Long taskId,
                           @Param("proposalId") Long proposalId,
                           @Param("title") String title,
                           @Param("content") String content,
                           @Param("sourceJson") String sourceJson,
                           @Param("status") String status,
                           @Param("version") int version,
                           @Param("username") String username,
                           @Param("confirmed") boolean confirmed);

    /**
     * 新增一条结构化知识条目。
     *
     * @param spaceId    知识空间
     * @param taskId     任务主键
     * @param proposalId 草案主键，可为空
     * @param title      标题
     * @param content    正文
     * @param keywords   检索关键词
     * @param sourceJson 来源 JSON
     * @param version    版本号
     * @param username   操作人
     * @return 影响行数
     */
    int insertKnowledgeItem(@Param("spaceId") Object spaceId,
                            @Param("taskId") Long taskId,
                            @Param("proposalId") Long proposalId,
                            @Param("title") String title,
                            @Param("content") String content,
                            @Param("keywords") String keywords,
                            @Param("sourceJson") String sourceJson,
                            @Param("version") int version,
                            @Param("username") String username);

    /**
     * 查询任务的全部记忆。
     *
     * @param taskId 任务主键
     * @return 记忆列表
     */
    List<Map<String, Object>> selectMemoriesByTask(@Param("taskId") Long taskId);

    /**
     * 查询任务的最新一条记忆。
     *
     * @param taskId 任务主键
     * @return 记忆列表，最多一条
     */
    List<Map<String, Object>> selectLatestMemory(@Param("taskId") Long taskId);

    /**
     * 查询已确认知识：任务与空间同时限定。
     *
     * @param status     记忆状态
     * @param memoryType 记忆类型
     * @param taskId     任务主键
     * @param spaceId    知识空间
     * @param limit      最多条数
     * @return 已确认知识
     */
    List<Map<String, Object>> selectConfirmedItemsByTaskAndSpace(@Param("status") String status,
                                                                 @Param("memoryType") String memoryType,
                                                                 @Param("taskId") Long taskId,
                                                                 @Param("spaceId") Long spaceId,
                                                                 @Param("limit") int limit);

    /**
     * 查询已确认知识：仅按任务限定。
     *
     * @param status     记忆状态
     * @param memoryType 记忆类型
     * @param taskId     任务主键
     * @param limit      最多条数
     * @return 已确认知识
     */
    List<Map<String, Object>> selectConfirmedItemsByTask(@Param("status") String status,
                                                         @Param("memoryType") String memoryType,
                                                         @Param("taskId") Long taskId,
                                                         @Param("limit") int limit);

    /**
     * 查询已确认知识：仅按空间限定。
     *
     * @param status     记忆状态
     * @param memoryType 记忆类型
     * @param spaceId    知识空间
     * @param limit      最多条数
     * @return 已确认知识
     */
    List<Map<String, Object>> selectConfirmedItemsBySpace(@Param("status") String status,
                                                          @Param("memoryType") String memoryType,
                                                          @Param("spaceId") Long spaceId,
                                                          @Param("limit") int limit);

    // ==================== 模型配置 ====================

    /**
     * 读取启用配置，用于判断是否存在可用模型。
     *
     * @param enabled 启用标记
     * @return 配置列表
     */
    List<Map<String, Object>> selectEnabledSettings(@Param("enabled") int enabled);

    /**
     * 按使用场景读取最新一条启用配置。
     *
     * @param enabled 启用标记
     * @param scene   使用场景
     * @return 配置列表，最多一条
     */
    List<Map<String, Object>> selectEnabledSettingByScene(@Param("enabled") int enabled,
                                                          @Param("scene") String scene);

    // ==================== 问答日志 ====================

    /**
     * 读取最近的问答日志。
     *
     * @param limit 最多条数
     * @return 问答消息列表
     */
    List<Map<String, Object>> selectRecentChatMessages(@Param("limit") int limit);
}

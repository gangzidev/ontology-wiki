package com.ontologywiki.ontology.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 业务知识问答会话数据访问。
 *
 * <p>覆盖问答会话、问答消息与引用关系，以及问答前需要的范围校验查询。
 */
@Mapper
public interface WikiChatMapper {

    // ==================== 范围校验 ====================

    /**
     * 读取资产的所属知识空间。
     *
     * @param assetId 资产主键
     * @param status  资产状态
     * @return 资产信息，不存在时为空列表
     */
    List<Map<String, Object>> selectAssetSpace(@Param("assetId") Long assetId,
                                               @Param("status") String status);

    /**
     * 读取分析任务绑定的知识空间。
     *
     * @param taskId 任务主键
     * @return 任务信息，不存在时为空列表
     */
    List<Map<String, Object>> selectTaskSpace(@Param("taskId") Long taskId);

    /**
     * 校验知识空间是否存在且启用。
     *
     * @param spaceId 知识空间主键
     * @param status  启用状态值
     * @return 命中数量
     */
    int countEnabledSpace(@Param("spaceId") Long spaceId, @Param("status") String status);

    // ==================== 会话 ====================

    /**
     * 读取会话的范围与上下文状态。
     *
     * @param sessionId 会话标识
     * @param username  创建人
     * @return 会话信息，不存在时为空列表
     */
    List<Map<String, Object>> selectSession(@Param("sessionId") String sessionId,
                                            @Param("username") String username);

    /**
     * 新增会话。
     *
     * @param sessionId 会话标识
     * @param spaceId   知识空间，可为空
     * @param scopeType 范围类型
     * @param scopeId   范围主键，可为空
     * @param title     会话标题
     * @param username  创建人
     * @return 影响行数
     */
    int insertSession(@Param("sessionId") String sessionId,
                      @Param("spaceId") Long spaceId,
                      @Param("scopeType") String scopeType,
                      @Param("scopeId") Long scopeId,
                      @Param("title") String title,
                      @Param("username") String username);

    /**
     * 刷新会话更新时间。
     *
     * @param sessionId 会话标识
     * @param username  创建人
     * @return 影响行数
     */
    int touchSession(@Param("sessionId") String sessionId, @Param("username") String username);

    /**
     * 回写会话的上下文状态。
     *
     * @param sessionId    会话标识
     * @param contextState 上下文状态 JSON
     * @param username     创建人
     * @return 影响行数
     */
    int updateSessionContext(@Param("sessionId") String sessionId,
                             @Param("contextState") String contextState,
                             @Param("username") String username);

    /**
     * 列出当前用户的会话。
     *
     * @param username 创建人
     * @param limit    最多条数
     * @return 会话列表
     */
    List<Map<String, Object>> listSessions(@Param("username") String username, @Param("limit") int limit);

    // ==================== 消息 ====================

    /**
     * 读取最近的对话历史，按主键倒序。
     *
     * @param sessionId 会话标识
     * @param username  创建人
     * @param limit     最多条数
     * @return 消息列表
     */
    List<Map<String, Object>> selectRecentMessages(@Param("sessionId") String sessionId,
                                                   @Param("username") String username,
                                                   @Param("limit") int limit);

    /**
     * 新增一条消息。
     *
     * @param sessionId 会话标识
     * @param role      角色（user / assistant）
     * @param content   内容
     * @param username  创建人
     * @return 影响行数
     */
    int insertMessage(@Param("sessionId") String sessionId,
                      @Param("role") String role,
                      @Param("content") String content,
                      @Param("username") String username);

    /**
     * 新增一条消息并回填自增主键。
     *
     * @param params 参数集合：sessionId、role、content、username、generatedId（输出）
     * @return 影响行数
     */
    int insertMessageWithKey(Map<String, Object> params);

    /**
     * 读取会话消息（只含主键、角色、内容与时间）。
     *
     * @param sessionId 会话标识
     * @param username  创建人
     * @param limit     最多条数
     * @return 消息列表
     */
    List<Map<String, Object>> selectSessionMessages(@Param("sessionId") String sessionId,
                                                    @Param("username") String username,
                                                    @Param("limit") int limit);

    // ==================== 引用 ====================

    /**
     * 记录消息引用的资产。
     *
     * @param messageId 消息主键
     * @param assetId   资产主键
     * @return 影响行数
     */
    int insertCitation(@Param("messageId") Long messageId, @Param("assetId") Long assetId);

    /**
     * 批量查询消息引用的资产。
     *
     * @param messageIds 消息主键集合
     * @return 引用关系
     */
    List<Map<String, Object>> selectCitations(@Param("messageIds") List<Long> messageIds);
}

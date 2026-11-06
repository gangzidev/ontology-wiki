package com.ontologywiki.ontology.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * MCP 端点数据访问。
 *
 * <p>覆盖两类查询：
 * <ul>
 *   <li>令牌校验：按令牌哈希查启用令牌，并刷新最近使用时间；</li>
 *   <li>工具查询：搜索 Wiki 资产、列知识空间、列分析任务、读任务状态与草案内容。</li>
 * </ul>
 */
@Mapper
public interface McpMapper {

    // ==================== 令牌校验 ====================

    /**
     * 按令牌哈希查询启用状态的令牌。
     *
     * @param tokenHash 令牌的 SHA-256 十六进制摘要
     * @param status    令牌状态，1 表示启用
     * @return 令牌记录（只含 token_id），未命中时为空列表
     */
    List<Map<String, Object>> selectEnabledToken(@Param("tokenHash") String tokenHash,
                                                 @Param("status") int status);

    /**
     * 刷新令牌的最近使用时间。
     *
     * @param tokenId 令牌主键
     * @return 影响行数
     */
    int touchToken(@Param("tokenId") Long tokenId);

    // ==================== 工具查询 ====================

    /**
     * 在指定知识空间内按关键字搜索 Wiki 资产。
     *
     * @param spaceId 知识空间主键
     * @param status  资产状态
     * @param like    已转义并带通配符的关键字
     * @param limit   最多条数
     * @return 资产摘要列表
     */
    List<Map<String, Object>> searchAssetsInSpace(@Param("spaceId") Long spaceId,
                                                  @Param("status") String status,
                                                  @Param("like") String like,
                                                  @Param("limit") int limit);

    /**
     * 在全部知识空间内按关键字搜索 Wiki 资产。
     *
     * @param status 资产状态
     * @param like   已转义并带通配符的关键字
     * @param limit  最多条数
     * @return 资产摘要列表
     */
    List<Map<String, Object>> searchAssets(@Param("status") String status,
                                           @Param("like") String like,
                                           @Param("limit") int limit);

    /**
     * 列出启用状态的行业知识空间。
     *
     * @param status 空间状态
     * @return 知识空间列表
     */
    List<Map<String, Object>> listSpaces(@Param("status") String status);

    /**
     * 按状态列出分析任务。
     *
     * @param status 任务状态
     * @param limit  最多条数
     * @return 任务列表
     */
    List<Map<String, Object>> listTasksByStatus(@Param("status") String status,
                                                @Param("limit") int limit);

    /**
     * 列出全部分析任务。
     *
     * @param limit 最多条数
     * @return 任务列表
     */
    List<Map<String, Object>> listTasks(@Param("limit") int limit);

    /**
     * 查询单个分析任务的状态信息。
     *
     * @param taskId 任务主键
     * @return 任务信息，不存在时为空列表
     */
    List<Map<String, Object>> selectTaskStatus(@Param("taskId") Long taskId);

    /**
     * 查询分析任务的主键与状态，用于触发前的前置校验。
     *
     * @param taskId 任务主键
     * @return 任务主键与状态，不存在时为空列表
     */
    List<Map<String, Object>> selectTaskIdAndStatus(@Param("taskId") Long taskId);

    /**
     * 读取任务最新一条配置草案的配置内容。
     *
     * @param taskId 任务主键
     * @return 草案（只含 config_json），不存在时为空列表
     */
    List<Map<String, Object>> selectLatestProposalConfig(@Param("taskId") Long taskId);

    // ==================== 令牌管理 ====================

    /**
     * 列出当前用户创建的令牌。
     *
     * @param username 创建人
     * @return 令牌列表
     */
    List<Map<String, Object>> listTokens(@Param("username") String username);

    /**
     * 新增令牌，只保存哈希。
     *
     * @param tokenName 令牌名称
     * @param tokenHash 令牌的 SHA-256 摘要
     * @param username  创建人
     * @return 影响行数
     */
    int insertToken(@Param("tokenName") String tokenName,
                    @Param("tokenHash") String tokenHash,
                    @Param("username") String username);

    /**
     * 删除当前用户创建的令牌。
     *
     * @param tokenId  令牌主键
     * @param username 创建人
     * @return 影响行数
     */
    int deleteToken(@Param("tokenId") Long tokenId, @Param("username") String username);
}

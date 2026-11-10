package com.ontologywiki.ontology.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 本体分析链路数据访问（Controller 层使用）。
 *
 * <p>覆盖分析任务、配置草案、数据源及导入记录写入：
 * <ul>
 *   <li>分析任务：读取、状态流转、进度回写；</li>
 *   <li>配置草案：完成标记、导出、版本对比与上下文读取；</li>
 *   <li>数据源：读取与凭证回写；</li>
 *   <li>导入记录：保存本体平台导入结果。</li>
 * </ul>
 */
@Mapper
public interface OntologyAnalysisMapper {

    // ==================== 分析任务 ====================

    /**
     * 读取分析任务全量字段。
     *
     * @param taskId 任务主键
     * @return 任务信息
     */
    Map<String, Object> selectTask(@Param("taskId") Long taskId);

    /**
     * 把任务置为执行中，仅当当前不是执行中时生效。
     *
     * @param taskId   任务主键
     * @param summary  进度摘要
     * @param username 操作人
     * @param running  执行中状态值
     * @return 影响行数，0 表示已在执行
     */
    int markTaskRunning(@Param("taskId") Long taskId,
                        @Param("summary") String summary,
                        @Param("username") String username,
                        @Param("running") String running);

    /**
     * 回写执行中任务的进度。
     *
     * @param taskId   任务主键
     * @param summary  进度摘要
     * @param username 操作人
     * @param running  执行中状态值
     * @return 影响行数
     */
    int updateRunningProgress(@Param("taskId") Long taskId,
                              @Param("summary") String summary,
                              @Param("username") String username,
                              @Param("running") String running);

    /**
     * 标记任务完成。
     *
     * @param taskId   任务主键
     * @param summary  结果摘要
     * @param username 操作人
     * @param done     完成状态值
     * @return 影响行数
     */
    int markTaskFinished(@Param("taskId") Long taskId,
                         @Param("summary") String summary,
                         @Param("username") String username,
                         @Param("done") String done);

    /**
     * 标记任务失败。
     *
     * @param taskId   任务主键
     * @param summary  失败原因
     * @param username 操作人
     * @param failed   失败状态值
     * @return 影响行数
     */
    int markTaskFailed(@Param("taskId") Long taskId,
                       @Param("summary") String summary,
                       @Param("username") String username,
                       @Param("failed") String failed);

    // ==================== 数据源 ====================

    /**
     * 读取数据源连接信息（含加密密码），用于连接测试与扫描。
     *
     * @param datasourceId 数据源主键
     * @return 数据源信息
     */
    Map<String, Object> selectDatasource(@Param("datasourceId") Long datasourceId);

    /**
     * 回写数据源密码密文。
     *
     * @param datasourceId 数据源主键
     * @param passwordEnc  密文
     * @param username     操作人
     * @return 影响行数
     */
    int updateDatasourceCredential(@Param("datasourceId") Long datasourceId,
                                   @Param("passwordEnc") String passwordEnc,
                                   @Param("username") String username);

    // ==================== 配置草案 ====================

    /** 草案仅可从待修正状态标记为完成。 */
    int completeProposal(@Param("proposalId") Long proposalId,
                       @Param("status") String status,
                       @Param("comment") String comment,
                       @Param("username") String username,
                       @Param("draft") String draft);

    /**
     * 读取草案全量字段，用于导入前校验。
     *
     * @param proposalId 草案主键
     * @return 草案信息
     */
    Map<String, Object> selectProposal(@Param("proposalId") Long proposalId);

    /**
     * 读取草案的导出信息。
     *
     * @param proposalId 草案主键
     * @return 草案名称、配置与来源任务
     */
    Map<String, Object> selectProposalForExport(@Param("proposalId") Long proposalId);

    /**
     * 读取草案的结构差异信息。
     *
     * @param proposalId 草案主键
     * @return 草案主键、名称、任务与配置
     */
    List<Map<String, Object>> selectProposalForDiff(@Param("proposalId") Long proposalId);

    /**
     * 读取同一任务下更早的一版草案。
     *
     * @param taskId     任务主键
     * @param proposalId 当前草案主键
     * @return 上一版草案，最多一条
     */
    List<Map<String, Object>> selectPreviousProposal(@Param("taskId") Long taskId,
                                                     @Param("proposalId") Long proposalId);

    /**
     * 读取任务最新一版草案主键。
     *
     * @param taskId 任务主键
     * @return 草案主键
     */
    List<Map<String, Object>> selectLatestProposalId(@Param("taskId") Long taskId);

    /**
     * 读取任务最新一版草案的配置。
     *
     * @param taskId 任务主键
     * @return 草案主键与配置
     */
    List<Map<String, Object>> selectLatestProposalWithConfig(@Param("taskId") Long taskId);

    /**
     * 读取任务上下文：任务摘要 + 最新草案。
     *
     * @param taskId 任务主键
     * @return 任务与草案组合信息
     */
    List<Map<String, Object>> selectTaskContext(@Param("taskId") Long taskId);

    /**
     * 读取任务上下文：任务摘要 + 指定草案。
     *
     * @param taskId     任务主键
     * @param proposalId 草案主键
     * @return 任务与草案组合信息
     */
    List<Map<String, Object>> selectTaskContextByProposal(@Param("taskId") Long taskId,
                                                          @Param("proposalId") Long proposalId);

    /**
     * 校验草案是否属于指定分析任务。
     *
     * @param proposalId 草案主键
     * @param taskId     任务主键
     * @return 命中数量
     */
    int countProposalOfTask(@Param("proposalId") Long proposalId, @Param("taskId") Long taskId);

    /**
     * 把草案置为已导入。
     *
     * @param proposalId 草案主键
     * @param username   操作人
     * @param imported   已导入状态值
     * @return 影响行数
     */
    int markProposalImported(@Param("proposalId") Long proposalId,
                             @Param("username") String username,
                             @Param("imported") String imported);

    /**
     * 新增配置草案并回填自增主键。
     *
     * @param params 参数集合：taskId、proposalName、configJson、username、generatedId（输出）
     * @return 影响行数
     */
    int insertProposal(Map<String, Object> params);

    /**
     * 读取任务关联数据源的基本信息，用于导出工程包。
     *
     * @param taskId 任务主键
     * @return 数据源名称、schema 与类型
     */
    List<Map<String, Object>> selectTaskDatasource(@Param("taskId") Long taskId);

    // ==================== 导入记录 ====================

    /**
     * 新增导入记录。
     *
     * @param proposalId   草案主键
     * @param targetSystem 目标系统
     * @param status       结果状态
     * @param detail       明细
     * @param username     操作人
     * @return 影响行数
     */
    int insertImportRecord(@Param("proposalId") Long proposalId,
                           @Param("targetSystem") String targetSystem,
                           @Param("status") String status,
                           @Param("detail") String detail,
                           @Param("username") String username);

}

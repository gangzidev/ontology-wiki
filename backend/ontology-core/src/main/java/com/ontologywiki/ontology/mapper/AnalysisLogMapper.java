package com.ontologywiki.ontology.mapper;

import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Map;

public interface AnalysisLogMapper {
    /**
     * 插入分析日志
     */
    int insertLog(Map<String, Object> log);

    /**
     * 查询执行批次的日志列表
     */
    List<Map<String, Object>> selectLogs(@Param("taskId") long taskId, @Param("runToken") String runToken);

    /**
     * 查询最近N条日志
     */
    List<Map<String, Object>> selectRecentLogs(@Param("taskId") long taskId, @Param("runToken") String runToken, @Param("limit") int limit);
}

package com.ontologywiki.ontology.mapper;

import org.apache.ibatis.annotations.Param;
import java.util.Map;
import java.util.List;

public interface AnalysisJobMapper {
    Map<String,Object> lockTask(@Param("taskId") long taskId);
    Map<String,Object> selectJob(@Param("taskId") long taskId);
    Map<String,Object> lockJob(@Param("taskId") long taskId);
    int insertJob(Map<String,Object> job);
    int claimJob(Map<String,Object> job);
    int insertRun(Map<String,Object> job);
    int finishRun(Map<String,Object> job);
    int insertModelCall(Map<String,Object> call);
    int insertStageTiming(Map<String,Object> timing);
    List<Map<String,Object>> selectRuns(@Param("taskId") long taskId, @Param("before") Long before,
                                       @Param("limit") int limit, @Param("now") long now);
    Map<String,Object> selectRunById(@Param("taskId") long taskId, @Param("runId") long runId);
    List<Map<String,Object>> selectModelCalls(@Param("taskId") long taskId, @Param("runId") long runId);
    List<Map<String,Object>> selectStageTimings(@Param("taskId") long taskId, @Param("runId") long runId);
    int checkpoint(Map<String,Object> job);
    int finish(Map<String,Object> job);
    int updateTask(@Param("taskId") long taskId, @Param("status") String status, @Param("message") String message, @Param("username") String username);
}

package com.ontologywiki.ontology.mapper;

import org.apache.ibatis.annotations.Param;
import java.util.Map;

public interface AnalysisJobMapper {
    Map<String,Object> lockTask(@Param("taskId") long taskId);
    Map<String,Object> selectJob(@Param("taskId") long taskId);
    Map<String,Object> lockJob(@Param("taskId") long taskId);
    int insertJob(Map<String,Object> job);
    int claimJob(Map<String,Object> job);
    int checkpoint(Map<String,Object> job);
    int finish(Map<String,Object> job);
    int updateTask(@Param("taskId") long taskId, @Param("status") String status, @Param("message") String message, @Param("username") String username);
}

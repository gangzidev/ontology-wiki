package com.ontologywiki.ontology.service.analysis;

import java.util.Map;

public interface IAnalysisJobService {
    Map<String,Object> start(long taskId, Map<String,String> request, String username);
    Map<String,Object> resume(long taskId, String username);
    Map<String,Object> cancel(long taskId, String username);
    Map<String,Object> status(long taskId);
    Map<String,Object> history(long taskId, Long before, int limit);
    java.util.List<Map<String,Object>> modelCalls(long taskId, long runId);
    java.util.List<Map<String,Object>> stageTimings(long taskId, long runId);
    java.util.List<Map<String,Object>> logs(long taskId, long runId);
    Map<String,Object> runtimeMetrics();
}

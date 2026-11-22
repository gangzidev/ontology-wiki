package com.ontologywiki.ontology.service.analysis;

import java.util.Map;

public interface IAnalysisJobService {
    Map<String,Object> start(long taskId, Map<String,String> request, String username);
    Map<String,Object> resume(long taskId, String username);
    Map<String,Object> cancel(long taskId, String username);
    Map<String,Object> status(long taskId);
}

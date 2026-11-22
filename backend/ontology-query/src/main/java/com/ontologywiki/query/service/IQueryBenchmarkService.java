package com.ontologywiki.query.service;

import java.util.Map;

public interface IQueryBenchmarkService {
    Map<String, Object> evaluate(long proposalId, Map<String, Object> request, String username);
}

package com.ontologywiki.query.service;

import com.ontologywiki.query.domain.QueryResult;

import java.util.Map;

public interface IQueryExecutionService {
    QueryResult execute(long proposalId, Map<String, Object> plan);
    QueryResult executeForEvaluation(long proposalId, Map<String, Object> plan);
}

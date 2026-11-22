package com.ontologywiki.query.service;

import com.ontologywiki.query.compiler.QuerySqlCompiler;
import com.ontologywiki.query.domain.QueryResult;
import com.ontologywiki.query.policy.QueryReadinessService;

import java.util.Map;

public interface IDataQueryService {
    QueryReadinessService.Readiness readiness(long proposalId);
    QuerySqlCompiler.Result compile(long proposalId, Map<String, Object> plan);
    Map<String, Object> plan(long proposalId, String question);
    QueryResult execute(long proposalId, Map<String, Object> plan);
    Map<String, Object> evaluate(long proposalId, Map<String, Object> benchmark, String username);
    Map<String, Object> ask(long proposalId, Map<String, Object> request, String username);
    Map<String, Object> drilldown(long proposalId, Map<String, Object> request, String username);
    Map<String, Object> catalog(long proposalId);
}

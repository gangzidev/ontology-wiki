package com.ontologywiki.query.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ontologywiki.query.comparison.QueryComparisonCalculator;
import com.ontologywiki.query.compiler.QuerySqlCompiler;
import com.ontologywiki.query.dao.BusinessQueryDao;
import com.ontologywiki.query.domain.QueryResult;
import com.ontologywiki.query.policy.QueryReadinessService;
import com.ontologywiki.query.policy.QueryPolicyService;
import com.ontologywiki.query.presentation.QueryResultExplainer;
import com.ontologywiki.query.repository.QueryConfigurationRepository;
import com.ontologywiki.query.service.IQueryExecutionService;
import com.ontologywiki.query.support.QueryConfigHash;
import com.ontologywiki.query.validation.QueryPlanValidator;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class QueryExecutionServiceImpl implements IQueryExecutionService {
    private final QueryConfigurationRepository repository;
    private final QueryReadinessService readiness;
    private final QueryPolicyService policy;
    private final QuerySqlCompiler compiler;
    private final BusinessQueryDao businessQueryDao;
    private final ObjectMapper json;
    private final QueryComparisonCalculator comparisonCalculator = new QueryComparisonCalculator();
    private final QueryResultExplainer explainer = new QueryResultExplainer();

    public QueryExecutionServiceImpl(QueryConfigurationRepository repository, QueryReadinessService readiness,
                                     QueryPolicyService policy, QuerySqlCompiler compiler, BusinessQueryDao businessQueryDao, ObjectMapper json) {
        this.repository = repository;
        this.readiness = readiness;
        this.policy = policy;
        this.compiler = compiler;
        this.businessQueryDao = businessQueryDao;
        this.json = json;
    }

    public QueryResult execute(long proposalId, Map<String, Object> plan) {
        return execute(proposalId, plan, null);
    }

    @Override
    public QueryResult execute(long proposalId, Map<String, Object> plan, String configHash) {
        policy.requireExecution(proposalId);
        return executeValidated(proposalId, plan, configHash);
    }

    public QueryResult executeForEvaluation(long proposalId, Map<String, Object> plan) {
        return execute(proposalId, plan);
    }

    private QueryResult executeValidated(long proposalId, Map<String, Object> plan, String configHash) {
        Map<String, Object> config = readiness.proposal(proposalId);
        if (configHash != null && !configHash.equals(QueryConfigHash.of(json, config))) {
            throw new IllegalArgumentException("查询配置已更新，请重新输入完整查询条件");
        }
        policy.validatePlan(plan, config);
        Map<String, Object> proposal = repository.findProposal(proposalId);
        Object taskId = proposal.get("task_id");
        if (!(taskId instanceof Number)) throw new IllegalArgumentException("配置草案没有关联分析任务");
        Map<String, Object> source = repository.findTaskDatasource(((Number) taskId).longValue());
        Map<String, Object> comparison = plan.get("comparison") instanceof Map ? (Map<String, Object>) plan.get("comparison") : Collections.<String, Object>emptyMap();
        String comparisonType = text(comparison.get("type")).toUpperCase(Locale.ROOT);
        Map<String, Object> currentPlan = new LinkedHashMap<>(plan);
        currentPlan.remove("comparison");
        QueryResult current = executeSingle(currentPlan, config, source);
        if (comparisonType.isEmpty() || "NONE".equals(comparisonType)) return current;
        int periods = comparison.get("periods") == null ? 1 : Integer.parseInt(String.valueOf(comparison.get("periods")));
        Map<String, Object> previousPlan = comparisonCalculator.previousPlan(currentPlan, comparisonType, periods);
        QueryResult previous = executeSingle(previousPlan, config, source);
        List<Map<String, Object>> mergedRows = comparisonCalculator.compareRows(current.getRows(), previous.getRows(), currentPlan, comparisonType, periods);
        Map<String, Object> explanation = new LinkedHashMap<>(current.getExplanation());
        explanation.put("comparison", comparisonType);
        explanation.put("comparisonPeriods", periods);
        return new QueryResult(mergedRows, current.getObjectIds(), current.getSql() + "\n-- comparison period --\n" + previous.getSql(),
                comparisonCalculator.concat(current.getParameters(), previous.getParameters()), current.getConfigHash(), explanation,
                Integer.parseInt(String.valueOf(plan.getOrDefault("limit", 100))));
    }

    private QueryResult executeSingle(Map<String, Object> plan, Map<String, Object> config, Map<String, Object> source) {
        policy.validatePlan(plan, config);
        QuerySqlCompiler.Result compiled = compiler.compile(plan, config, String.valueOf(source.get("db_type")));
        List<Map<String, Object>> rows = businessQueryDao.query(source, compiled);
        return new QueryResult(rows, compiled.getObjectIds(), compiled.getSql(), compiled.getParameters(),
                QueryConfigHash.of(json, config), explainer.explain(plan, config, compiled.getObjectIds()),
                Integer.parseInt(String.valueOf(plan.getOrDefault("limit", 100))));
    }

    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
}

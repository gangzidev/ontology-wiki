package com.ontologywiki.query.service.impl;

import com.ontologywiki.query.catalog.QuerySemanticCatalogService;
import com.ontologywiki.query.compiler.QuerySqlCompiler;
import com.ontologywiki.query.domain.QueryResult;
import com.ontologywiki.query.planning.QueryPlannerService;
import com.ontologywiki.query.policy.QueryReadinessService;
import com.ontologywiki.query.policy.QueryPolicyService;
import com.ontologywiki.query.presentation.QueryResponseAssembler;
import com.ontologywiki.query.repository.QueryConfigurationRepository;
import com.ontologywiki.query.service.IDataQueryService;
import com.ontologywiki.query.service.IQueryBenchmarkService;
import com.ontologywiki.query.service.IQueryExecutionService;
import com.ontologywiki.query.service.IQueryHistoryService;
import com.ontologywiki.query.validation.QueryPlanValidator;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Map;

@Service
public class DataQueryServiceImpl implements IDataQueryService {
    private final QueryReadinessService readiness;
    private final QueryConfigurationRepository repository;
    private final QueryPlannerService planner;
    private final QueryPolicyService policy;
    private final QuerySqlCompiler compiler;
    private final IQueryExecutionService execution;
    private final IQueryBenchmarkService benchmarks;
    private final IQueryHistoryService history;
    private final QuerySemanticCatalogService catalog;
    private final QueryResponseAssembler responses;

    public DataQueryServiceImpl(QueryReadinessService readiness, QueryConfigurationRepository repository,
                                QueryPlannerService planner, QueryPolicyService policy, QuerySqlCompiler compiler,
                                IQueryExecutionService execution, IQueryBenchmarkService benchmarks, IQueryHistoryService history,
                                QuerySemanticCatalogService catalog, QueryResponseAssembler responses) {
        this.readiness = readiness;
        this.repository = repository;
        this.planner = planner;
        this.policy = policy;
        this.compiler = compiler;
        this.execution = execution;
        this.benchmarks = benchmarks;
        this.history = history;
        this.catalog = catalog;
        this.responses = responses;
    }

    @Override
    public QueryReadinessService.Readiness readiness(long proposalId) { return readiness.check(proposalId); }

    @Override
    public QuerySqlCompiler.Result compile(long proposalId, Map<String, Object> plan) {
        QueryReadinessService.Readiness state = readiness.check(proposalId);
        if (!state.isEligibleForEvaluation()) throw new IllegalStateException("配置暂不具备评测条件：" + String.join("；", state.getReasons()));
        Map<String, Object> config = readiness.proposal(proposalId);
        policy.validatePlan(plan, config);
        Object taskId = repository.findProposal(proposalId).get("task_id");
        if (!(taskId instanceof Number)) throw new IllegalArgumentException("配置草案没有关联分析任务");
        Map<String, Object> source = repository.findTaskDatasource(((Number) taskId).longValue());
        return compiler.compile(plan, config, String.valueOf(source.get("db_type")));
    }

    @Override
    public Map<String, Object> plan(long proposalId, String question) { return planner.plan(proposalId, question); }

    @Override
    public QueryResult execute(long proposalId, Map<String, Object> plan) { return execution.execute(proposalId, plan); }

    @Override
    public Map<String, Object> evaluate(long proposalId, Map<String, Object> benchmark, String username) {
        return benchmarks.evaluate(proposalId, benchmark, username);
    }

    @Override
    public Map<String, Object> ask(long proposalId, Map<String, Object> request, String username) {
        String question = text(request.get("question"));
        String displayQuestion = text(request.get("displayQuestion"));
        if (question.isEmpty()) throw new IllegalArgumentException("问题不能为空");
        if (question.length() > 2000 || displayQuestion.length() > 2000) throw new IllegalArgumentException("问题不能超过2000字");
        String sessionId = text(request.get("sessionId"));
        Map<String, Object> previous;
        if (Boolean.TRUE.equals(request.get("newQuestion")) || (!displayQuestion.isEmpty() && !question.equals(displayQuestion))) {
            history.validateSession(username, proposalId, sessionId);
            previous = Collections.emptyMap();
        } else {
            previous = history.latest(username, proposalId, sessionId);
        }
        String queryQuestion = question;
        if ("clarification".equals(previous.get("type"))) {
            String pending = text(previous.get("queryQuestion"));
            if (!pending.isEmpty()) queryQuestion = pending + "\n补充说明：" + question;
        }
        Map<String, Object> response;
        try {
            if (queryQuestion.length() > 2000) throw new IllegalArgumentException("补充后的问题超过2000字，请另起一个问题");
            response = answer(proposalId, queryQuestion, previous);
        } catch (IllegalArgumentException | IllegalStateException failure) {
            response = new LinkedHashMap<>();
            response.put("type", "error");
            response.put("error", failure.getMessage());
        }
        response.put("question", displayQuestion.isEmpty() ? question : displayQuestion);
        response.put("queryQuestion", queryQuestion);
        return saveHistory(username, proposalId, sessionId, response);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> answer(long proposalId, String question, Map<String, Object> previous) {
        Object base = previous.get("plan");
        Map<String, Object> planned = base instanceof Map
                ? planner.followUp(proposalId, question, (Map<String, Object>) base, previous.get("configHash"))
                : planner.plan(proposalId, question);
        if (planned.containsKey("clarification")) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("type", "clarification");
            response.put("clarification", planned.get("clarification"));
            if (base instanceof Map && planned.containsKey("configHash")) {
                response.put("plan", base);
                response.put("configHash", previous.get("configHash"));
            }
            return response;
        }
        Map<String, Object> plan = (Map<String, Object>) planned.get("plan");
        QueryReadinessService.Readiness state = readiness.check(proposalId);
        boolean followedUp = planned.containsKey("changedFields");
        Map<String, Object> response = responses.result(plan, followedUp
                ? execution.execute(proposalId, plan, text(planned.get("configHash"))) : execution.execute(proposalId, plan));
        if (followedUp) response.put("changedFields", planned.get("changedFields"));
        response.put("semanticEvidence", planned.get("semanticEvidence"));
        response.put("queryEnabled", state.isQueryEnabled());
        return response;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> drilldown(long proposalId, Map<String, Object> request, String username) {
        Map<String, Object> plan = request.get("plan") instanceof Map ? (Map<String, Object>) request.get("plan") : request;
        String sessionId = text(request.get("sessionId"));
        history.validateSession(username, proposalId, sessionId);
        Map<String, Object> response = responses.result(plan, execution.execute(proposalId, plan));
        String question = text(request.get("question"));
        response.put("question", question.isEmpty() ? "继续下钻分析" : question);
        return saveHistory(username, proposalId, sessionId, response);
    }

    @Override
    public Map<String, Object> catalog(long proposalId) {
        Map<String, Object> proposal = repository.findProposal(proposalId);
        QueryReadinessService.Readiness state = readiness.check(proposalId, proposal);
        Map<String, Object> config = readiness.proposal(proposal);
        Map<String, Object> result = catalog.describe(config);
        result.put("exampleQuestions", java.util.Collections.emptyList());
        if (state.isQueryEnabled() && proposal.get("task_id") instanceof Number) {
            Map<String,Object> source = repository.findTaskDatasource(((Number) proposal.get("task_id")).longValue());
            if (source.get("db_type") != null) {
                result.put("exampleQuestions", planner.examples(config, result,
                        plan -> compiler.compile(plan, config, String.valueOf(source.get("db_type")))));
            }
        }
        result.put("readiness", state);
        result.put("eligibleForEvaluation", state.isEligibleForEvaluation());
        result.put("queryEnabled", state.isQueryEnabled());
        result.put("readinessReasons", state.getReasons());
        return result;
    }

    private Map<String, Object> saveHistory(String username, long proposalId, String sessionId, Map<String, Object> response) {
        try {
            return history.save(username, proposalId, sessionId, response);
        } catch (RuntimeException failure) {
            response.put("historyWarning", "本次结果未能保存到历史记录，请稍后重试。");
            return response;
        }
    }

    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
}

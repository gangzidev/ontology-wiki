package com.ontologywiki.query.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import com.ontologywiki.common.core.domain.AjaxResult;
import com.ontologywiki.query.repository.QueryConfigurationRepository;
import com.ontologywiki.query.service.QueryBenchmarkService;
import com.ontologywiki.query.service.QueryChartHintService;
import com.ontologywiki.query.service.QueryExecutionService;
import com.ontologywiki.query.service.QueryPlanValidator;
import com.ontologywiki.query.service.QueryPlannerService;
import com.ontologywiki.query.service.QueryReadinessService;
import com.ontologywiki.query.service.QuerySemanticCatalogService;
import com.ontologywiki.query.service.QuerySqlCompiler;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 数据查询模块入口；当前提供查询准入检查，查询执行须等准确性评测通过后开放。 */
@RestController
@RequestMapping("/ontology/data-query")
public class DataQueryController {
    private final QueryReadinessService readinessService;
    private final QueryConfigurationRepository repository;
    private final QueryPlanValidator planValidator = new QueryPlanValidator();
    private final QuerySqlCompiler sqlCompiler = new QuerySqlCompiler();
    private final QueryPlannerService planner;
    private final QueryExecutionService execution;
    private final QueryBenchmarkService benchmarks;
    private final QueryChartHintService chartHint;
    private final QuerySemanticCatalogService semanticCatalog;

    public DataQueryController(QueryReadinessService readinessService, QueryConfigurationRepository repository,
                               QueryPlannerService planner, QueryExecutionService execution,
                               QueryBenchmarkService benchmarks, QueryChartHintService chartHint,
                               QuerySemanticCatalogService semanticCatalog) {
        this.readinessService = readinessService;
        this.repository = repository;
        this.planner = planner;
        this.execution = execution;
        this.benchmarks = benchmarks;
        this.chartHint = chartHint;
        this.semanticCatalog = semanticCatalog;
    }

    /** 查看指定配置是否具备进入数据问答评测的条件。 */
    @GetMapping("/readiness/{proposalId}")
    @PreAuthorize("@ss.hasPermi('ontology:proposals:query')")
    public AjaxResult readiness(@PathVariable long proposalId) {
        return AjaxResult.success(readinessService.check(proposalId));
    }

    /** 编译受限查询计划供人工核对，不访问业务数据库。 */
    @PostMapping("/compile/{proposalId}")
    @PreAuthorize("@ss.hasPermi('ontology:proposals:query')")
    public AjaxResult compile(@PathVariable long proposalId, @RequestBody java.util.Map<String, Object> plan) {
        QueryReadinessService.Readiness readiness = readinessService.check(proposalId);
        if (!readiness.isEligibleForEvaluation()) throw new IllegalStateException("配置暂不具备评测条件：" + String.join("；", readiness.getReasons()));
        Map<String, Object> config = readinessService.proposal(proposalId);
        planValidator.validate(plan, config);
        Map<String, Object> proposal = repository.findProposal(proposalId);
        Object taskId = proposal.get("task_id");
        if (!(taskId instanceof Number)) throw new IllegalArgumentException("配置草案没有关联分析任务");
        Map<String, Object> datasource = repository.findTaskDatasource(((Number) taskId).longValue());
        QuerySqlCompiler.Result result = sqlCompiler.compile(plan, config, String.valueOf(datasource.get("db_type")));
        return AjaxResult.success(result);
    }

    /** 将自然语言解析成待核对的计划，不执行查询。 */
    @PostMapping("/plan/{proposalId}")
    @PreAuthorize("@ss.hasPermi('ontology:proposals:query')")
    public AjaxResult plan(@PathVariable long proposalId, @RequestBody java.util.Map<String, Object> request) {
        return AjaxResult.success(planner.plan(proposalId, request.get("question") == null ? null : String.valueOf(request.get("question"))));
    }

    /** 执行通过评测门禁的只读查询；未通过时只允许使用预览接口。 */
    @PostMapping("/execute/{proposalId}")
    @PreAuthorize("@ss.hasPermi('ontology:proposals:query')")
    public AjaxResult execute(@PathVariable long proposalId, @RequestBody Map<String, Object> plan) {
        return AjaxResult.success(execution.execute(proposalId, plan));
    }

    /** 使用已确认的标准答案运行配置评测并写入服务端准入凭证。 */
    @PostMapping("/evaluate/{proposalId}")
    @PreAuthorize("@ss.hasPermi('ontology:proposals:edit')")
    public AjaxResult evaluate(@PathVariable long proposalId, @RequestBody Map<String, Object> benchmark) {
        return AjaxResult.success(benchmarks.evaluate(proposalId, benchmark));
    }

    /**
     * 一步问答接口：自然语言 → 语义解析 → 执行（评测通过时）→ 返回结果+图表建议。
     * 未通过评测时返回计划预览，不访问业务数据库。
     */
    @PostMapping("/ask/{proposalId}")
    @PreAuthorize("@ss.hasPermi('ontology:proposals:query')")
    public AjaxResult ask(@PathVariable long proposalId, @RequestBody Map<String, Object> request) {
        String question = request.get("question") == null ? null : String.valueOf(request.get("question")).trim();
        if (question == null || question.isEmpty()) return AjaxResult.error("问题不能为空");

        // 1. 生成计划
        Map<String, Object> planResult = planner.plan(proposalId, question);
        if (planResult.containsKey("clarification")) {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("type", "clarification");
            resp.put("question", question);
            resp.put("clarification", planResult.get("clarification"));
            return AjaxResult.success(resp);
        }

        Map<String, Object> plan = (Map<String, Object>) planResult.get("plan");
        QueryReadinessService.Readiness state = readinessService.check(proposalId);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("type", "result");
        resp.put("question", question);
        resp.put("semanticEvidence", planResult.get("semanticEvidence"));
        resp.put("configHash", planResult.get("configHash"));
        resp.put("queryEnabled", state.isQueryEnabled());
        resp.put("plan", plan);

        if (state.isQueryEnabled()) {
            // 2a. 评测已通过 → 执行查询
            QueryExecutionService.Result result = execution.execute(proposalId, plan);
            resp.put("rows", result.getRows());
            resp.put("columns", result.getRows().isEmpty() ? new java.util.ArrayList<>()
                    : new java.util.ArrayList<>(result.getRows().get(0).keySet()));
            resp.put("rowCount", result.getRows().size());
            resp.put("sql", result.getSql());
            resp.put("parameters", result.getParameters());
            resp.put("explanation", result.getExplanation());
            resp.put("chartHint", chartHint.hint(plan, result.getRows(), result.getExplanation()));
        } else {
            // 2b. 仅预览计划，不查询数据库
            Map<String, Object> config = readinessService.proposal(proposalId);
            Map<String, Object> proposal = repository.findProposal(proposalId);
            Object taskId = proposal.get("task_id");
            Map<String, Object> datasource = (taskId instanceof Number)
                    ? repository.findTaskDatasource(((Number) taskId).longValue())
                    : java.util.Collections.emptyMap();
            QuerySqlCompiler.Result compiled = sqlCompiler.compile(plan, config,
                    String.valueOf(datasource.getOrDefault("db_type", "mysql")));
            resp.put("type", "preview");
            resp.put("plan", plan);
            resp.put("sql", compiled.getSql());
            resp.put("parameters", compiled.getParameters());
            resp.put("readinessReasons", state.getReasons());
        }
        return AjaxResult.success(resp);
    }

    /** 从已执行计划继续下钻；下钻值仍由服务端按语义目录转换为参数化过滤条件。 */
    @PostMapping("/drilldown/{proposalId}")
    @PreAuthorize("@ss.hasPermi('ontology:proposals:query')")
    public AjaxResult drilldown(@PathVariable long proposalId, @RequestBody Map<String, Object> plan) {
        QueryExecutionService.Result result = execution.execute(proposalId, plan);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("type", "result");
        response.put("plan", plan);
        response.put("rows", result.getRows());
        response.put("columns", result.getRows().isEmpty() ? new java.util.ArrayList<>() : new java.util.ArrayList<>(result.getRows().get(0).keySet()));
        response.put("rowCount", result.getRows().size());
        response.put("sql", result.getSql());
        response.put("parameters", result.getParameters());
        response.put("explanation", result.getExplanation());
        response.put("configHash", result.getConfigHash());
        response.put("chartHint", chartHint.hint(plan, result.getRows(), result.getExplanation()));
        return AjaxResult.success(response);
    }

    /** 返回指定配置的语义目录摘要（指标、维度名称列表），供前端展示查询引导。 */
    @GetMapping("/catalog/{proposalId}")
    @PreAuthorize("@ss.hasPermi('ontology:proposals:query')")
    public AjaxResult catalog(@PathVariable long proposalId) {
        QueryReadinessService.Readiness state = readinessService.check(proposalId);
        Map<String, Object> config = readinessService.proposal(proposalId);
        Map<String, Object> semantics = mergedSemantics(config);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("eligibleForEvaluation", state.isEligibleForEvaluation());
        result.put("queryEnabled", state.isQueryEnabled());
        result.put("readinessReasons", state.getReasons());
        result.put("metrics", nameList((java.util.List<?>) semantics.get("metrics")));
        result.put("dimensions", nameList((java.util.List<?>) semantics.get("dimensions")));
        result.put("timeDimensions", nameList((java.util.List<?>) semantics.get("timeDimensions")));
        return AjaxResult.success(result);
    }

    @SuppressWarnings("unchecked")
    private java.util.List<Map<String, Object>> nameList(java.util.List<?> items) {
        java.util.List<Map<String, Object>> result = new java.util.ArrayList<>();
        if (items == null) return result;
        for (Object item : items) {
            if (!(item instanceof Map)) continue;
            Map<?, ?> m = (Map<?, ?>) item;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", m.get("id"));
            entry.put("name", m.get("name") != null ? m.get("name") : m.get("label"));
            entry.put("propertyId", m.get("propertyId"));
            entry.put("definition", m.get("definition"));
            entry.put("synonyms", m.get("synonyms"));
            result.add(entry);
        }
        return result;
    }

    private Map<String, Object> mergedSemantics(Map<String, Object> config) {
        Map<String, Object> result = new LinkedHashMap<>();
        Object legacy = config.get("querySemantics");
        Object catalog = config.get("semanticCatalog");
        if (legacy instanceof Map) for (Map.Entry<?, ?> e : ((Map<?, ?>) legacy).entrySet()) result.put(String.valueOf(e.getKey()), e.getValue());
        if (catalog instanceof Map) for (Map.Entry<?, ?> e : ((Map<?, ?>) catalog).entrySet()) result.put(String.valueOf(e.getKey()), e.getValue());
        return result;
    }
}

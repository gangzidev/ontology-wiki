package com.ontologywiki.query.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ontologywiki.query.domain.QueryEvaluation;
import com.ontologywiki.query.domain.QueryResult;
import com.ontologywiki.query.mapper.QueryEvaluationMapper;
import com.ontologywiki.query.policy.QueryReadinessService;
import com.ontologywiki.query.repository.QueryConfigurationRepository;
import com.ontologywiki.query.service.IQueryBenchmarkService;
import com.ontologywiki.query.service.IQueryExecutionService;
import com.ontologywiki.query.support.QueryConfigHash;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

/** 用业务人员确认的标准结果运行配置质量评测。 */
@Service
public class QueryBenchmarkServiceImpl implements IQueryBenchmarkService {
    private final QueryConfigurationRepository repository;
    private final QueryReadinessService readiness;
    private final IQueryExecutionService execution;
    private final QueryEvaluationMapper evaluationMapper;
    private final ObjectMapper json;

    public QueryBenchmarkServiceImpl(QueryConfigurationRepository repository, QueryReadinessService readiness,
                                 IQueryExecutionService execution, QueryEvaluationMapper evaluationMapper, ObjectMapper json) {
        this.repository = repository; this.readiness = readiness; this.execution = execution; this.evaluationMapper = evaluationMapper; this.json = json;
    }

    public Map<String, Object> evaluate(long proposalId, Map<String, Object> request, String username) {
        String version = text(request.get("benchmarkVersion"));
        Object rawCases = request.get("cases");
        if (version.isEmpty() || version.length() > 100 || !(rawCases instanceof List) || ((List<?>) rawCases).isEmpty() || ((List<?>) rawCases).size() > 100) {
            throw new IllegalArgumentException("评测版本必填，标准用例需为1到100条");
        }
        Map<String, Object> config = readiness.proposal(proposalId);
        QueryReadinessService.Readiness state = readiness.check(proposalId);
        if (!state.isEligibleForEvaluation()) throw new IllegalArgumentException("配置暂不具备评测条件：" + String.join("；", state.getReasons()));
        Map<String, Object> proposal = repository.findProposal(proposalId);
        if (!"COMPLETED".equalsIgnoreCase(text(proposal.get("status"))) && !"IMPORTED".equalsIgnoreCase(text(proposal.get("status")))) {
            throw new IllegalArgumentException("只有已完成审核的本体配置可以评测");
        }
        String hash = QueryConfigHash.of(json, config);
        int passed = 0;
        List<Map<String, Object>> details = new ArrayList<>();
        for (int i = 0; i < ((List<?>) rawCases).size(); i++) {
            Object raw = ((List<?>) rawCases).get(i);
            if (!(raw instanceof Map)) throw new IllegalArgumentException("评测用例格式错误");
            Map<?, ?> testCase = (Map<?, ?>) raw;
            if (!(testCase.get("plan") instanceof Map) || !(testCase.get("expectedRows") instanceof List)) throw new IllegalArgumentException("每条用例必须包含 plan 和 expectedRows");
            boolean success = false;
            String error = null;
            try {
                QueryResult actual = execution.executeForEvaluation(proposalId, (Map<String, Object>) testCase.get("plan"));
                success = normalize(actual.getRows()).equals(normalize((List<?>) testCase.get("expectedRows")));
                if (!success) error = "实际结果与标准结果不一致";
            } catch (Exception e) {
                error = e.getMessage() == null ? "评测执行失败" : e.getMessage();
            }
            if (success) passed++;
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("caseId", text(testCase.get("id")).isEmpty() ? String.valueOf(i + 1) : text(testCase.get("id")));
            detail.put("passed", success);
            if (error != null) detail.put("error", error);
            details.add(detail);
        }
        boolean allPassed = passed == details.size();
        String detailJson;
        try { detailJson = json.writeValueAsString(details); } catch (Exception e) { throw new IllegalStateException("评测结果保存失败", e); }
        QueryEvaluation evaluation = new QueryEvaluation();
        evaluation.setProposalId(proposalId);
        evaluation.setConfigHash(hash);
        evaluation.setBenchmarkVersion(version);
        evaluation.setPassed(allPassed);
        evaluation.setCaseCount(details.size());
        evaluation.setPassedCount(passed);
        evaluation.setDetail(detailJson);
        evaluation.setEvaluatedBy(username);
        try {
            evaluationMapper.saveEvaluation(evaluation);
        } catch (org.springframework.dao.DataAccessException failure) {
            throw new IllegalStateException("评测记录未能保存，请检查评测存储", failure);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("proposalId", proposalId); result.put("configHash", hash); result.put("benchmarkVersion", version);
        result.put("passed", allPassed); result.put("caseCount", details.size()); result.put("passedCount", passed); result.put("details", details);
        return result;
    }

    private List<Object> normalize(Object value) {
        if (value instanceof Map) {
            Map<String, Object> sorted = new TreeMap<>();
            ((Map<?, ?>) value).forEach((key, item) -> sorted.put(String.valueOf(key).toLowerCase(Locale.ROOT), normalizeValue(item)));
            return Collections.<Object>singletonList(sorted);
        }
        List<Object> result = new ArrayList<>();
        if (value instanceof List) for (Object item : (List<?>) value) result.add(normalizeValue(item));
        result.sort(Comparator.comparing(String::valueOf));
        return result;
    }

    private Object normalizeValue(Object value) {
        if (value instanceof Number) return new BigDecimal(String.valueOf(value)).stripTrailingZeros();
        if (value instanceof Map || value instanceof List) return normalize(value);
        return value;
    }

    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
}

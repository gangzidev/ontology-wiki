package com.ontologywiki.query.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ontologywiki.common.utils.SecurityUtils;
import com.ontologywiki.query.repository.QueryConfigurationRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.*;

/** 用业务人员确认的标准结果运行评测；任何用例失败都会关闭该配置的查询。 */
@Service
public class QueryBenchmarkService {
    private final QueryConfigurationRepository repository;
    private final QueryReadinessService readiness;
    private final QueryExecutionService execution;
    private final javax.sql.DataSource dataSource;
    private final ObjectMapper json;

    public QueryBenchmarkService(QueryConfigurationRepository repository, QueryReadinessService readiness,
                                 QueryExecutionService execution, javax.sql.DataSource dataSource, ObjectMapper json) {
        this.repository = repository; this.readiness = readiness; this.execution = execution; this.dataSource = dataSource; this.json = json;
    }

    public Map<String, Object> evaluate(long proposalId, Map<String, Object> request) {
        String version = text(request.get("benchmarkVersion"));
        Object rawCases = request.get("cases");
        if (version.isEmpty() || version.length() > 100 || !(rawCases instanceof List) || ((List<?>) rawCases).isEmpty() || ((List<?>) rawCases).size() > 100) {
            throw new IllegalArgumentException("评测版本必填，标准用例需为1到100条");
        }
        Map<String, Object> config = readiness.proposal(proposalId);
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
                QueryExecutionService.Result actual = execution.executeForEvaluation(proposalId, (Map<String, Object>) testCase.get("plan"));
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
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "insert into ontology_query_evaluation (proposal_id, config_hash, benchmark_version, passed, case_count, passed_count, detail, evaluated_by, evaluated_at) values (?, ?, ?, ?, ?, ?, ?, ?, now()) on duplicate key update passed=values(passed), case_count=values(case_count), passed_count=values(passed_count), detail=values(detail), evaluated_by=values(evaluated_by), evaluated_at=now()")) {
            statement.setLong(1, proposalId); statement.setString(2, hash); statement.setString(3, version);
            statement.setInt(4, allPassed ? 1 : 0); statement.setInt(5, details.size()); statement.setInt(6, passed);
            statement.setString(7, detailJson); statement.setString(8, SecurityUtils.getUsername()); statement.executeUpdate();
        } catch (Exception e) { throw new IllegalStateException("评测记录未能保存，查询准入保持关闭", e); }
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

package com.ontologywiki.query.presentation;

import com.ontologywiki.query.domain.QueryResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class QueryResponseAssembler {
    private final QueryChartHintService chartHint;

    public QueryResponseAssembler(QueryChartHintService chartHint) { this.chartHint = chartHint; }

    public Map<String, Object> result(Map<String, Object> plan, QueryResult result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("type", "result");
        response.put("plan", plan);
        response.put("rows", result.getRows());
        response.put("columns", result.getRows().isEmpty() ? new ArrayList<>() : new ArrayList<>(result.getRows().get(0).keySet()));
        response.put("rowCount", result.getRows().size());
        if (plan.get("detailObjectId") != null) {
            Object labels = result.getExplanation().get("columnLabels");
            response.put("columnLabels", labels);
            if (result.getRows().isEmpty() && labels instanceof Map) response.put("columns", new ArrayList<>(((Map<?, ?>) labels).keySet()));
            response.put("limit", result.getLimit());
            response.put("offset", plan.getOrDefault("offset", 0));
            response.put("mayHaveMore", result.getRows().size() == result.getLimit());
        }
        response.put("sql", result.getSql());
        response.put("parameters", result.getParameters());
        response.put("explanation", result.getExplanation());
        response.put("configHash", result.getConfigHash());
        response.put("chartHint", chartHint.hint(plan, result.getRows(), result.getExplanation()));
        return response;
    }
}

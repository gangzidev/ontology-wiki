package com.ontologywiki.query.domain;

import java.util.List;
import java.util.Map;

public class QueryResult {
    private final List<Map<String, Object>> rows;
    private final List<String> objectIds;
    private final String sql;
    private final List<Object> parameters;
    private final String configHash;
    private final Map<String, Object> explanation;
    private final int limit;
    public QueryResult(List<Map<String, Object>> rows, List<String> objectIds, String sql, List<Object> parameters, String configHash, Map<String, Object> explanation, int limit) { this.rows = rows; this.objectIds = objectIds; this.sql = sql; this.parameters = parameters; this.configHash = configHash; this.explanation = explanation; this.limit = limit; }
    public List<Map<String, Object>> getRows() { return rows; }
    public List<String> getObjectIds() { return objectIds; }
    public String getSql() { return sql; }
    public List<Object> getParameters() { return parameters; }
    public String getConfigHash() { return configHash; }
    public Map<String, Object> getExplanation() { return explanation; }
    public int getLimit() { return limit; }
}

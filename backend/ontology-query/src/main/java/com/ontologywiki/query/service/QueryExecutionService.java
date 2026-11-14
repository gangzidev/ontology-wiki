package com.ontologywiki.query.service;

import com.ontologywiki.ontology.service.CredentialService;
import com.ontologywiki.query.repository.QueryConfigurationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.WeekFields;
import java.util.*;

/** 只读查询执行器：仅在服务端评测通过后连接配置中的业务数据源。 */
@Service
public class QueryExecutionService {
    private final QueryConfigurationRepository repository;
    private final QueryReadinessService readiness;
    private final QueryPlanValidator validator;
    private final QuerySqlCompiler compiler;
    private final CredentialService credentials;

    public QueryExecutionService(QueryConfigurationRepository repository, QueryReadinessService readiness,
                                 QueryPlanValidator validator, QuerySqlCompiler compiler, CredentialService credentials) {
        this.repository = repository; this.readiness = readiness; this.validator = validator;
        this.compiler = compiler; this.credentials = credentials;
    }

    public Result execute(long proposalId, Map<String, Object> plan) {
        QueryReadinessService.Readiness state = readiness.check(proposalId);
        if (!state.isQueryEnabled()) throw new IllegalStateException("查询尚未通过服务端标准问答评测，当前只允许预览");
        return executeValidated(proposalId, plan);
    }

    Result executeForEvaluation(long proposalId, Map<String, Object> plan) {
        return executeValidated(proposalId, plan);
    }

    private Result executeValidated(long proposalId, Map<String, Object> plan) {
        Map<String, Object> config = readiness.proposal(proposalId);
        validator.validate(plan, config);
        Map<String, Object> proposal = repository.findProposal(proposalId);
        Object taskId = proposal.get("task_id");
        if (!(taskId instanceof Number)) throw new IllegalArgumentException("配置草案没有关联分析任务");
        Map<String, Object> source = repository.findTaskDatasource(((Number) taskId).longValue());
        Map<String, Object> comparison = plan.get("comparison") instanceof Map ? (Map<String, Object>) plan.get("comparison") : Collections.<String, Object>emptyMap();
        String comparisonType = text(comparison.get("type")).toUpperCase(Locale.ROOT);
        Map<String, Object> currentPlan = new LinkedHashMap<>(plan);
        currentPlan.remove("comparison");
        Result current = executeSingle(currentPlan, config, source);
        if (comparisonType.isEmpty() || "NONE".equals(comparisonType)) return current;
        int periods = comparison.get("periods") == null ? 1 : Integer.parseInt(String.valueOf(comparison.get("periods")));
        Map<String, Object> previousPlan = new LinkedHashMap<>(currentPlan);
        Map<?, ?> range = (Map<?, ?>) currentPlan.get("timeRange");
        int months = "YOY".equals(comparisonType) ? 12 * periods : "QOQ".equals(comparisonType) ? 3 * periods : "MOM".equals(comparisonType) ? periods : 0;
        int days = "WOW".equals(comparisonType) ? 7 * periods : 0;
        Map<String, Object> previousRange = new LinkedHashMap<>();
        previousRange.put("from", shiftDate(text(range.get("from")), months, days));
        previousRange.put("to", shiftDate(text(range.get("to")), months, days));
        previousPlan.put("timeRange", previousRange);
        Result previous = executeSingle(previousPlan, config, source);
        List<Map<String, Object>> mergedRows = compareRows(current.getRows(), previous.getRows(), currentPlan, comparisonType, periods);
        Map<String, Object> explanation = new LinkedHashMap<>(current.getExplanation());
        explanation.put("comparison", comparisonType);
        explanation.put("comparisonPeriods", periods);
        return new Result(mergedRows, current.getObjectIds(), current.getSql() + "\n-- comparison period --\n" + previous.getSql(),
                concat(current.getParameters(), previous.getParameters()), current.getConfigHash(), explanation,
                Integer.parseInt(String.valueOf(plan.getOrDefault("limit", 100))));
    }

    private Result executeSingle(Map<String, Object> plan, Map<String, Object> config, Map<String, Object> source) {
        validator.validate(plan, config);
        QuerySqlCompiler.Result compiled = compiler.compile(plan, config, String.valueOf(source.get("db_type")));
        String url = text(source.get("jdbc_url"));
        if (url.isEmpty()) throw new IllegalArgumentException("数据源没有 JDBC 地址");
        String dbType = text(source.get("db_type")).toLowerCase(Locale.ROOT);
        try {
            Class.forName(driver(dbType));
            try (Connection connection = DriverManager.getConnection(url, text(source.get("username")), credentials.decrypt(text(source.get("password_enc"))))) {
                connection.setReadOnly(true);
                try (PreparedStatement statement = connection.prepareStatement(compiled.getSql())) {
                    statement.setQueryTimeout(30);
                    List<Object> params = compiled.getParameters();
                    for (int i = 0; i < params.size(); i++) statement.setObject(i + 1, params.get(i));
                    statement.setMaxRows(1000);
                    try (ResultSet rs = statement.executeQuery()) {
                        List<Map<String, Object>> rows = new ArrayList<>();
                        ResultSetMetaData meta = rs.getMetaData();
                        while (rs.next()) {
                            Map<String, Object> row = new LinkedHashMap<>();
                            for (int i = 1; i <= meta.getColumnCount(); i++) row.put(meta.getColumnLabel(i).toLowerCase(Locale.ROOT), rs.getObject(i));
                            rows.add(row);
                        }
                        Map<String, Object> explanation = explain(plan, config, compiled.getObjectIds());
                        return new Result(rows, compiled.getObjectIds(), compiled.getSql(), compiled.getParameters(),
                                QueryConfigHash.of(new ObjectMapper(), config), explanation, Integer.parseInt(String.valueOf(plan.getOrDefault("limit", 100))));
                    }
                }
            }
        } catch (SQLException | ClassNotFoundException e) {
            throw new IllegalStateException("只读查询执行失败，请检查数据源权限和网络", e);
        }
    }

    private List<Map<String, Object>> compareRows(List<Map<String, Object>> current, List<Map<String, Object>> previous,
                                                  Map<String, Object> plan, String type, int periods) {
        Map<String, Map<String, Object>> priorByKey = new LinkedHashMap<>();
        for (Map<String, Object> row : previous) priorByKey.put(rowKey(row, plan, type, periods, true), row);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : current) {
            Map<String, Object> merged = new LinkedHashMap<>();
            for (String id : dimensionIds(plan)) merged.put(id.toLowerCase(Locale.ROOT), value(row, id));
            String granularity = text(plan.get("timeGranularity")).toLowerCase(Locale.ROOT);
            if (!granularity.isEmpty()) merged.put("time_" + granularity, value(row, "time_" + granularity));
            Map<String, Object> prior = priorByKey.get(rowKey(row, plan, type, periods, false));
            for (String metricId : metricIds(plan)) {
                Object currentValue = value(row, metricId);
                Object previousValue = prior == null ? null : value(prior, metricId);
                merged.put("current_" + metricId, currentValue);
                merged.put("previous_" + metricId, previousValue);
                BigDecimal currentNumber = decimal(currentValue), previousNumber = decimal(previousValue);
                BigDecimal change = currentNumber == null || previousNumber == null ? null : currentNumber.subtract(previousNumber);
                merged.put("change_" + metricId, change);
                merged.put("change_rate_" + metricId, change == null || previousNumber.compareTo(BigDecimal.ZERO) == 0
                        ? null : change.divide(previousNumber, 8, java.math.RoundingMode.HALF_UP));
            }
            result.add(merged);
        }
        return result;
    }

    private String rowKey(Map<String, Object> row, Map<String, Object> plan, String type, int periods, boolean shift) {
        List<String> values = new ArrayList<>();
        for (String id : dimensionIds(plan)) values.add(String.valueOf(value(row, id)));
        String granularity = text(plan.get("timeGranularity")).toUpperCase(Locale.ROOT);
        if (!granularity.isEmpty()) {
            String key = "time_" + granularity.toLowerCase(Locale.ROOT);
            Object raw = value(row, key);
            String normalized = normalizePeriod(raw, granularity);
            int months = "YOY".equals(type) ? 12 * periods : "QOQ".equals(type) ? 3 * periods : "MOM".equals(type) ? periods : 0;
            int days = "WOW".equals(type) ? 7 * periods : 0;
            values.add(shift ? shiftPeriod(normalized, granularity, months, days) : normalized);
        }
        return String.join("\u001f", values);
    }

    private String normalizePeriod(Object value, String granularity) {
        if (value == null) return "";
        LocalDate date = null;
        if (value instanceof java.sql.Date) date = ((java.sql.Date) value).toLocalDate();
        else if (value instanceof Timestamp) date = ((Timestamp) value).toLocalDateTime().toLocalDate();
        else if (value instanceof java.util.Date) date = new java.sql.Date(((java.util.Date) value).getTime()).toLocalDate();
        else {
            String text = String.valueOf(value);
            try {
                if (text.matches("\\d{4}-Q[1-4]")) return text;
                if (text.matches("\\d{4}-\\d{2}")) return text;
                if (text.matches("\\d{4}-\\d{2}-\\d{2}")) date = LocalDate.parse(text);
                else if (text.matches("\\d{4}")) return text;
                else date = Timestamp.valueOf(text.replace('T', ' ')).toLocalDateTime().toLocalDate();
            } catch (Exception ignored) { return text; }
        }
        if ("YEAR".equals(granularity)) return String.valueOf(date.getYear());
        if ("QUARTER".equals(granularity)) return date.getYear() + "-Q" + ((date.getMonthValue() - 1) / 3 + 1);
        if ("MONTH".equals(granularity)) return YearMonth.from(date).toString();
        if ("WEEK".equals(granularity)) return date.get(WeekFields.ISO.weekBasedYear()) + "-" + String.format(Locale.ROOT, "%02d", date.get(WeekFields.ISO.weekOfWeekBasedYear()));
        return date.toString();
    }

    private String shiftPeriod(String period, String granularity, int months, int days) {
        try {
            if ("YEAR".equals(granularity)) return String.valueOf(Integer.parseInt(period) + months / 12);
            if ("QUARTER".equals(granularity)) {
                String[] parts = period.split("-Q");
                LocalDate shifted = LocalDate.of(Integer.parseInt(parts[0]), (Integer.parseInt(parts[1]) - 1) * 3 + 1, 1).plusMonths(months).plusDays(days);
                return shifted.getYear() + "-Q" + ((shifted.getMonthValue() - 1) / 3 + 1);
            }
            if ("MONTH".equals(granularity)) return YearMonth.parse(period).plusMonths(months).plusMonths(days / 30).toString();
            if ("WEEK".equals(granularity)) {
                String[] parts = period.split("-");
                LocalDate monday = LocalDate.of(Integer.parseInt(parts[0]), 1, 4).with(WeekFields.ISO.weekOfWeekBasedYear(), Integer.parseInt(parts[1])).with(WeekFields.ISO.dayOfWeek(), 1);
                LocalDate shifted = monday.plusMonths(months).plusDays(days);
                return shifted.get(WeekFields.ISO.weekBasedYear()) + "-" + String.format(Locale.ROOT, "%02d", shifted.get(WeekFields.ISO.weekOfWeekBasedYear()));
            }
            return LocalDate.parse(period).plusMonths(months).plusDays(days).toString();
        } catch (Exception ignored) { return period; }
    }

    private String shiftDate(String value, int months, int days) { return LocalDate.parse(value).minusMonths(months).minusDays(days).toString(); }
    private Object value(Map<String, Object> row, String key) { return row.get(key.toLowerCase(Locale.ROOT)); }
    private BigDecimal decimal(Object value) { if (!(value instanceof Number)) return null; return new BigDecimal(String.valueOf(value)); }
    private List<String> dimensionIds(Map<String, Object> plan) {
        List<String> result = new ArrayList<>();
        for (Map<String, Object> row : rows(plan.get("dimensions"))) result.add(text(row.get("id")));
        Map<?, ?> drilldown = plan.get("drilldown") instanceof Map ? (Map<?, ?>) plan.get("drilldown") : Collections.emptyMap();
        String next = text(drilldown.get("nextDimensionId")); if (!next.isEmpty() && !result.contains(next)) result.add(next);
        return result;
    }
    private List<String> metricIds(Map<String, Object> plan) { List<String> result = new ArrayList<>(); for (Map<String, Object> row : rows(plan.get("metrics"))) result.add(text(row.get("id"))); return result; }
    private List<Object> concat(List<Object> left, List<Object> right) { List<Object> result = new ArrayList<>(left); result.addAll(right); return result; }
    private List<Map<String, Object>> rows(Object value) { List<Map<String, Object>> result = new ArrayList<>(); if (value instanceof List) for (Object row : (List<?>) value) if (row instanceof Map) result.add((Map<String, Object>) row); return result; }

    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private Map<String, Object> explain(Map<String, Object> plan, Map<String, Object> config, List<String> objectIds) {
        Map<String, Object> semantics = mergedSemantics(config);
        Map<String, Object> explanation = new LinkedHashMap<>();
        explanation.put("metrics", describe(plan.get("metrics"), semantics == null ? null : semantics.get("metrics")));
        explanation.put("dimensions", describe(plan.get("dimensions"), semantics == null ? null : semantics.get("dimensions")));
        explanation.put("filters", plan.getOrDefault("filters", Collections.emptyList()));
        if (plan.get("timeDimension") != null) explanation.put("timeDimension", plan.get("timeDimension"));
        if (plan.get("timeRange") != null) explanation.put("timeRange", plan.get("timeRange"));
        if (plan.get("timeGranularity") != null) explanation.put("timeGranularity", plan.get("timeGranularity"));
        if (plan.get("orderBy") != null) explanation.put("orderBy", plan.get("orderBy"));
        explanation.put("sourceObjects", objectIds);
        return explanation;
    }
    private List<Map<String, Object>> describe(Object selected, Object definitions) {
        List<Map<String, Object>> result = new ArrayList<>();
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        if (definitions instanceof List) for (Object value : (List<?>) definitions) if (value instanceof Map) { Map<String, Object> row = (Map<String, Object>) value; byId.put(String.valueOf(row.get("id")), row); }
        if (selected instanceof List) for (Object value : (List<?>) selected) if (value instanceof Map) {
            Object id = ((Map<?, ?>) value).get("id"); Map<String, Object> definition = byId.get(String.valueOf(id));
            if (definition != null) { Map<String, Object> item = new LinkedHashMap<>(); item.put("id", id); item.put("name", definition.get("name")); item.put("definition", definition.get("definition")); item.put("propertyId", definition.get("propertyId")); item.put("aggregation", definition.get("aggregation")); result.add(item); }
        }
        return result;
    }
    private Map<String, Object> mergedSemantics(Map<String, Object> config) {
        Map<String, Object> result = new LinkedHashMap<>();
        Object legacy = config.get("querySemantics");
        Object catalog = config.get("semanticCatalog");
        if (legacy instanceof Map) for (Map.Entry<?, ?> entry : ((Map<?, ?>) legacy).entrySet()) result.put(String.valueOf(entry.getKey()), entry.getValue());
        if (catalog instanceof Map) for (Map.Entry<?, ?> entry : ((Map<?, ?>) catalog).entrySet()) result.put(String.valueOf(entry.getKey()), entry.getValue());
        return result;
    }
    private String driver(String dbType) {
        if ("postgres".equals(dbType) || "postgresql".equals(dbType)) return "org.postgresql.Driver";
        if ("hive".equals(dbType)) return "org.apache.hive.jdbc.HiveDriver";
        return "com.mysql.cj.jdbc.Driver";
    }

    public static class Result {
        private final List<Map<String, Object>> rows;
        private final List<String> objectIds;
        private final String sql;
        private final List<Object> parameters;
        private final String configHash;
        private final Map<String, Object> explanation;
        private final int limit;
        public Result(List<Map<String, Object>> rows, List<String> objectIds, String sql, List<Object> parameters, String configHash, Map<String, Object> explanation, int limit) { this.rows = rows; this.objectIds = objectIds; this.sql = sql; this.parameters = parameters; this.configHash = configHash; this.explanation = explanation; this.limit = limit; }
        public List<Map<String, Object>> getRows() { return rows; }
        public List<String> getObjectIds() { return objectIds; }
        public String getSql() { return sql; }
        public List<Object> getParameters() { return parameters; }
        public String getConfigHash() { return configHash; }
        public Map<String, Object> getExplanation() { return explanation; }
        public int getLimit() { return limit; }
    }
}

package com.ontologywiki.query.service;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import org.springframework.stereotype.Service;

/** 校验结构化查询计划，只允许引用已审核配置中的指标、维度和过滤字段。 */
@Service
public class QueryPlanValidator {
    private static final Set<String> AGGREGATIONS = new HashSet<>(Arrays.asList("COUNT", "SUM", "AVG", "MIN", "MAX"));
    private static final Set<String> FORMULA_OPERATORS = new HashSet<>(Arrays.asList("ADD", "SUBTRACT", "MULTIPLY", "DIVIDE", "SAFE_DIVIDE"));
    private static final Set<String> FORMULA_AGGREGATIONS = new HashSet<>(Arrays.asList("SUM", "COUNT", "AVG", "MIN", "MAX"));
    private static final Set<String> OPERATORS = new HashSet<>(Arrays.asList("=", "!=", ">", ">=", "<", "<=", "LIKE", "IS_NULL", "IS_NOT_NULL"));
    private static final Set<String> TIME_GRANULARITIES = new HashSet<>(Arrays.asList("DAY", "WEEK", "MONTH", "QUARTER", "YEAR"));
    private static final Set<String> COMPARISONS = new HashSet<>(Arrays.asList("NONE", "YOY", "MOM", "QOQ", "WOW"));

    /** 校验查询计划；失败时抛出明确的业务错误。 */
    public void validate(Map<String, Object> plan, Map<String, Object> config) {
        if (plan == null) throw new IllegalArgumentException("查询计划不能为空");
        for (String key : plan.keySet()) if (!Arrays.asList("metrics", "dimensions", "filters", "timeDimension", "timeRange", "timeGranularity", "orderBy", "limit", "comparison", "drilldown").contains(key)) throw new IllegalArgumentException("查询计划包含不支持的字段：" + key);
        for (String key : Arrays.asList("metrics", "dimensions", "filters")) if (plan.containsKey(key) && !(plan.get(key) instanceof List)) throw new IllegalArgumentException("查询计划字段必须是数组：" + key);
        List<?> metrics = list(plan.get("metrics"));
        if (metrics.isEmpty()) throw new IllegalArgumentException("查询计划至少需要一个指标");
        Map<String, Object> semantics = mergedSemantics(config);
        Map<String, Map<String, Object>> metricDefinitions = index(list(semantics.get("metrics")));
        Map<String, Map<String, Object>> dimensionDefinitions = index(list(semantics.get("dimensions")));
        Set<String> propertyIds = ids(list(config.get("properties")));
        for (Object raw : metrics) {
            Map<?, ?> metric = map(raw, "指标");
            String id = text(metric.get("id"));
            Map<String, Object> definition = metricDefinitions.get(id);
            if (definition == null) throw new IllegalArgumentException("指标不在已审核查询语义中：" + id);
            validateMetric(definition, metricDefinitions, propertyIds, new HashSet<String>());
        }
        for (Object raw : list(plan.get("dimensions"))) {
            Map<?, ?> dimension = map(raw, "维度");
            String id = text(dimension.get("id"));
            Map<String, Object> definition = dimensionDefinitions.get(id);
            if (definition == null) throw new IllegalArgumentException("维度不在已审核查询语义中：" + id);
            if (!propertyIds.contains(text(definition.get("propertyId")))) throw new IllegalArgumentException("维度属性映射不存在：" + definition.get("propertyId"));
        }
        validateTime(plan, semantics);
        validateOrderBy(plan, metrics, list(plan.get("dimensions")));
        validateComparison(plan);
        validateDrilldown(plan, dimensionDefinitions);
        for (Object raw : list(plan.get("filters"))) {
            Map<?, ?> filter = map(raw, "过滤条件");
            if (!propertyIds.contains(text(filter.get("propertyId")))) throw new IllegalArgumentException("过滤字段映射不存在：" + filter.get("propertyId"));
            String operator = text(filter.get("operator")).toUpperCase();
            if (!OPERATORS.contains(operator)) throw new IllegalArgumentException("不支持的过滤操作：" + operator);
            if (("IS_NULL".equals(operator) || "IS_NOT_NULL".equals(operator)) && filter.get("value") != null) throw new IllegalArgumentException("空值判断不能带过滤值");
            if (!"IS_NULL".equals(operator) && !"IS_NOT_NULL".equals(operator) && filter.get("value") == null) throw new IllegalArgumentException("过滤值不能为空");
            Object value = filter.get("value");
            if (value != null && !(value instanceof String || value instanceof Number || value instanceof Boolean)) throw new IllegalArgumentException("过滤值必须是简单标量");
            if (value instanceof String && ((String) value).length() > 2000) throw new IllegalArgumentException("过滤值不能超过2000字");
        }
        int limit = number(plan.get("limit"), 100);
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("返回条数必须在1到1000之间");
    }

    private void validateMetric(Map<String, Object> definition, Map<String, Map<String, Object>> all,
                                Set<String> propertyIds, Set<String> visiting) {
        String id = text(definition.get("id"));
        if (!visiting.add(id)) throw new IllegalArgumentException("计算指标存在循环引用：" + id);
        String type = text(definition.get("type")).toUpperCase();
        String aggregation = text(definition.get("aggregation")).toUpperCase();
        boolean computed = "COMPUTED".equals(type) || "COMPUTED".equals(aggregation) || definition.get("formula") instanceof Map;
        if (!computed) {
            if (!AGGREGATIONS.contains(aggregation)) throw new IllegalArgumentException("不支持的聚合方式：" + aggregation);
            String propertyId = text(definition.get("propertyId"));
            if (!propertyIds.contains(propertyId)) throw new IllegalArgumentException("指标属性映射不存在：" + definition.get("propertyId"));
            if (("SUM".equals(aggregation) || "AVG".equals(aggregation)) && definition.get("propertyId") == null) {
                throw new IllegalArgumentException("数值指标必须绑定属性");
            }
            visiting.remove(id);
            return;
        }
        validateFormula(definition.get("formula"), all, propertyIds, visiting);
        visiting.remove(id);
    }

    private void validateFormula(Object raw, Map<String, Map<String, Object>> all,
                                 Set<String> propertyIds, Set<String> visiting) {
        if (!(raw instanceof Map)) throw new IllegalArgumentException("计算指标必须提供 formula 对象");
        Map<?, ?> formula = (Map<?, ?>) raw;
        String operator = text(formula.get("operator")).toUpperCase();
        if (formula.get("metricId") != null) {
            Map<String, Object> referenced = all.get(text(formula.get("metricId")));
            if (referenced == null) throw new IllegalArgumentException("计算指标引用不存在：" + formula.get("metricId"));
            validateMetric(referenced, all, propertyIds, visiting);
            return;
        }
        if (formula.get("propertyId") != null) {
            if (!propertyIds.contains(text(formula.get("propertyId")))) throw new IllegalArgumentException("计算指标属性映射不存在：" + formula.get("propertyId"));
            String aggregation = text(formula.get("aggregation")).isEmpty() ? "SUM" : text(formula.get("aggregation")).toUpperCase();
            if (!AGGREGATIONS.contains(aggregation)) throw new IllegalArgumentException("不支持的计算指标聚合方式：" + aggregation);
            return;
        }
        if (formula.get("value") != null) {
            if (!(formula.get("value") instanceof Number)) throw new IllegalArgumentException("计算指标常量必须是数字");
            return;
        }
        if (!FORMULA_OPERATORS.contains(operator) && !FORMULA_AGGREGATIONS.contains(operator)) throw new IllegalArgumentException("不支持的计算指标操作：" + operator);
        if (FORMULA_AGGREGATIONS.contains(operator)) {
            if (formula.get("left") == null) throw new IllegalArgumentException("聚合公式缺少操作数");
            validateFormula(formula.get("left"), all, propertyIds, visiting);
            return;
        }
        if (formula.get("left") == null || formula.get("right") == null) throw new IllegalArgumentException("计算指标公式必须包含左右操作数");
        validateFormula(formula.get("left"), all, propertyIds, visiting);
        validateFormula(formula.get("right"), all, propertyIds, visiting);
    }

    private void validateComparison(Map<String, Object> plan) {
        if (plan.get("comparison") == null) return;
        if (!(plan.get("comparison") instanceof Map)) throw new IllegalArgumentException("comparison 必须是对象");
        Map<?, ?> comparison = (Map<?, ?>) plan.get("comparison");
        String type = text(comparison.get("type")).toUpperCase();
        if (!COMPARISONS.contains(type)) throw new IllegalArgumentException("不支持的对比类型：" + type);
        if (!"NONE".equals(type)) {
            if (plan.get("timeRange") == null || plan.get("timeGranularity") == null) throw new IllegalArgumentException("同比/环比需要时间范围和时间粒度");
            int periods = number(comparison.get("periods"), 1);
            if (periods < 1 || periods > 12) throw new IllegalArgumentException("对比周期必须在1到12之间");
        }
    }

    private void validateDrilldown(Map<String, Object> plan, Map<String, Map<String, Object>> dimensions) {
        if (plan.get("drilldown") == null) return;
        if (!(plan.get("drilldown") instanceof Map)) throw new IllegalArgumentException("drilldown 必须是对象");
        Map<?, ?> drilldown = (Map<?, ?>) plan.get("drilldown");
        String dimensionId = text(drilldown.get("dimensionId"));
        if (!dimensions.containsKey(dimensionId)) throw new IllegalArgumentException("下钻维度不在已审核查询语义中：" + dimensionId);
        boolean selected = false;
        for (Object raw : list(plan.get("dimensions"))) if (dimensionId.equals(text(map(raw, "维度").get("id")))) selected = true;
        if (!selected) throw new IllegalArgumentException("下钻来源必须是当前查询已选择的维度：" + dimensionId);
        if (drilldown.get("value") == null || text(drilldown.get("value")).isEmpty()) throw new IllegalArgumentException("下钻值不能为空");
        String next = text(drilldown.get("nextDimensionId"));
        if (!next.isEmpty() && !dimensions.containsKey(next)) throw new IllegalArgumentException("下一级下钻维度不在已审核查询语义中：" + next);
    }

    private List<?> list(Object value) { return value instanceof List ? (List<?>) value : java.util.Collections.emptyList(); }
    private Set<String> ids(List<?> rows) { Set<String> ids = new HashSet<>(); for (Object row : rows) if (row instanceof Map) ids.add(text(((Map<?, ?>) row).get("id"))); return ids; }
    private Map<String, Map<String, Object>> index(List<?> rows) { Map<String, Map<String, Object>> result = new java.util.LinkedHashMap<>(); for (Object row : rows) if (row instanceof Map) { Map<?, ?> raw = (Map<?, ?>) row; Map<String, Object> value = new java.util.LinkedHashMap<>(); for (Map.Entry<?, ?> item : raw.entrySet()) value.put(String.valueOf(item.getKey()), item.getValue()); result.put(text(raw.get("id")), value); } return result; }
    private Map<?, ?> map(Object value, String label) { if (!(value instanceof Map)) throw new IllegalArgumentException(label + "格式无效"); return (Map<?, ?>) value; }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private int number(Object value, int fallback) { if (value == null) return fallback; try { return Integer.parseInt(String.valueOf(value)); } catch (NumberFormatException e) { throw new IllegalArgumentException("limit 必须是整数"); } }
    private void validateTime(Map<String, Object> plan, Map<String, Object> semantics) {
        List<?> timeDimensions = list(semantics.get("timeDimensions"));
        if (plan.get("timeDimension") != null) {
            if (plan.get("timeRange") == null && plan.get("timeGranularity") == null) throw new IllegalArgumentException("timeDimension 需要配合时间范围或时间粒度");
            String id = text(plan.get("timeDimension")); boolean found = false;
            for (Object raw : timeDimensions) if (raw instanceof Map && id.equals(text(((Map<?, ?>) raw).get("id")))) { found = true; break; }
            if (!found) throw new IllegalArgumentException("时间维度不在已审核查询语义中：" + id);
        }
        Object rangeValue = plan.get("timeRange");
        if (rangeValue != null) {
            if (!(rangeValue instanceof Map)) throw new IllegalArgumentException("timeRange 必须是对象");
            Map<?, ?> range = (Map<?, ?>) rangeValue;
            String from = text(range.get("from")), to = text(range.get("to"));
            if (from.isEmpty() || to.isEmpty()) throw new IllegalArgumentException("timeRange 必须同时包含 from 和 to");
            try {
                LocalDate start = LocalDate.parse(from, DateTimeFormatter.ISO_LOCAL_DATE);
                LocalDate end = LocalDate.parse(to, DateTimeFormatter.ISO_LOCAL_DATE);
                if (start.isAfter(end)) throw new IllegalArgumentException("timeRange.from 不能晚于 to");
            } catch (DateTimeParseException e) { throw new IllegalArgumentException("timeRange 必须使用 YYYY-MM-DD 格式"); }
            if (timeDimensions.isEmpty()) throw new IllegalArgumentException("查询配置没有已审核时间维度");
        }
        if (plan.containsKey("timeGranularity")) {
            String granularity = text(plan.get("timeGranularity")).toUpperCase();
            if (!TIME_GRANULARITIES.contains(granularity)) throw new IllegalArgumentException("不支持的时间粒度：" + granularity);
            if (timeDimensions.isEmpty()) throw new IllegalArgumentException("查询配置没有已审核时间维度");
        }
    }

    private void validateOrderBy(Map<String, Object> plan, List<?> metrics, List<?> dimensions) {
        if (!plan.containsKey("orderBy")) return;
        if (!(plan.get("orderBy") instanceof List)) throw new IllegalArgumentException("orderBy 必须是数组");
        Set<String> allowed = new HashSet<>();
        for (Object metric : metrics) allowed.add(text(map(metric, "指标").get("id")));
        for (Object dimension : dimensions) allowed.add(text(map(dimension, "维度").get("id")));
        for (Object raw : list(plan.get("orderBy"))) {
            Map<?, ?> order = map(raw, "排序条件");
            String field = text(order.get("field"));
            if (!allowed.contains(field)) throw new IllegalArgumentException("排序字段必须引用当前查询指标或维度：" + field);
            String direction = text(order.get("direction")).toUpperCase();
            if (!("ASC".equals(direction) || "DESC".equals(direction))) throw new IllegalArgumentException("排序方向只能是 ASC 或 DESC");
        }
    }
    private Map<String, Object> mergedSemantics(Map<String, Object> config) { Map<String, Object> result = new java.util.LinkedHashMap<>(); Object legacy = config.get("querySemantics"); Object catalog = config.get("semanticCatalog"); if (legacy instanceof Map) for (Map.Entry<?, ?> entry : ((Map<?, ?>) legacy).entrySet()) result.put(String.valueOf(entry.getKey()), entry.getValue()); if (catalog instanceof Map) for (Map.Entry<?, ?> entry : ((Map<?, ?>) catalog).entrySet()) result.put(String.valueOf(entry.getKey()), entry.getValue()); return result; }
}

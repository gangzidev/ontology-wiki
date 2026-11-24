package com.ontologywiki.query.compiler;

import com.ontologywiki.query.catalog.SemanticCatalog;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

/** 将通过校验的查询计划编译为参数化只读 SQL，表名和列名全部来自配置。 */
@Service
public class QuerySqlCompiler {
    /** 编译结果：SQL 模板、参数和语义依据。 */
    public Result compile(Map<String, Object> plan, Map<String, Object> config, String databaseType) {
        String dialect = databaseType == null ? "mysql" : databaseType.toLowerCase(Locale.ROOT);
        if (!Arrays.asList("mysql", "postgres", "postgresql", "hive").contains(dialect)) throw new IllegalArgumentException("暂不支持该数据源 SQL 方言");
        List<Map<String, Object>> objects = rows(config.get("objects"));
        List<Map<String, Object>> properties = rows(config.get("properties"));
        Map<String, Object> semantics = mergedSemantics(config);
        List<Map<String, Object>> metrics = rows(semantics.get("metrics"));
        List<Map<String, Object>> dimensions = rows(semantics.get("dimensions"));
        List<Map<String, Object>> timeDimensions = rows(semantics.get("timeDimensions"));
        Map<String, Map<String, Object>> objectById = index(objects);
        Map<String, Map<String, Object>> propertyById = index(properties);
        Map<String, Map<String, Object>> metricById = index(metrics);
        Map<String, Map<String, Object>> dimensionById = index(dimensions);

        Map<String, String> aliases = new LinkedHashMap<>();
        List<String> fromObjects = new ArrayList<>();
        String detailObjectId = plan.get("detailObjectId") == null ? "" : String.valueOf(plan.get("detailObjectId"));
        if (!detailObjectId.isEmpty()) {
            if (!objectById.containsKey(detailObjectId)) throw new IllegalArgumentException("明细对象不存在：" + detailObjectId);
            alias(aliases, detailObjectId);
            fromObjects.add(detailObjectId);
        } else {
            for (Map<String, Object> metric : rows(plan.get("metrics"))) addMetricObjects(metricById.get(String.valueOf(metric.get("id"))), metricById, propertyById, objectById, aliases, fromObjects, new LinkedHashSet<String>());
        }
        for (Map<String, Object> dimension : rows(plan.get("dimensions"))) addObject(propertyById, objectById, dimensionById.get(String.valueOf(dimension.get("id"))).get("propertyId"), aliases, fromObjects);
        for (Map<String, Object> filter : rows(plan.get("filters"))) addObject(propertyById, objectById, filter.get("propertyId"), aliases, fromObjects);
        Map<String, Object> timeDimension = resolveTimeDimension(plan, timeDimensions, propertyById, fromObjects);
        if (timeDimension != null) addObject(propertyById, objectById, timeDimension.get("propertyId"), aliases, fromObjects);
        if (fromObjects.isEmpty()) throw new IllegalArgumentException("查询计划没有可用的数据对象");

        String baseId = fromObjects.get(0);
        StringBuilder sql = new StringBuilder("select ");
        List<String> selectParts = new ArrayList<>();
        List<String> groups = new ArrayList<>();
        Map<String, String> orderAliases = new LinkedHashMap<>();
        List<Map<String, Object>> selectedDimensions = new ArrayList<>(rows(plan.get("dimensions")));
        Map<String, Object> drilldown = plan.get("drilldown") instanceof Map ? (Map<String, Object>) plan.get("drilldown") : Collections.<String, Object>emptyMap();
        String nextDimensionId = drilldown.get("nextDimensionId") == null ? "" : String.valueOf(drilldown.get("nextDimensionId"));
        if (!nextDimensionId.isEmpty()) {
            boolean exists = false;
            for (Map<String, Object> item : selectedDimensions) if (nextDimensionId.equals(String.valueOf(item.get("id")))) exists = true;
            if (!exists) selectedDimensions.add(Collections.<String, Object>singletonMap("id", nextDimensionId));
            Map<String, Object> nextDimension = dimensionById.get(nextDimensionId);
            if (nextDimension != null) addObject(propertyById, objectById, nextDimension.get("propertyId"), aliases, fromObjects);
        }
        if (!detailObjectId.isEmpty()) {
            Set<String> selectedColumns = new LinkedHashSet<>();
            Set<String> selectedFields = new LinkedHashSet<>();
            for (Map<String, Object> field : rows(plan.get("fields"))) selectedFields.add(String.valueOf(field.get("id")));
            for (Map<String, Object> property : properties) {
                if (!detailObjectId.equals(String.valueOf(property.get("objectId")))) continue;
                String column = property.get("column") == null ? "" : String.valueOf(property.get("column"));
                if (column.trim().isEmpty()) continue;
                orderAliases.put(String.valueOf(property.get("id")), qualified(aliases, property, dialect));
                if (!selectedFields.isEmpty() && !selectedFields.contains(String.valueOf(property.get("id")))) continue;
                if (!selectedColumns.add(column.toLowerCase(Locale.ROOT))) continue;
                selectParts.add(qualified(aliases, property, dialect));
            }
            if (selectParts.isEmpty()) throw new IllegalArgumentException("明细对象没有可展示的字段：" + detailObjectId);
        } else {
            for (Map<String, Object> raw : selectedDimensions) {
                Map<String, Object> dimension = dimensionById.get(String.valueOf(raw.get("id")));
                Map<String, Object> property = propertyById.get(String.valueOf(dimension.get("propertyId")));
                String expression = qualified(aliases, property, dialect), alias = String.valueOf(dimension.get("id"));
                selectParts.add(expression + " as " + quote(alias, dialect)); groups.add(expression); orderAliases.put(alias, quote(alias, dialect));
            }
        }
        String granularity = plan.get("timeGranularity") == null ? null : String.valueOf(plan.get("timeGranularity")).toUpperCase(Locale.ROOT);
        if (granularity != null && timeDimension != null) {
            Map<String, Object> property = propertyById.get(String.valueOf(timeDimension.get("propertyId")));
            String expression = timeExpression(qualified(aliases, property, dialect), granularity, dialect), alias = "time_" + granularity.toLowerCase(Locale.ROOT);
            selectParts.add(expression + " as " + quote(alias, dialect)); groups.add(expression);
        }
        if (detailObjectId.isEmpty()) {
            for (Map<String, Object> raw : rows(plan.get("metrics"))) {
                Map<String, Object> metric = metricById.get(String.valueOf(raw.get("id")));
                String expression = metricExpression(metric, metricById, propertyById, aliases, dialect, new LinkedHashSet<String>());
                String alias = String.valueOf(metric.get("id"));
                selectParts.add(expression + " as " + quote(alias, dialect)); orderAliases.put(alias, quote(alias, dialect));
            }
        }
        sql.append(String.join(", ", selectParts));
        sql.append(" from ").append(tableName(objectById.get(baseId), dialect)).append(" ").append(aliases.get(baseId));
        List<Object> parameters = new ArrayList<>();
        // 关系属于语义目录时也必须参与编译，保证配置位置与校验器一致。
        List<Map<String, Object>> relations = rows(semantics.get("relations"));
        if (relations.isEmpty()) relations = rows(config.get("relations"));
        relations.removeIf(relation -> "m2mReification".equals(String.valueOf(relation.get("inferenceMethod"))));
        appendJoins(sql, objectById, aliases, baseId, fromObjects, relations, dialect);
        List<String> predicates = new ArrayList<>();
        for (Map<String, Object> filter : rows(plan.get("filters"))) {
            Map<String, Object> property = propertyById.get(String.valueOf(filter.get("propertyId")));
            String column = qualified(aliases, property, dialect);
            String op = String.valueOf(filter.get("operator")).toUpperCase(Locale.ROOT);
            if ("IS_NULL".equals(op)) predicates.add(column + " is null");
            else if ("IS_NOT_NULL".equals(op)) predicates.add(column + " is not null");
            else { predicates.add(column + " " + op + " ?"); parameters.add(filter.get("value")); }
        }
        if (!drilldown.isEmpty()) {
            Map<String, Object> dimension = dimensionById.get(String.valueOf(drilldown.get("dimensionId")));
            Map<String, Object> property = propertyById.get(String.valueOf(dimension.get("propertyId")));
            predicates.add(qualified(aliases, property, dialect) + " = ?");
            parameters.add(drilldown.get("value"));
        }
        if (timeDimension != null && plan.get("timeRange") instanceof Map) {
            Map<String, Object> property = propertyById.get(String.valueOf(timeDimension.get("propertyId")));
            String column = qualified(aliases, property, dialect);
            Map<?, ?> range = (Map<?, ?>) plan.get("timeRange");
            predicates.add(column + " >= ?"); parameters.add(java.sql.Date.valueOf(LocalDate.parse(String.valueOf(range.get("from")))));
            predicates.add(column + " < ?"); parameters.add(java.sql.Date.valueOf(LocalDate.parse(String.valueOf(range.get("to"))).plusDays(1)));
        }
        if (!predicates.isEmpty()) sql.append(" where ").append(String.join(" and ", predicates));
        if (!groups.isEmpty()) sql.append(" group by ").append(String.join(", ", groups));
        appendOrderBy(sql, plan.get("orderBy"), orderAliases);
        if (!detailObjectId.isEmpty() && rows(plan.get("orderBy")).isEmpty()) {
            List<String> ordering = new ArrayList<>();
            for (Map<String, Object> identity : rows(config.get("identitySpecs"))) {
                if (!detailObjectId.equals(String.valueOf(identity.get("objectId"))) || !(identity.get("columns") instanceof List)) continue;
                for (Object column : (List<?>) identity.get("columns")) for (Map<String, Object> property : properties)
                    if (detailObjectId.equals(String.valueOf(property.get("objectId"))) && String.valueOf(column).equals(String.valueOf(property.get("column"))))
                        ordering.add(qualified(aliases, property, dialect));
            }
            if (ordering.isEmpty()) for (Map<String, Object> property : properties) {
                String type = String.valueOf(property.getOrDefault("dataType", "")).toUpperCase(Locale.ROOT);
                if (detailObjectId.equals(String.valueOf(property.get("objectId"))) && type.matches(".*(INT|DECIMAL|NUMERIC|CHAR|DATE|TIME|BOOL).*")) {
                    ordering.add(qualified(aliases, property, dialect));
                }
            }
            if (!ordering.isEmpty()) sql.append(" order by ").append(String.join(", ", ordering));
        }
        sql.append(" limit ").append(Integer.parseInt(String.valueOf(plan.getOrDefault("limit", 100))));
        if (!detailObjectId.isEmpty()) sql.append(" offset ").append(Integer.parseInt(String.valueOf(plan.getOrDefault("offset", 0))));
        return new Result(sql.toString(), parameters, new ArrayList<>(fromObjects));
    }

    private void addMetricObjects(Map<String, Object> metric, Map<String, Map<String, Object>> metrics,
                                  Map<String, Map<String, Object>> properties, Map<String, Map<String, Object>> objects,
                                  Map<String, String> aliases, List<String> needed, Set<String> visiting) {
        if (metric == null) throw new IllegalArgumentException("查询指标定义不存在");
        String id = String.valueOf(metric.get("id"));
        if (!visiting.add(id)) throw new IllegalArgumentException("计算指标存在循环引用：" + id);
        String type = String.valueOf(metric.getOrDefault("type", "")).toUpperCase(Locale.ROOT);
        String aggregation = String.valueOf(metric.getOrDefault("aggregation", "")).toUpperCase(Locale.ROOT);
        if (!("COMPUTED".equals(type) || "COMPUTED".equals(aggregation) || metric.get("formula") instanceof Map)) {
            addObject(properties, objects, metric.get("propertyId"), aliases, needed);
        } else if (metric.get("formula") instanceof Map) {
            addFormulaObjects(metric.get("formula"), metrics, properties, objects, aliases, needed, visiting);
        }
        visiting.remove(id);
    }

    private void addFormulaObjects(Object raw, Map<String, Map<String, Object>> metrics,
                                   Map<String, Map<String, Object>> properties, Map<String, Map<String, Object>> objects,
                                   Map<String, String> aliases, List<String> needed, Set<String> visiting) {
        if (!(raw instanceof Map)) return;
        Map<?, ?> formula = (Map<?, ?>) raw;
        if (formula.get("metricId") != null) addMetricObjects(metrics.get(String.valueOf(formula.get("metricId"))), metrics, properties, objects, aliases, needed, visiting);
        if (formula.get("propertyId") != null) addObject(properties, objects, formula.get("propertyId"), aliases, needed);
        if (formula.get("left") != null) addFormulaObjects(formula.get("left"), metrics, properties, objects, aliases, needed, visiting);
        if (formula.get("right") != null) addFormulaObjects(formula.get("right"), metrics, properties, objects, aliases, needed, visiting);
    }

    private String metricExpression(Map<String, Object> metric, Map<String, Map<String, Object>> metrics,
                                    Map<String, Map<String, Object>> properties, Map<String, String> aliases,
                                    String dialect, Set<String> visiting) {
        if (metric == null) throw new IllegalArgumentException("查询指标定义不存在");
        String id = String.valueOf(metric.get("id"));
        if (!visiting.add(id)) throw new IllegalArgumentException("计算指标存在循环引用：" + id);
        String type = String.valueOf(metric.getOrDefault("type", "")).toUpperCase(Locale.ROOT);
        String aggregation = String.valueOf(metric.getOrDefault("aggregation", "")).toUpperCase(Locale.ROOT);
        String expression;
        if (!("COMPUTED".equals(type) || "COMPUTED".equals(aggregation) || metric.get("formula") instanceof Map)) {
            Map<String, Object> property = properties.get(String.valueOf(metric.get("propertyId")));
            expression = aggregation + "(" + qualified(aliases, property, dialect) + ")";
        } else {
            expression = formulaExpression(metric.get("formula"), metrics, properties, aliases, dialect, visiting);
        }
        visiting.remove(id);
        return expression;
    }

    private String formulaExpression(Object raw, Map<String, Map<String, Object>> metrics,
                                     Map<String, Map<String, Object>> properties, Map<String, String> aliases,
                                     String dialect, Set<String> visiting) {
        if (!(raw instanceof Map)) throw new IllegalArgumentException("计算指标公式无效");
        Map<?, ?> formula = (Map<?, ?>) raw;
        if (formula.get("metricId") != null) return metricExpression(metrics.get(String.valueOf(formula.get("metricId"))), metrics, properties, aliases, dialect, visiting);
        if (formula.get("propertyId") != null) {
            Map<String, Object> property = properties.get(String.valueOf(formula.get("propertyId")));
            String aggregation = String.valueOf(formula.containsKey("aggregation") ? formula.get("aggregation") : "SUM").toUpperCase(Locale.ROOT);
            return aggregation + "(" + qualified(aliases, property, dialect) + ")";
        }
        if (formula.get("value") != null) {
            Object value = formula.get("value");
            if (!(value instanceof Number)) throw new IllegalArgumentException("计算指标常量必须是数字");
            return String.valueOf(value);
        }
        String operator = String.valueOf(formula.get("operator")).toUpperCase(Locale.ROOT);
        if (Arrays.asList("SUM", "COUNT", "AVG", "MIN", "MAX").contains(operator)) {
            String operand = formulaExpression(formula.get("left"), metrics, properties, aliases, dialect, visiting);
            Object rawOperand = formula.get("left");
            if (rawOperand instanceof Map && ((Map<?, ?>) rawOperand).get("propertyId") != null) {
                Map<String, Object> property = properties.get(String.valueOf(((Map<?, ?>) rawOperand).get("propertyId")));
                operand = qualified(aliases, property, dialect);
            }
            return operator + "(" + operand + ")";
        }
        String left = formulaExpression(formula.get("left"), metrics, properties, aliases, dialect, visiting);
        String right = formulaExpression(formula.get("right"), metrics, properties, aliases, dialect, visiting);
        if ("ADD".equals(operator)) return "(" + left + " + " + right + ")";
        if ("SUBTRACT".equals(operator)) return "(" + left + " - " + right + ")";
        if ("MULTIPLY".equals(operator)) return "(" + left + " * " + right + ")";
        if ("DIVIDE".equals(operator) || "SAFE_DIVIDE".equals(operator)) return "(" + left + " / NULLIF(" + right + ", 0))";
        throw new IllegalArgumentException("不支持的计算指标操作：" + operator);
    }

    private Map<String, Object> resolveTimeDimension(Map<String, Object> plan, List<Map<String, Object>> timeDimensions,
                                                     Map<String, Map<String, Object>> properties, List<String> neededObjects) {
        if (plan.get("timeRange") == null && plan.get("timeGranularity") == null) return null;
        if (timeDimensions.isEmpty()) throw new IllegalArgumentException("查询配置没有可用时间维度");
        String explicitId = plan.get("timeDimension") == null ? null : String.valueOf(plan.get("timeDimension"));
        if (explicitId != null) for (Map<String, Object> candidate : timeDimensions) if (explicitId.equals(String.valueOf(candidate.get("id")))) return candidate;
        if (timeDimensions.size() == 1) return timeDimensions.get(0);
        String currentObject = neededObjects.isEmpty() ? null : neededObjects.get(0);
        Map<String, Object> selected = null;
        for (Map<String, Object> candidate : timeDimensions) {
            Map<String, Object> property = properties.get(String.valueOf(candidate.get("propertyId")));
            if (property != null && currentObject != null && currentObject.equals(String.valueOf(property.get("objectId")))) {
                if (selected != null) throw new IllegalArgumentException("查询计划对应多个时间维度，请先整理语义目录");
                selected = candidate;
            }
        }
        if (selected != null) return selected;
        for (Map<String, Object> candidate : timeDimensions) if (Boolean.TRUE.equals(candidate.get("default"))) return candidate;
        throw new IllegalArgumentException("查询计划对应多个时间维度，请先整理语义目录");
    }

    private void appendOrderBy(StringBuilder sql, Object value, Map<String, String> aliases) {
        List<Map<String, Object>> orders = rows(value); if (orders.isEmpty()) return;
        List<String> parts = new ArrayList<>();
        for (Map<String, Object> order : orders) {
            String field = String.valueOf(order.get("field")); String direction = String.valueOf(order.get("direction")).toUpperCase(Locale.ROOT);
            String alias = aliases.get(field); if (alias == null) throw new IllegalArgumentException("排序字段没有对应查询输出：" + field);
            if (!("ASC".equals(direction) || "DESC".equals(direction))) throw new IllegalArgumentException("排序方向不安全：" + direction);
            parts.add(alias + " " + direction);
        }
        sql.append(" order by ").append(String.join(", ", parts));
    }

    private String timeExpression(String column, String granularity, String dialect) {
        if ("postgres".equals(dialect) || "postgresql".equals(dialect)) return "date_trunc('" + postgresUnit(granularity) + "', " + column + ")";
        if ("hive".equals(dialect)) {
            if ("YEAR".equals(granularity)) return "date_format(" + column + ", 'yyyy')";
            if ("QUARTER".equals(granularity)) return "concat(year(" + column + "), '-Q', quarter(" + column + "))";
            if ("MONTH".equals(granularity)) return "date_format(" + column + ", 'yyyy-MM')";
            if ("WEEK".equals(granularity)) return "date_format(" + column + ", 'yyyy-ww')";
            return "date_format(" + column + ", 'yyyy-MM-dd')";
        }
        if ("YEAR".equals(granularity)) return "year(" + column + ")";
        if ("QUARTER".equals(granularity)) return "concat(year(" + column + "), '-Q', quarter(" + column + "))";
        if ("MONTH".equals(granularity)) return "date_format(" + column + ", '%Y-%m')";
        if ("WEEK".equals(granularity)) return "date_format(" + column + ", '%x-%v')";
        return "date_format(" + column + ", '%Y-%m-%d')";
    }

    private String postgresUnit(String granularity) {
        if ("DAY".equals(granularity)) return "day";
        if ("WEEK".equals(granularity)) return "week";
        if ("MONTH".equals(granularity)) return "month";
        if ("QUARTER".equals(granularity)) return "quarter";
        return "year";
    }

    private void appendJoins(StringBuilder sql, Map<String, Map<String, Object>> objects, Map<String, String> aliases, String baseId, List<String> needed, List<Map<String, Object>> relations, String dialect) {
        Set<String> joined = new LinkedHashSet<>(); joined.add(baseId);
        for (String target : new ArrayList<>(needed)) {
            if (joined.contains(target)) continue;
            List<Map<String, Object>> path = findUniquePath(baseId, target, relations);
            if (path.isEmpty()) throw new IllegalArgumentException("本体关系无法连接查询对象：" + target);
            for (Map<String, Object> relation : path) {
                if (relation.get("viaTable") != null || relation.get("viaObjectId") != null) {
                    appendManyToManyJoin(sql, objects, aliases, joined, relation, dialect);
                    Object viaObjectId = relation.get("viaObjectId");
                    if (viaObjectId != null && !needed.contains(String.valueOf(viaObjectId))) needed.add(String.valueOf(viaObjectId));
                    continue;
                }
                String source = String.valueOf(relation.get("sourceObjectId")), next = String.valueOf(relation.get("targetObjectId"));
                if (joined.contains(source) && joined.contains(next)) continue;
                if (joined.contains(next)) {
                    sql.append(" join ").append(tableName(objects.get(source), dialect)).append(" ").append(alias(aliases, source)).append(" on ").append(alias(aliases, source)).append('.').append(quote(String.valueOf(relation.get("sourceColumn")), dialect)).append(" = ").append(alias(aliases, next)).append('.').append(quote(String.valueOf(relation.get("targetColumn")), dialect));
                    joined.add(source); needed.add(source);
                } else {
                    sql.append(" join ").append(tableName(objects.get(next), dialect)).append(" ").append(alias(aliases, next)).append(" on ").append(alias(aliases, source)).append('.').append(quote(String.valueOf(relation.get("sourceColumn")), dialect)).append(" = ").append(alias(aliases, next)).append('.').append(quote(String.valueOf(relation.get("targetColumn")), dialect));
                    joined.add(next); needed.add(next);
                }
            }
        }
    }

    private void appendManyToManyJoin(StringBuilder sql, Map<String, Map<String, Object>> objects,
                                      Map<String, String> aliases,
                                      Set<String> joined, Map<String, Object> relation, String dialect) {
        String source = String.valueOf(relation.get("sourceObjectId"));
        String target = String.valueOf(relation.get("targetObjectId"));
        String viaId = relation.get("viaObjectId") == null ? null : String.valueOf(relation.get("viaObjectId"));
        String viaAliasKey = viaId == null || viaId.isEmpty() ? "__via__" + String.valueOf(relation.get("viaTable")) : viaId;
        Map<String, Object> viaObject = viaId == null ? null : objects.get(viaId);
        if (viaObject == null && relation.get("viaTable") == null) throw new IllegalArgumentException("多对多关系缺少中间表映射");
        String viaTable = viaObject == null ? quote(String.valueOf(relation.get("viaTable")), dialect) : tableName(viaObject, dialect);
        String viaAlias = alias(aliases, viaAliasKey);
        String sourceKey = endpointColumn(relation, "sourceKeyColumn", "sourceColumn");
        String targetKey = endpointColumn(relation, "targetKeyColumn", "targetColumn");
        String viaSource = endpointColumn(relation, "viaSourceColumn", "sourceColumn");
        String viaTarget = endpointColumn(relation, "viaTargetColumn", "targetColumn");
        if (joined.contains(source) && !joined.contains(target)) {
            sql.append(" join ").append(viaTable).append(" ").append(viaAlias)
                    .append(" on ").append(alias(aliases, source)).append('.').append(quote(sourceKey, dialect))
                    .append(" = ").append(viaAlias).append('.').append(quote(viaSource, dialect));
            joined.add(viaAliasKey);
            sql.append(" join ").append(tableName(objects.get(target), dialect)).append(" ").append(alias(aliases, target))
                    .append(" on ").append(viaAlias).append('.').append(quote(viaTarget, dialect))
                    .append(" = ").append(alias(aliases, target)).append('.').append(quote(targetKey, dialect));
            joined.add(target);
        } else if (joined.contains(target) && !joined.contains(source)) {
            sql.append(" join ").append(viaTable).append(" ").append(viaAlias)
                    .append(" on ").append(alias(aliases, target)).append('.').append(quote(targetKey, dialect))
                    .append(" = ").append(viaAlias).append('.').append(quote(viaTarget, dialect));
            joined.add(viaAliasKey);
            sql.append(" join ").append(tableName(objects.get(source), dialect)).append(" ").append(alias(aliases, source))
                    .append(" on ").append(viaAlias).append('.').append(quote(viaSource, dialect))
                    .append(" = ").append(alias(aliases, source)).append('.').append(quote(sourceKey, dialect));
            joined.add(source);
        }
    }

    private String endpointColumn(Map<String, Object> relation, String preferred, String fallback) {
        Object value = relation.get(preferred);
        if (value == null || String.valueOf(value).trim().isEmpty()) value = relation.get(fallback);
        if (value == null || String.valueOf(value).trim().isEmpty()) throw new IllegalArgumentException("多对多关系缺少字段映射：" + preferred);
        return String.valueOf(value);
    }

    private List<Map<String, Object>> findUniquePath(String start, String target, List<Map<String, Object>> relations) {
        Queue<PathState> queue = new ArrayDeque<>();
        queue.add(new PathState(start, new ArrayList<Map<String, Object>>(), new HashSet<>(Collections.singleton(start))));
        List<Map<String, Object>> result = null;
        while (!queue.isEmpty()) {
            PathState state = queue.remove();
            if (result != null && state.path.size() >= result.size()) continue;
            for (Map<String, Object> relation : relations) {
                String source = String.valueOf(relation.get("sourceObjectId")), destination = String.valueOf(relation.get("targetObjectId"));
                String next = state.current.equals(source) ? destination : state.current.equals(destination) ? source : null;
                if (next == null || state.visited.contains(next)) continue;
                List<Map<String, Object>> path = new ArrayList<>(state.path); path.add(relation);
                if (next.equals(target)) {
                    if (result != null && result.size() == path.size()) throw new IllegalArgumentException("查询对象之间存在多条等长本体关系路径，无法唯一确定连接方式");
                    result = path;
                } else {
                    Set<String> visited = new HashSet<>(state.visited); visited.add(next);
                    queue.add(new PathState(next, path, visited));
                }
            }
        }
        return result == null ? Collections.<Map<String, Object>>emptyList() : result;
    }

    private String alias(Map<String, String> aliases, String objectId) { if (!aliases.containsKey(objectId)) aliases.put(objectId, "t" + aliases.size()); return aliases.get(objectId); }
    private static class PathState { final String current; final List<Map<String, Object>> path; final Set<String> visited; PathState(String current, List<Map<String, Object>> path, Set<String> visited) { this.current = current; this.path = path; this.visited = visited; } }

    private void addObject(Map<String, Map<String, Object>> properties, Map<String, Map<String, Object>> objects, Object propertyId, Map<String, String> aliases, List<String> needed) { Map<String, Object> property = properties.get(String.valueOf(propertyId)); if (property == null) throw new IllegalArgumentException("属性映射不存在：" + propertyId); String objectId = String.valueOf(property.get("objectId")); if (!objects.containsKey(objectId)) throw new IllegalArgumentException("属性所属对象不存在：" + objectId); if (!aliases.containsKey(objectId)) aliases.put(objectId, "t" + aliases.size()); if (!needed.contains(objectId)) needed.add(objectId); }
    private String qualified(Map<String, String> aliases, Map<String, Object> property, String dialect) { String objectId = String.valueOf(property.get("objectId")); return aliases.get(objectId) + "." + quote(String.valueOf(property.get("column")), dialect); }
    private String tableName(Map<String, Object> object, String dialect) { if (object == null || object.get("table") == null) throw new IllegalArgumentException("对象缺少物理表映射"); String schema = object.get("schema") == null ? "" : String.valueOf(object.get("schema")).trim(); return (schema.isEmpty() ? "" : quote(schema, dialect) + ".") + quote(String.valueOf(object.get("table")), dialect); }
    private String quote(String identifier, String dialect) { if (!identifier.matches("[A-Za-z0-9_$-]+")) throw new IllegalArgumentException("物理标识符不安全：" + identifier); String q = "postgres".equals(dialect) || "postgresql".equals(dialect) ? "\"" : "`"; return q + identifier + q; }
    private List<Map<String, Object>> rows(Object value) { List<Map<String, Object>> result = new ArrayList<>(); if (value instanceof List) for (Object row : (List<?>) value) if (row instanceof Map) result.add((Map<String, Object>) row); return result; }
    private Map<String, Map<String, Object>> index(List<Map<String, Object>> rows) { Map<String, Map<String, Object>> result = new LinkedHashMap<>(); for (Map<String, Object> row : rows) result.put(String.valueOf(row.get("id")), row); return result; }
    private Map<String, Object> mergedSemantics(Map<String, Object> config) { return SemanticCatalog.from(config); }

    /** 参数化 SQL 编译结果。 */
    public static class Result { private final String sql; private final List<Object> parameters; private final List<String> objectIds; public Result(String sql, List<Object> parameters, List<String> objectIds) { this.sql = sql; this.parameters = parameters; this.objectIds = objectIds; } public String getSql() { return sql; } public List<Object> getParameters() { return parameters; } public List<String> getObjectIds() { return objectIds; } }
}

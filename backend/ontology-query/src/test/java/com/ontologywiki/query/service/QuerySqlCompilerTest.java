package com.ontologywiki.query.service;

import com.ontologywiki.query.compiler.QuerySqlCompiler;
import com.ontologywiki.query.validation.QueryPlanValidator;
import org.junit.Assert;
import org.junit.Test;

import java.util.*;

/** 查询编译器只验证白名单和参数化，不连接真实业务库。 */
public class QuerySqlCompilerTest {
    @Test
    public void compilesParameterizedAggregateWithJoin() {
        Map<String, Object> config = config();
        Map<String, Object> plan = row("metrics", Collections.singletonList(row("id", "revenue")), "dimensions", Collections.singletonList(row("id", "region")), "filters", Collections.singletonList(row("propertyId", "status", "operator", "=", "value", "PAID")), "limit", 20);
        new QueryPlanValidator().validate(plan, config);
        QuerySqlCompiler.Result result = new QuerySqlCompiler().compile(plan, config, "mysql");
        Assert.assertTrue(result.getSql().toLowerCase(Locale.ROOT).contains("sum("));
        Assert.assertTrue(result.getSql().contains(" join "));
        Assert.assertTrue(result.getSql().contains(" where "));
        Assert.assertFalse(result.getSql().contains("PAID"));
        Assert.assertEquals(Collections.singletonList("PAID"), result.getParameters());
    }

    @Test
    public void readsRelationsFromSemanticCatalog() {
        Map<String, Object> config = config();
        config.remove("relations");
        ((Map<String, Object>) config.get("querySemantics")).put("relations",
                Collections.singletonList(row("sourceObjectId", "orders", "targetObjectId", "regions",
                        "sourceColumn", "region_id", "targetColumn", "id")));
        Map<String, Object> plan = row("metrics", Collections.singletonList(row("id", "revenue")),
                "dimensions", Collections.singletonList(row("id", "region")));
        new QueryPlanValidator().validate(plan, config);
        Assert.assertTrue(new QuerySqlCompiler().compile(plan, config, "mysql").getSql().contains(" join "));
    }

    @Test
    public void compilesReviewedObjectDetailRows() {
        Map<String, Object> config = row("objects", Collections.singletonList(row("id", "users", "table", "sys_user")),
                "properties", Arrays.asList(row("id", "userId", "objectId", "users", "column", "user_id", "dataType", "BIGINT"),
                        row("id", "userName", "objectId", "users", "column", "user_name", "dataType", "VARCHAR")),
                "querySemantics", row("metrics", Collections.emptyList(), "dimensions", Collections.emptyList()));
        Map<String, Object> plan = row("detailObjectId", "users", "limit", 25);
        new QueryPlanValidator().validate(plan, config);
        QuerySqlCompiler.Result result = new QuerySqlCompiler().compile(plan, config, "mysql");
        Assert.assertTrue(result.getSql().toLowerCase(Locale.ROOT).startsWith("select t0.`user_id`, t0.`user_name` from `sys_user`"));
        Assert.assertTrue(result.getSql().toLowerCase(Locale.ROOT).contains("limit 25"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnknownMetric() {
        new QueryPlanValidator().validate(Collections.<String, Object>singletonMap("metrics", Collections.singletonList(row("id", "unknown"))), config());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnsafePhysicalIdentifier() {
        Map<String, Object> config = config();
        ((Map<String, Object>) ((List<?>) config.get("objects")).get(0)).put("table", "orders; delete");
        Map<String, Object> plan = row("metrics", Collections.singletonList(row("id", "revenue")));
        new QueryPlanValidator().validate(plan, config);
        new QuerySqlCompiler().compile(plan, config, "mysql");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsAmbiguousRelationPaths() {
        Map<String, Object> config = config();
        List<Map<String, Object>> relations = new ArrayList<>((List<Map<String, Object>>) config.get("relations"));
        relations.add(row("sourceObjectId", "orders", "targetObjectId", "regions", "sourceColumn", "other_region_id", "targetColumn", "id"));
        config.put("relations", relations);
        Map<String, Object> plan = row("metrics", Collections.singletonList(row("id", "revenue")), "dimensions", Collections.singletonList(row("id", "region")));
        new QueryPlanValidator().validate(plan, config);
        new QuerySqlCompiler().compile(plan, config, "mysql");
    }

    @Test
    public void compilesParameterizedTimeRangeGranularityAndTopN() {
        Map<String, Object> config = config();
        List<Map<String, Object>> properties = new ArrayList<>((List<Map<String, Object>>) config.get("properties"));
        properties.add(row("id", "createdAt", "objectId", "orders", "column", "created_at", "dataType", "DATETIME")); config.put("properties", properties);
        ((Map<String, Object>) config.get("querySemantics")).put("timeDimensions",
                Collections.singletonList(row("id", "order-created", "name", "下单时间", "propertyId", "createdAt", "default", true)));
        Map<String, Object> plan = row("metrics", Collections.singletonList(row("id", "revenue")),
                "dimensions", Collections.singletonList(row("id", "region")),
                "timeRange", row("from", "2026-01-01", "to", "2026-12-31"),
                "timeGranularity", "MONTH",
                "orderBy", Collections.singletonList(row("field", "revenue", "direction", "DESC")), "limit", 10);
        new QueryPlanValidator().validate(plan, config);
        QuerySqlCompiler.Result result = new QuerySqlCompiler().compile(plan, config, "mysql");
        String sql = result.getSql().toLowerCase(Locale.ROOT);
        Assert.assertTrue(sql.contains("date_format"));
        Assert.assertTrue(sql.contains("group by"));
        Assert.assertTrue(sql.contains("order by `revenue` desc"));
        Assert.assertFalse(sql.contains("2026-01-01"));
        Assert.assertEquals(Arrays.asList(java.sql.Date.valueOf("2026-01-01"), java.sql.Date.valueOf("2027-01-01")), result.getParameters());
    }

    @Test
    public void compilesReviewedComputedMetricFormula() {
        Map<String, Object> config = config();
        List<Map<String, Object>> properties = new ArrayList<>((List<Map<String, Object>>) config.get("properties"));
        properties.add(row("id", "cost", "objectId", "orders", "column", "cost", "dataType", "DECIMAL"));
        config.put("properties", properties);
        Map<String, Object> semantics = (Map<String, Object>) config.get("querySemantics");
        List<Map<String, Object>> metrics = new ArrayList<>((List<Map<String, Object>>) semantics.get("metrics"));
        metrics.add(row("id", "profit", "name", "利润", "type", "COMPUTED", "formula",
                row("operator", "SUBTRACT", "left", row("metricId", "revenue"), "right", row("propertyId", "cost", "aggregation", "SUM"))));
        semantics.put("metrics", metrics);
        Map<String, Object> plan = row("metrics", Collections.singletonList(row("id", "profit")), "limit", 10);
        new QueryPlanValidator().validate(plan, config);
        String sql = new QuerySqlCompiler().compile(plan, config, "mysql").getSql().toLowerCase(Locale.ROOT);
        Assert.assertTrue(sql.contains("sum(t0.`amount`)") && sql.contains("sum(t0.`cost`)") && sql.contains(" - "));
    }

    @Test
    public void compilesManyToManyJoinTableRelation() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("objects", Arrays.asList(
                row("id", "users", "table", "users"),
                row("id", "roles", "table", "roles"),
                row("id", "userRole", "table", "user_role")));
        config.put("properties", Arrays.asList(
                row("id", "userId", "objectId", "users", "column", "id", "dataType", "BIGINT"),
                row("id", "roleId", "objectId", "roles", "column", "id", "dataType", "BIGINT"),
                row("id", "roleName", "objectId", "roles", "column", "name", "dataType", "VARCHAR"),
                row("id", "userRoleUserId", "objectId", "userRole", "column", "user_id", "dataType", "BIGINT"),
                row("id", "userRoleRoleId", "objectId", "userRole", "column", "role_id", "dataType", "BIGINT"),
                row("id", "userCount", "objectId", "users", "column", "id", "dataType", "BIGINT")));
        config.put("relations", Collections.singletonList(row(
                "id", "user-role", "sourceObjectId", "users", "targetObjectId", "roles",
                "sourceColumn", "user_id", "targetColumn", "role_id",
                "sourceKeyColumn", "id", "targetKeyColumn", "id",
                "viaTable", "user_role", "viaObjectId", "userRole",
                "viaSourceColumn", "user_id", "viaTargetColumn", "role_id",
                "cardinality", "MANY_TO_MANY")));
        config.put("querySemantics", row(
                "metrics", Collections.singletonList(row("id", "user-count", "name", "用户数", "propertyId", "userCount", "aggregation", "COUNT")),
                "dimensions", Collections.singletonList(row("id", "role", "name", "角色", "propertyId", "roleName"))));
        Map<String, Object> plan = row("metrics", Collections.singletonList(row("id", "user-count")), "dimensions", Collections.singletonList(row("id", "role")));
        new QueryPlanValidator().validate(plan, config);
        String sql = new QuerySqlCompiler().compile(plan, config, "mysql").getSql().toLowerCase(Locale.ROOT);
        Assert.assertTrue(sql.contains("join `user_role`"));
        Assert.assertTrue(sql.contains("t0.`id` = t2.`user_id`"));
        Assert.assertTrue(sql.contains("t2.`role_id` = t1.`id`"));
    }

    @Test
    public void compilesAggregateComputedMetricFormula() {
        Map<String, Object> config = config();
        Map<String, Object> semantics = (Map<String, Object>) config.get("querySemantics");
        List<Map<String, Object>> metrics = new ArrayList<>((List<Map<String, Object>>) semantics.get("metrics"));
        metrics.add(row("id", "gross", "name", "总收入", "type", "COMPUTED", "formula",
                row("operator", "SUM", "left", row("propertyId", "amount"))));
        semantics.put("metrics", metrics);
        Map<String, Object> plan = row("metrics", Collections.singletonList(row("id", "gross")), "limit", 10);
        new QueryPlanValidator().validate(plan, config);
        String sql = new QuerySqlCompiler().compile(plan, config, "mysql").getSql().toLowerCase(Locale.ROOT);
        Assert.assertTrue(sql.contains("sum(t0.`amount`)") && !sql.contains("sum(sum("));
    }

    @Test
    public void rejectsComparisonWithoutTimeContext() {
        Map<String, Object> plan = row("metrics", Collections.singletonList(row("id", "revenue")),
                "comparison", row("type", "YOY", "periods", 1));
        try {
            new QueryPlanValidator().validate(plan, config());
            Assert.fail("同比必须有时间上下文");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("时间范围"));
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsOrderByFieldOutsidePlan() {
        Map<String, Object> plan = row("metrics", Collections.singletonList(row("id", "revenue")),
                "orderBy", Collections.singletonList(row("field", "unknown", "direction", "DESC")));
        new QueryPlanValidator().validate(plan, config());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInvalidTimeGranularity() {
        Map<String, Object> config = config();
        List<Map<String, Object>> properties = new ArrayList<>((List<Map<String, Object>>) config.get("properties"));
        properties.add(row("id", "createdAt", "objectId", "orders", "column", "created_at", "dataType", "DATE")); config.put("properties", properties);
        ((Map<String, Object>) config.get("querySemantics")).put("timeDimensions",
                Collections.singletonList(row("id", "order-created", "name", "下单时间", "propertyId", "createdAt")));
        new QueryPlanValidator().validate(row("metrics", Collections.singletonList(row("id", "revenue")), "timeGranularity", "HOUR"), config);
    }

    @Test
    public void rejectsInvalidDetailPlansBeforeCompilation() {
        for (Map<String, Object> plan : Arrays.asList(
                row("detailObjectId", "missing"),
                row("detailObjectId", "orders", "timeGranularity", "MONTH"),
                row("detailObjectId", "orders", "dimensions", Collections.singletonList(row("id", "region"))),
                row("detailObjectId", "orders", "fields", Collections.singletonList(row("id", "regionName"))),
                row("detailObjectId", "orders", "offset", -1),
                row("detailObjectId", "orders", "limit", 1001),
                row("detailObjectId", "orders", "fields", Collections.emptyList()))) {
            try { new QueryPlanValidator().validate(plan, config()); Assert.fail("Invalid detail plan accepted: " + plan); }
            catch (IllegalArgumentException expected) { }
        }
    }

    private Map<String, Object> config() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("objects", Arrays.asList(row("id", "orders", "table", "orders"), row("id", "regions", "table", "regions")));
        config.put("properties", Arrays.asList(row("id", "amount", "objectId", "orders", "column", "amount", "dataType", "DECIMAL"), row("id", "status", "objectId", "orders", "column", "status", "dataType", "VARCHAR"), row("id", "regionId", "objectId", "orders", "column", "region_id", "dataType", "BIGINT"), row("id", "regionName", "objectId", "regions", "column", "name", "dataType", "VARCHAR"), row("id", "regionPk", "objectId", "regions", "column", "id", "dataType", "BIGINT")));
        config.put("relations", Collections.singletonList(row("sourceObjectId", "orders", "targetObjectId", "regions", "sourceColumn", "region_id", "targetColumn", "id")));
        config.put("querySemantics", row("metrics", Collections.singletonList(row("id", "revenue", "name", "收入", "propertyId", "amount", "aggregation", "SUM")), "dimensions", Collections.singletonList(row("id", "region", "name", "地区", "propertyId", "regionName"))));
        return config;
    }

    private Map<String, Object> row(Object... values) { Map<String, Object> row = new LinkedHashMap<>(); for (int i = 0; i < values.length; i += 2) row.put(String.valueOf(values[i]), values[i + 1]); return row; }
}

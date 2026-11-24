package com.ontologywiki.ontology.service;

import org.junit.Assert;
import org.junit.Test;

import java.util.*;

public class OntologyGenerationEngineQualityTest {
    @Test
    public void lowQualityTablesAreReportedButExcludedFromCandidates() {
        OntologyGenerationEngine engine = new OntologyGenerationEngine();
        Map<String,Object> table = table("orders", "订单", true);
        Map<String,Object> weak = table("scratch", null, false);
        Map<String,Object> result = engine.generate(schema(table, weak), Collections.<Map<String,Object>>emptyList(),
                Collections.<Map<String,Object>>emptyList(), Collections.<String>emptyList(), Collections.<Integer>emptySet());

        Map<?,?> stage1 = (Map<?,?>) ((Map<?,?>) ((List<?>) result.get("stageReports")).get(0)).get("result");
        Assert.assertEquals(2, stage1.get("tableCount"));
        Assert.assertEquals(1L, stage1.get("processableTableCount"));
        Assert.assertEquals(1, ((List<?>) result.get("objects")).size());
        Assert.assertTrue(String.valueOf(result.get("warnings")).contains("LOW_QUALITY_TABLE"));
    }

    @Test
    public void missingCommentsKeepPhysicalTableAndColumnNames() {
        OntologyGenerationEngine engine = new OntologyGenerationEngine();
        Map<String,Object> result = engine.generate(schema(table("analysis_task", null, true)),
                Collections.<Map<String,Object>>emptyList(), Collections.<Map<String,Object>>emptyList(),
                Collections.<String>emptyList(), Collections.<Integer>emptySet());

        Assert.assertEquals("analysis_task", ((Map<?,?>) ((List<?>) result.get("objects")).get(0)).get("name"));
        Assert.assertEquals("id", ((Map<?,?>) ((List<?>) result.get("properties")).get(0)).get("name"));
    }

    @Test
    public void taxonomyCyclesAreRejectedAndReported() {
        OntologyGenerationEngine engine = new OntologyGenerationEngine();
        Map<String,Object> alpha = table("alpha", "Alpha", true);
        Map<String,Object> beta = table("beta", "Beta", true);
        Map<String,Object> doc = new LinkedHashMap<>();
        doc.put("sourceId", 1L); doc.put("content", "Alpha 属于 Beta。Beta 属于 Alpha。");
        Map<String,Object> result = engine.generate(schema(alpha, beta), Collections.singletonList(doc),
                Collections.<Map<String,Object>>emptyList(), Collections.<String>emptyList(), Collections.<Integer>emptySet());

        Map<?,?> taxonomy = (Map<?,?>) result.get("taxonomy");
        Assert.assertEquals(1, ((List<?>) taxonomy.get("edges")).size());
        Assert.assertTrue(String.valueOf(result.get("warnings")).contains("TAXONOMY_CYCLE"));
    }

    @Test
    public void explicitWikiAliasesPoliciesAndColumnFormulasBecomeReviewCandidates() {
        OntologyGenerationEngine engine = new OntologyGenerationEngine();
        Map<String,Object> table = new LinkedHashMap<>(); table.put("name", "customer"); table.put("comment", "客户");
        table.put("primaryKeys", Collections.singletonList("id")); table.put("foreignKeys", Collections.emptyList());
        List<Map<String,Object>> columns = new ArrayList<>();
        columns.add(column("id", null, false)); columns.add(column("total_amount", "计算公式：quantity * unit_price", true)); table.put("columns", columns);
        Map<String,Object> doc = new LinkedHashMap<>(); doc.put("sourceId", 9L); doc.put("content", "# 客户\n别名：customer, buyer\n必须保留客户原始编号。");

        Map<String,Object> result = engine.generate(schema(table), Collections.singletonList(doc),
                Collections.<Map<String,Object>>emptyList(), Collections.<String>emptyList(), Collections.<Integer>emptySet());

        Assert.assertEquals("quantity * unit_price", ((Map<?,?>) ((List<?>) result.get("derivedProperties")).get(0)).get("formula"));
        Assert.assertEquals(1, ((List<?>) result.get("semanticMappings")).size());
        Assert.assertEquals(1, ((List<?>) result.get("governanceRules")).size());
        Assert.assertTrue(((Map<?,?>) ((List<?>) result.get("governanceRules")).get(0)).containsKey("reviewRequired"));
    }

    @Test
    public void incrementalSnapshotReplacesSelectedTablesAndKeepsUnchangedMetadata() {
        OntologyGenerationEngine engine = new OntologyGenerationEngine();
        Map<String,Object> oldOrders = table("orders", "旧订单", true); oldOrders.put("samples", Collections.singletonList(Collections.singletonMap("id", 1)));
        Map<String,Object> oldColumn = column("id", null, false); oldColumn.put("default", "sensitive-default"); oldOrders.put("columns", Collections.singletonList(oldColumn));
        Map<String,Object> oldCustomers = table("customers", "客户", true);
        Map<String,Object> oldArchived = table("archived", "已删除表", true);
        Map<String,Object> snapshot = engine.schemaMetadataSnapshot(schema(oldOrders, oldCustomers, oldArchived));
        Map<String,Object> scannedOrders = table("orders", "新订单", true);
        Map<String,Object> scannedLines = table("order_lines", "订单明细", true);
        Map<String,Object> updated = engine.mergeSchemaSnapshot(snapshot, schema(scannedOrders, scannedLines),
                new HashSet<>(Arrays.asList("orders", "order_lines", "archived")));

        List<Map<String,Object>> tables = (List<Map<String,Object>>) updated.get("tables");
        Assert.assertEquals(3, tables.size());
        Assert.assertFalse(tables.get(0).containsKey("samples"));
        Assert.assertFalse(((Map<?,?>) ((List<?>) tables.get(0).get("columns")).get(0)).containsKey("default"));
        Assert.assertEquals("客户", tables.get(0).get("comment"));
        Assert.assertEquals("新订单", tables.get(1).get("comment"));
        Assert.assertEquals("order_lines", tables.get(2).get("name"));
        try {
            engine.mergeSchemaSnapshot(snapshot, schema(), Collections.singleton("typo"));
            Assert.fail("Unknown incremental table should be rejected.");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("变化表未出现"));
        }
    }

    @Test
    public void incrementalSnapshotUsesSchemaQualifiedTableIdentity() {
        OntologyGenerationEngine engine = new OntologyGenerationEngine();
        Map<String,Object> firstSchema = table("orders", "Schema A", true);
        firstSchema.put("schema", "a");
        Map<String,Object> secondSchema = table("orders", "Schema B", true);
        secondSchema.put("schema", "b");
        Map<String,Object> snapshot = engine.schemaMetadataSnapshot(schema(firstSchema, secondSchema));
        Map<String,Object> updatedTable = table("orders", "Updated Schema A", true);
        updatedTable.put("schema", "a");

        Map<String,Object> updated = engine.mergeSchemaSnapshot(snapshot, schema(updatedTable), Collections.singleton("a.orders"));
        List<Map<String,Object>> tables = (List<Map<String,Object>>) updated.get("tables");
        Assert.assertEquals(2, tables.size());
        Map<String,Object> updatedBySchema = new HashMap<>();
        for (Map<String,Object> table : tables) updatedBySchema.put(String.valueOf(table.get("schema")), table);
        Assert.assertEquals("Updated Schema A", ((Map<?,?>) updatedBySchema.get("a")).get("comment"));
        Assert.assertEquals("Schema B", ((Map<?,?>) updatedBySchema.get("b")).get("comment"));
    }

    @Test
    public void sqlPatternsProduceReviewableActionFlowAndPermissionCandidates() {
        OntologyGenerationEngine engine = new OntologyGenerationEngine();
        Map<String,Object> orderTable = table("orders", "订单", true);
        Map<String,Object> result = engine.generate(schema(orderTable), Collections.<Map<String,Object>>emptyList(),
                Collections.<Map<String,Object>>emptyList(), Collections.singletonList("UPDATE orders SET status='PAID' WHERE id=1"), Collections.<Integer>emptySet());

        Assert.assertEquals("UPDATE", ((Map<?,?>) ((List<?>) result.get("actions")).get(0)).get("operation"));
        Assert.assertEquals(1, ((List<?>) result.get("serviceFlows")).size());
        Assert.assertEquals(1, ((List<?>) result.get("permissionCandidates")).size());
        Assert.assertEquals("NEEDS_REVIEW", ((Map<?,?>) ((List<?>) result.get("permissionCandidates")).get(0)).get("status"));
    }

    @Test
    public void generatesReviewableMetricDimensionAndTimeCandidates() {
        OntologyGenerationEngine engine = new OntologyGenerationEngine();
        Map<String,Object> table = new LinkedHashMap<>();
        table.put("name", "orders"); table.put("comment", "订单"); table.put("primaryKeys", Collections.singletonList("id"));
        table.put("foreignKeys", Collections.emptyList());
        table.put("columns", Arrays.asList(columnWithType("id", "BIGINT"), columnWithType("amount", "DECIMAL"),
                columnWithType("status", "VARCHAR"), columnWithType("created_at", "DATETIME")));
        table.put("samples", Arrays.asList(sample("id", 1, "amount", 10, "status", "PAID", "created_at", "2026-01-01"),
                sample("id", 2, "amount", 20, "status", "NEW", "created_at", "2026-01-02")));
        Map<String,Object> result = engine.generate(schema(table), Collections.<Map<String,Object>>emptyList(),
                Collections.<Map<String,Object>>emptyList(), Collections.<String>emptyList(), Collections.<Integer>emptySet());
        Map<?,?> catalog = (Map<?,?>) result.get("semanticCatalog");
        Map<?,?> amount = find((List<?>) catalog.get("metrics"), "propertyId", "property_orders_amount");
        Assert.assertEquals("SUM", amount.get("aggregation"));
        Assert.assertEquals("NEEDS_REVIEW", amount.get("status"));
        Assert.assertFalse(find((List<?>) catalog.get("dimensions"), "propertyId", "property_orders_status").isEmpty());
        Assert.assertFalse(find((List<?>) catalog.get("timeDimensions"), "propertyId", "property_orders_created_at").isEmpty());
        Assert.assertEquals(catalog, ((Map<?,?>) result.get("targetConfig")).get("semanticCatalog"));
    }

    @Test
    public void numericFlagsAndEnumsAreDimensionsAndPrimaryKeyCountsTheObject() {
        Map<String, Object> table = table("sys_menu", "菜单", true);
        table.put("primaryKeys", Collections.singletonList("menu_id"));
        Map<String, Object> flag = columnWithType("is_frame", "INT(1)");
        flag.put("comment", "是否为外链（0是 1否）");
        Map<String, Object> category = columnWithType("menu_type", "TINYINT");
        category.put("comment", "菜单类型（0目录 1菜单）");
        table.put("columns", Arrays.asList(columnWithType("menu_id", "BIGINT"), flag, category,
                columnWithType("parent_id", "BIGINT"), columnWithType("amount", "DECIMAL")));
        Map<String, Object> result = new OntologyGenerationEngine().generate(schema(table), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), Collections.emptySet());
        Map<?, ?> catalog = (Map<?, ?>) result.get("semanticCatalog");
        List<?> metrics = (List<?>) catalog.get("metrics");
        Assert.assertTrue(find(metrics, "propertyId", "property_sys_menu_is_frame").isEmpty());
        Assert.assertTrue(find(metrics, "propertyId", "property_sys_menu_menu_type").isEmpty());
        Assert.assertTrue(find(metrics, "propertyId", "property_sys_menu_parent_id").isEmpty());
        Map<?, ?> count = find(metrics, "propertyId", "property_sys_menu_menu_id");
        Assert.assertEquals("COUNT", count.get("aggregation"));
        Assert.assertEquals("菜单数量", count.get("name"));
        Map<?, ?> dimension = find((List<?>) catalog.get("dimensions"), "propertyId", "property_sys_menu_is_frame");
        Assert.assertEquals("是否为外链", dimension.get("name"));
        Assert.assertEquals("是否为外链（0是 1否）", dimension.get("definition"));
    }

    private Map<String,Object> column(String name, String comment, boolean nullable) {
        Map<String,Object> column = new LinkedHashMap<>(); column.put("name", name); column.put("comment", comment); column.put("nullable", nullable); return column;
    }

    private Map<String,Object> columnWithType(String name, String type) {
        Map<String,Object> column = new LinkedHashMap<>(); column.put("name", name); column.put("type", type); column.put("nullable", true); return column;
    }

    private Map<?,?> find(List<?> rows, String key, String value) {
        for (Object row : rows) if (row instanceof Map && value.equals(String.valueOf(((Map<?,?>) row).get(key)))) return (Map<?,?>) row;
        return Collections.emptyMap();
    }

    private Map<String,Object> sample(Object... values) {
        Map<String,Object> row = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) row.put(String.valueOf(values[i]), values[i + 1]);
        return row;
    }

    private Map<String,Object> schema(Map<String,Object>... tables) {
        Map<String,Object> schema = new LinkedHashMap<>(); schema.put("tables", Arrays.asList(tables)); return schema;
    }

    private Map<String,Object> table(String name, String comment, boolean withColumns) {
        Map<String,Object> table = new LinkedHashMap<>(); table.put("name", name); table.put("comment", comment);
        table.put("columns", withColumns ? Collections.singletonList(Collections.<String,Object>singletonMap("name", "id")) : Collections.emptyList());
        table.put("primaryKeys", withColumns ? Collections.singletonList("id") : Collections.emptyList()); table.put("foreignKeys", Collections.emptyList()); return table;
    }
}

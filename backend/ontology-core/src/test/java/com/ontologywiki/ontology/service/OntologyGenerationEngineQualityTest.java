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

    private Map<String,Object> column(String name, String comment, boolean nullable) {
        Map<String,Object> column = new LinkedHashMap<>(); column.put("name", name); column.put("comment", comment); column.put("nullable", nullable); return column;
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

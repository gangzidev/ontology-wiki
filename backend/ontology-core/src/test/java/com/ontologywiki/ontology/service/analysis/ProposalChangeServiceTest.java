package com.ontologywiki.ontology.service.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ontologywiki.ontology.service.OntologyArtifactExporter;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class ProposalChangeServiceTest {
    private final ProposalChangeService changes = new ProposalChangeService();

    @Test
    public void synchronizesConversationChangesIntoExportMappingAndLegacyViews() throws Exception {
        Map<String,Object> base = config();
        List<Map<String,Object>> requested = Arrays.asList(update("objects", "orders", "name", "销售订单"),
                update("properties", "amount", "column", "net_amount"), update("properties", "amount", "name", "实收金额"),
                update("metrics", "revenue", "name", "实收总额"), update("metrics", "revenue", "synonyms", Arrays.asList("营收", "销售额")));
        Map<String,Object> result = changes.applyAnalysisChanges(base, changes.normalizeAnalysisChanges(base, requested, true));
        assertEquals("订单", rows(base.get("objects")).get(0).get("name"));
        assertEquals("销售订单", rows(map(result.get("targetConfig")).get("objects")).get(0).get("name"));
        assertEquals("实收总额", rows(map(map(result.get("targetConfig")).get("semanticCatalog")).get("metrics")).get(0).get("name"));
        assertEquals("实收总额", rows(map(result.get("querySemantics")).get("metrics")).get(0).get("name"));
        assertEquals(Arrays.asList("营收", "销售额"), rows(map(result.get("semanticCatalog")).get("metrics")).get(0).get("synonyms"));
        assertEquals("net_amount", rows(result.get("mappings")).get(0).get("column"));
        assertEquals("销售订单", rows(map(result.get("taxonomy")).get("nodes")).get(0).get("name"));
        String rdf = new String(new OntologyArtifactExporter().rdf(result, "1", "销售", "db").getBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(rdf.contains("实收金额"));
        assertFalse(rdf.contains("旧导出名称"));
    }

    @Test
    public void batchCanAddObjectThenPropertyThenMetricAndDeleteInDependencyOrder() {
        Map<String,Object> base = config();
        List<Map<String,Object>> additions = Arrays.asList(add("objects", row("id", "users", "name", "用户", "table", "users")),
                add("properties", row("id", "userId", "name", "用户编号", "objectId", "users", "column", "id", "dataType", "BIGINT")),
                add("metrics", row("id", "userCount", "name", "用户数量", "propertyId", "userId", "aggregation", "COUNT")));
        Map<String,Object> updated = changes.applyAnalysisChanges(base, changes.normalizeAnalysisChanges(base, additions, true));
        assertEquals(2, rows(updated.get("objects")).size());
        try { changes.normalizeAnalysisChanges(updated, Collections.singletonList(delete("properties", "userId")), true); fail(); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("引用")); }
        List<Map<String,Object>> deletions = Arrays.asList(delete("metrics", "userCount"), delete("properties", "userId"), delete("objects", "users"));
        Map<String,Object> removed = changes.applyAnalysisChanges(updated, changes.normalizeAnalysisChanges(updated, deletions, true));
        assertEquals(1, rows(removed.get("objects")).size());
        assertEquals(1, rows(removed.get("properties")).size());
        assertEquals(1, rows(map(removed.get("targetConfig")).get("properties")).size());
    }

    @Test
    public void rejectsStaleEditsAndMissingObjectReferences() {
        Map<String,Object> stale = update("properties", "amount", "name", "客户新名称");
        stale.put("before", "其他人修改前的名称");
        try { changes.normalizeAnalysisChanges(config(), Collections.singletonList(stale), true); fail(); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("其他操作")); }
        try { changes.normalizeAnalysisChanges(config(), Collections.singletonList(update("properties", "amount", "objectId", "missing")), true); fail(); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("对象")); }
    }

    @Test
    public void preservesManualNamesAndSurfacesPhysicalConflictsOnReanalysis() {
        Map<String,Object> previous = config();
        Map<String,Object> physical = update("properties", "amount", "dataType", "BIGINT");
        physical.put("before", "DECIMAL");
        previous.put("analysisCorrections", Arrays.asList(update("objects", "orders", "name", "客户确认的订单"), physical));
        Map<String,Object> generated = config();
        rows(generated.get("properties")).get(0).put("dataType", "VARCHAR");
        Map<String,Object> result = changes.preserveCorrections(generated, previous);
        assertEquals("客户确认的订单", rows(result.get("objects")).get(0).get("name"));
        assertEquals("VARCHAR", rows(result.get("properties")).get(0).get("dataType"));
        assertEquals(1, rows(result.get("analysisCorrectionConflicts")).size());
        assertEquals("客户确认的订单", rows(map(result.get("targetConfig")).get("objects")).get(0).get("name"));
    }

    @Test
    public void preservesSemanticJoinFilteringAndBlocksIndirectReferences() {
        Map<String,Object> base = config();
        base.put("relations", Arrays.asList(row("id", "physical", "inferenceMethod", "foreignKey"), row("id", "reified", "inferenceMethod", "m2mReification")));
        Map<String,Object> result = changes.applyAnalysisChanges(base, Collections.emptyList());
        assertEquals(1, rows(map(result.get("semanticCatalog")).get("relations")).size());
        map(base.get("semanticCatalog")).put("metrics", Arrays.asList(row("id", "revenue", "name", "收入", "propertyId", "amount", "aggregation", "SUM"),
                row("id", "computed", "name", "计算收入", "formula", row("metricId", "revenue"))));
        try { changes.normalizeAnalysisChanges(base, Collections.singletonList(delete("metrics", "revenue")), true); fail(); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("引用")); }
    }

    private Map<String,Object> config() {
        Map<String,Object> property = row("id", "amount", "objectId", "orders", "name", "金额", "column", "amount", "dataType", "DECIMAL");
        Map<String,Object> semantics = row("metrics", new ArrayList<>(Collections.singletonList(row("id", "revenue", "name", "收入", "propertyId", "amount", "aggregation", "SUM"))));
        return row("objects", new ArrayList<>(Collections.singletonList(row("id", "orders", "name", "订单", "table", "orders"))),
                "properties", new ArrayList<>(Collections.singletonList(property)), "semanticCatalog", semantics,
                "querySemantics", row("metrics", Collections.singletonList(row("id", "revenue", "name", "过时指标"))),
                "targetConfig", row("objects", Collections.singletonList(row("id", "orders", "name", "旧导出名称"))),
                "taxonomy", row("nodes", new ArrayList<>(Collections.singletonList(row("id", "orders", "name", "旧层级名称")))), "mappings", new ArrayList<>());
    }
    private Map<String,Object> update(String category, String id, String field, Object value) { return row("op", "UPDATE", "category", category, "id", id, "field", field, "value", value); }
    private Map<String,Object> add(String category, Map<String,Object> value) { return row("op", "ADD", "category", category, "value", value); }
    private Map<String,Object> delete(String category, String id) { return row("op", "DELETE", "category", category, "id", id); }
    private Map<String,Object> row(Object... values) { Map<String,Object> row = new LinkedHashMap<>(); for (int index = 0; index < values.length; index += 2) row.put(String.valueOf(values[index]), values[index + 1]); return row; }
    @SuppressWarnings("unchecked") private Map<String,Object> map(Object value) { return (Map<String,Object>) value; }
    @SuppressWarnings("unchecked") private List<Map<String,Object>> rows(Object value) { return (List<Map<String,Object>>) value; }
}

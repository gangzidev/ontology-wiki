package com.ontologywiki.ontology.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.Assert.*;

public class OntologySemanticNamingServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final OntologySemanticNamingService service = new OntologySemanticNamingService();

    @Test
    public void namesEveryTableFieldAndSemanticBeyondOriginalSummaryLimits() {
        Map<String, Object> config = config(135);
        List<String> prompts = new ArrayList<>();
        Map<String, Object> report = service.enrich(config, schema(135), "订单销售业务", prompt -> {
            prompts.add(prompt);
            assertTrue(input(prompt).path("items").size() <= 24);
            assertTrue(prompt.length() < 21000);
            assertFalse(prompt.contains("secret-customer-sample"));
            return answer(prompt, null);
        }, message -> assertTrue(message.startsWith("3/4 ")));
        assertEquals("COMPLETE", report.get("status"));
        assertEquals(274, report.get("named"));
        assertTrue(prompts.size() > 10);
        assertEquals("中文业务字段134", rows(config, "properties").get(134).get("name"));
        assertEquals("LLM_WIKI", rows(config, "properties").get(134).get("semanticSource"));
        Map<String, Object> metric = rows(map(config.get("semanticCatalog")), "metrics").get(135);
        assertEquals("订单数量", metric.get("name"));
        assertEquals("COUNT", metric.get("aggregation"));
        assertEquals("property_0", metric.get("propertyId"));
        assertTrue(prompts.stream().anyMatch(prompt -> prompt.contains("\"objectName\":\"销售订单\"") && prompt.contains("\"aggregation\":\"COUNT\"")));
        assertEquals("销售订单", rows(map(config.get("taxonomy")), "nodes").get(0).get("name"));
        assertSame(config.get("semanticCatalog"), map(config.get("targetConfig")).get("semanticCatalog"));
    }

    @Test
    public void retriesOnlyMissingItemsAndNeverChangesPhysicalMappingsOrAggregation() {
        Map<String, Object> config = config(2);
        AtomicInteger request = new AtomicInteger();
        service.enrich(config, schema(2), "", prompt -> {
            int call = request.incrementAndGet();
            if (call == 1) return answer(prompt, item -> "objects".equals(item.path("category").asText()));
            if (call == 2) {
                assertEquals(2, input(prompt).path("items").size());
                assertEquals("properties", input(prompt).path("items").get(0).path("category").asText());
            }
            return answer(prompt, null).replace("\"confidence\":0.9", "\"confidence\":0.9,\"table\":\"fake_table\",\"column\":\"fake_column\",\"aggregation\":\"AVG\",\"propertyId\":\"fake_id\",\"status\":\"APPROVED\"");
        }, message -> {});
        assertEquals("orders", rows(config, "objects").get(0).get("table"));
        Map<String, Object> property = rows(config, "properties").get(0);
        assertEquals("amount_0", property.get("column"));
        assertEquals("object_orders", property.get("objectId"));
        assertEquals("property_0", property.get("id"));
        Map<String, Object> metric = rows(map(config.get("semanticCatalog")), "metrics").get(0);
        assertEquals("SUM", metric.get("aggregation"));
        assertEquals("property_0", metric.get("propertyId"));
        assertEquals("NEEDS_REVIEW", metric.get("status"));
        assertEquals(3, request.get());
    }

    @Test
    public void failuresStayExplicitAndDoNotPretendDictionaryNamesAreModelResults() {
        Map<String, Object> config = config(1);
        rows(config, "properties").get(0).put("name", "task_id");
        Map<String, Object> report = service.enrich(config, schema(1), "", prompt -> { throw new IllegalStateException("Unavailable"); }, message -> {});
        assertEquals("UNAVAILABLE", report.get("status"));
        assertEquals(6, report.get("unresolved"));
        assertEquals(4, report.get("failedRequests"));
        assertEquals("task_id", rows(config, "properties").get(0).get("name"));
        assertEquals("UNRESOLVED", rows(config, "properties").get(0).get("namingStatus"));
        assertNull(rows(config, "properties").get(0).get("semanticSource"));
        assertEquals("SEMANTIC_NAMES_INCOMPLETE", rows(config, "warnings").get(0).get("code"));
    }

    @Test
    public void rejectsDuplicateUnknownAndNonChineseIdsAndNames() {
        Map<String, Object> config = config(1);
        service.enrich(config, schema(1), "", prompt -> "{\"items\":["
                + "{\"category\":\"objects\",\"id\":\"object_orders\",\"name\":\"订单\",\"definition\":\"销售订单\"},"
                + "{\"category\":\"objects\",\"id\":\"object_orders\",\"name\":\"用户\",\"definition\":\"用户记录\"},"
                + "{\"category\":\"properties\",\"id\":\"property_0\",\"name\":\"amount\",\"definition\":\"字段金额\"},"
                + "{\"category\":\"properties\",\"id\":\"unknown\",\"name\":\"金额\",\"definition\":\"字段金额\"}]}", message -> {});
        assertEquals("orders", rows(config, "objects").get(0).get("name"));
        assertEquals("amount_0", rows(config, "properties").get(0).get("name"));
        assertEquals(0, map(config.get("semanticNamingReport")).get("named"));
        assertEquals(1, rows(config, "properties").size());
    }

    @Test
    public void keepsSameColumnNamesInDifferentSchemasIsolated() {
        Map<String, Object> config = config(1);
        rows(config, "objects").add(row("id", "object_payments", "table", "orders", "schema", "finance", "name", "orders"));
        rows(config, "properties").add(row("id", "payment_amount", "objectId", "object_payments", "column", "amount_0", "name", "amount_0"));
        service.enrich(config, schema(1), "", prompt -> {
            JsonNode items = input(prompt).path("items");
            String objectId = items.get(0).path("objectId").asText();
            for (JsonNode item : items) assertEquals(objectId, item.path("objectId").asText());
            return answer(prompt, null).replace("中文业务字段payment_amount", "财务结算金额");
        }, message -> {});
        assertEquals("中文业务字段0", rows(config, "properties").get(0).get("name"));
        assertEquals("财务结算金额", rows(config, "properties").get(1).get("name"));
    }

    @Test
    public void onlyFillsMissingChineseNamesAndPreservesCustomerCorrections() {
        Map<String, Object> config = config(2);
        Map<String, Object> object = rows(config, "objects").get(0);
        object.put("name", "客户确认的销售订单");
        object.put("semanticSource", "HUMAN_REVIEW");
        Map<String, Object> property = rows(config, "properties").get(0);
        property.put("name", "应收金额");
        property.put("description", "客户确认的应收金额口径");
        Map<String, Object> metric = rows(map(config.get("semanticCatalog")), "metrics").get(0);
        metric.put("name", "应收金额合计");
        metric.put("definition", "客户已确认的统计口径");
        Map<String, Object> originalObject = new LinkedHashMap<>(object);
        Map<String, Object> originalProperty = new LinkedHashMap<>(property);
        Map<String, Object> originalMetric = new LinkedHashMap<>(metric);
        Map<String, Object> report = service.enrich(config, schema(2), "", prompt -> {
            for (JsonNode item : input(prompt).path("items")) {
                assertNotEquals("object_orders", item.path("id").asText());
                assertNotEquals("property_0", item.path("id").asText());
                assertNotEquals("metric_0", item.path("id").asText());
            }
            return answer(prompt, null);
        }, message -> {});
        assertEquals(originalObject, object);
        assertEquals(originalProperty, property);
        assertEquals(originalMetric, metric);
        assertEquals(3, report.get("preserved"));
        assertEquals(5, report.get("total"));
        assertEquals(5, report.get("named"));
        assertEquals("中文业务字段1", rows(config, "properties").get(1).get("name"));
        AtomicInteger requests = new AtomicInteger();
        Map<String, Object> repeated = service.enrich(config, schema(2), "", prompt -> { requests.incrementAndGet(); return "{}"; }, message -> {});
        assertEquals(0, requests.get());
        assertEquals(0, repeated.get("total"));
        assertEquals(8, repeated.get("preserved"));
    }

    @Test
    public void enrichedResultsStillSupportConversationAddUpdateAndDelete() {
        Map<String, Object> config = config(2);
        service.enrich(config, schema(2), "", prompt -> answer(prompt, null), message -> {});
        LlmWikiClient client = new LlmWikiClient(new com.ontologywiki.ontology.config.OntologyIntegrationProperties(), null, null, null, null, null, null);
        Map<String, Object> added = row("id", "property_remark", "name", "订单备注", "objectId", "object_orders", "column", "remark", "dataType", "VARCHAR");
        List<Map<String, Object>> add = client.normalizeAnalysisChanges(config, Collections.singletonList(row("op", "ADD", "category", "properties", "value", added)), true);
        Map<String, Object> withAdded = client.applyAnalysisChanges(config, add);
        assertEquals(3, rows(withAdded, "properties").size());
        assertEquals("remark", rows(withAdded, "properties").get(2).get("column"));
        List<Map<String, Object>> updates = client.normalizeAnalysisChanges(withAdded, Arrays.asList(
                row("op", "UPDATE", "category", "properties", "id", "property_0", "field", "name", "value", "客户确认的含税金额"),
                row("op", "UPDATE", "category", "properties", "id", "property_remark", "field", "dataType", "value", "TEXT"),
                row("op", "UPDATE", "category", "metrics", "id", "metric_0", "field", "name", "value", "含税金额合计"),
                row("op", "UPDATE", "category", "dimensions", "id", "dimension_region", "field", "name", "value", "销售区域")), true);
        Map<String, Object> updated = client.applyAnalysisChanges(withAdded, updates);
        assertEquals("客户确认的含税金额", rows(updated, "properties").get(0).get("name"));
        assertEquals("TEXT", rows(updated, "properties").get(2).get("dataType"));
        assertEquals("含税金额合计", rows(map(updated.get("semanticCatalog")), "metrics").get(0).get("name"));
        assertEquals("销售区域", rows(map(updated.get("semanticCatalog")), "dimensions").get(0).get("name"));
        List<Map<String, Object>> delete = client.normalizeAnalysisChanges(updated, Collections.singletonList(row("op", "DELETE", "category", "properties", "id", "property_remark")), true);
        Map<String, Object> result = client.applyAnalysisChanges(updated, delete);
        assertEquals(2, rows(result, "properties").size());
        assertEquals("orders", rows(result, "objects").get(0).get("table"));
        assertEquals("amount_0", rows(result, "properties").get(0).get("column"));
        assertEquals(2, rows(config, "properties").size());
        assertEquals("中文业务字段0", rows(config, "properties").get(0).get("name"));
    }

    @Test
    public void cancellationIsNotSwallowedAsAModelFallback() {
        AtomicInteger calls = new AtomicInteger();
        try {
            service.enrich(config(1), schema(1), "", prompt -> {
                calls.incrementAndGet();
                throw new java.util.concurrent.CancellationException("用户取消");
            }, message -> {});
            fail("Expected cancellation");
        } catch (java.util.concurrent.CancellationException expected) {
            assertEquals(1, calls.get());
        }
    }

    @Test
    public void recordsLowConfidenceWithoutChangingStructuralConfidence() {
        Map<String, Object> config = config(1);
        service.enrich(config, schema(1), "", prompt -> answer(prompt, null).replace("\"confidence\":0.9", "\"confidence\":0.4"), message -> {});
        assertEquals("COMPLETE", map(config.get("semanticNamingReport")).get("status"));
        assertEquals(6, map(config.get("semanticNamingReport")).get("needsConfirmation"));
        assertEquals("NEEDS_CONFIRMATION", rows(config, "properties").get(0).get("namingStatus"));
        assertEquals(0.7, rows(config, "properties").get(0).get("confidence"));
        assertEquals(0.4, rows(config, "properties").get(0).get("nameConfidence"));
    }

    private String answer(String prompt, Function<JsonNode, Boolean> include) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (JsonNode item : input(prompt).path("items")) {
            if (include != null && !include.apply(item)) continue;
            String category = item.path("category").asText();
            String name = "objects".equals(category) ? "销售订单" : "中文业务字段" + item.path("id").asText().replace("property_", "");
            if ("metrics".equals(category)) name = "COUNT".equals(item.path("aggregation").asText()) ? "订单数量" : "订单金额合计";
            if ("dimensions".equals(category)) name = "订单地区";
            if ("timeDimensions".equals(category)) name = "下单时间";
            result.add(row("category", category, "id", item.path("id").asText(), "name", name, "definition", name + "的业务含义", "confidence", 0.9, "reason", "依据表名和字段上下文", "synonyms", Arrays.asList("业务别名")));
        }
        try { return json.writeValueAsString(row("items", result)); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }

    private JsonNode input(String prompt) {
        try { return json.readTree(prompt.substring(prompt.indexOf("\n输入 JSON：\n") + "\n输入 JSON：\n".length())); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }

    private Map<String, Object> config(int size) {
        List<Map<String, Object>> properties = new ArrayList<>(), metrics = new ArrayList<>();
        for (int index = 0; index < size; index++) {
            properties.add(row("id", "property_" + index, "objectId", "object_orders", "column", "amount_" + index, "name", "amount_" + index, "dataType", "DECIMAL", "confidence", 0.7));
            metrics.add(row("id", "metric_" + index, "propertyId", "property_" + index, "name", "amount_" + index, "aggregation", "SUM", "status", "NEEDS_REVIEW"));
        }
        metrics.add(row("id", "metric_count", "propertyId", "property_0", "name", "orders数量", "aggregation", "COUNT"));
        return row("objects", new ArrayList<>(Collections.singletonList(row("id", "object_orders", "table", "orders", "name", "orders", "schema", "sales"))),
                "properties", properties, "taxonomy", row("nodes", Collections.singletonList(row("id", "object_orders", "name", "orders"))),
                "targetConfig", row(), "semanticCatalog", row("metrics", metrics,
                        "dimensions", Collections.singletonList(row("id", "dimension_region", "propertyId", "property_0", "name", "region")),
                        "timeDimensions", Collections.singletonList(row("id", "time_created", "propertyId", "property_0", "name", "created_at"))));
    }

    private Map<String, Object> schema(int size) {
        List<Map<String, Object>> columns = new ArrayList<>();
        for (int index = 0; index < size; index++) columns.add(row("name", "amount_" + index, "type", "DECIMAL", "comment", ""));
        return row("tables", Collections.singletonList(row("name", "orders", "schema", "sales", "comment", "sales orders", "columns", columns,
                "samples", Collections.singletonList(row("name", "secret-customer-sample")))));
    }

    private Map<String, Object> row(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) result.put(String.valueOf(values[index]), values[index + 1]);
        return result;
    }
    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) { return (Map<String, Object>) value; }
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(Map<String, Object> config, String key) { return (List<Map<String, Object>>) config.get(key); }
}

package com.ontologywiki.ontology.controller;

import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.*;

public class OntologyControllerLlmMergeTest {
    @Test
    public void mergesSemanticNamesAndFieldBasedRelation() throws Exception {
        com.ontologywiki.ontology.service.analysis.SemanticEnrichmentService controller = new com.ontologywiki.ontology.service.analysis.SemanticEnrichmentService();
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("objects", new ArrayList<>(Arrays.asList(
                row("id", "object_orders", "table", "orders", "technicalName", "orders", "name", "orders"),
                row("id", "object_users", "table", "sys_user", "technicalName", "sys_user", "name", "sys_user"))));
        config.put("properties", new ArrayList<>(Arrays.asList(
                row("id", "property_orders_user_id", "objectId", "object_orders", "column", "user_id", "name", "user_id"),
                row("id", "property_users_id", "objectId", "object_users", "column", "id", "name", "id"))));
        config.put("relations", new ArrayList<>());
        config.put("semanticCatalog", row("relations", new ArrayList<>(), "metrics", new ArrayList<>(Collections.singletonList(row("id", "metric_orders_user_id", "propertyId", "property_orders_user_id", "name", "user_id"))), "dimensions", new ArrayList<>(), "timeDimensions", new ArrayList<>()));

        Map<String, Object> suggestions = new LinkedHashMap<>();
        suggestions.put("objects", Collections.singletonList(row("table", "orders", "name", "订单")));
        suggestions.put("properties", Collections.singletonList(row("table", "orders", "column", "user_id", "name", "用户编号")));
        suggestions.put("metrics", Collections.singletonList(row("propertyId", "property_orders_user_id", "name", "用户数", "definition", "订单对应的用户数量")));
        suggestions.put("relations", Collections.singletonList(row("name", "订单所属用户", "sourceTable", "orders", "sourceColumn", "user_id", "targetTable", "sys_user", "targetColumn", "id")));

        Method merge = com.ontologywiki.ontology.service.analysis.SemanticEnrichmentService.class.getDeclaredMethod("mergeLlmSuggestions", Map.class, Map.class);
        merge.setAccessible(true);
        merge.invoke(controller, config, suggestions);

        Assert.assertEquals("订单", ((Map<?, ?>) ((List<?>) config.get("objects")).get(0)).get("name"));
        Assert.assertEquals("用户编号", ((Map<?, ?>) ((List<?>) config.get("properties")).get(0)).get("name"));
        Assert.assertEquals("用户数", ((Map<?, ?>) ((List<?>) ((Map<?, ?>) config.get("semanticCatalog")).get("metrics")).get(0)).get("name"));
        Map<?, ?> relation = (Map<?, ?>) ((List<?>) config.get("relations")).get(0);
        Assert.assertEquals("object_orders", relation.get("sourceObjectId"));
        Assert.assertEquals("object_users", relation.get("targetObjectId"));
        Assert.assertEquals("LLM_FIELD_NAME_INFERENCE", relation.get("inferenceMethod"));
        Assert.assertEquals(1, ((List<?>) ((Map<?, ?>) config.get("semanticCatalog")).get("relations")).size());
    }

    @Test
    public void parsesNestedCatalogSuggestions() throws Exception {
        com.ontologywiki.ontology.service.analysis.SemanticEnrichmentService controller = new com.ontologywiki.ontology.service.analysis.SemanticEnrichmentService();
        Method parse = com.ontologywiki.ontology.service.analysis.SemanticEnrichmentService.class.getDeclaredMethod("parseLlmSuggestions", String.class);
        parse.setAccessible(true);
        Map<?, ?> parsed = (Map<?, ?>) parse.invoke(controller,
                "```json\n{\"semanticCatalog\":{\"dimensions\":[{\"propertyId\":\"p1\",\"name\":\"订单状态\",\"definition\":\"订单当前状态\"}]}}\n```");
        Assert.assertEquals("订单状态", ((Map<?, ?>) ((List<?>) parsed.get("dimensions")).get(0)).get("name"));
    }

    @Test
    public void propertyNamesDoNotOverwriteCountMetricsAndSummaryIncludesNestedCatalog() throws Exception {
        com.ontologywiki.ontology.service.analysis.SemanticEnrichmentService controller = new com.ontologywiki.ontology.service.analysis.SemanticEnrichmentService();
        Map<String, Object> property = row("id", "property_orders_id", "objectId", "object_orders", "column", "id", "name", "id");
        Map<String, Object> metric = row("id", "metric_count", "propertyId", "property_orders_id", "name", "订单数量", "aggregation", "COUNT");
        Map<String, Object> config = row("objects", new ArrayList<>(Collections.singletonList(row("id", "object_orders", "table", "orders", "name", "订单"))),
                "properties", new ArrayList<>(Collections.singletonList(property)),
                "semanticCatalog", row("metrics", new ArrayList<>(Collections.singletonList(metric))));
        Method merge = com.ontologywiki.ontology.service.analysis.SemanticEnrichmentService.class.getDeclaredMethod("mergeLlmSuggestions", Map.class, Map.class);
        merge.setAccessible(true);
        merge.invoke(controller, config, row("properties", Collections.singletonList(row("id", "property_orders_id", "name", "订单编号"))));
        Assert.assertEquals("订单数量", metric.get("name"));
        Method summary = com.ontologywiki.ontology.service.analysis.SemanticEnrichmentService.class.getDeclaredMethod("candidateSummary", Map.class);
        summary.setAccessible(true);
        String result = (String) summary.invoke(controller, config);
        Assert.assertTrue(result.contains("metric_count"));
        Assert.assertTrue(result.contains("property_orders_id"));
        merge.invoke(controller, config, row("properties", Collections.singletonList(row("id", "property_orders_id", "name", "不应覆盖的名称")),
                "objects", Collections.singletonList(row("id", "object_orders", "name", "不应覆盖的对象")),
                "metrics", Collections.singletonList(row("id", "metric_count", "name", "不应覆盖的指标"))));
        Assert.assertEquals("订单编号", property.get("name"));
        Assert.assertEquals("订单数量", metric.get("name"));
        Assert.assertEquals("订单", ((Map<?, ?>) ((List<?>) config.get("objects")).get(0)).get("name"));
    }

    private Map<String, Object> row(Object... values) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) row.put(String.valueOf(values[i]), values[i + 1]);
        return row;
    }
}

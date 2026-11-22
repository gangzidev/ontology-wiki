package com.ontologywiki.query.service;

import com.ontologywiki.query.catalog.QuerySemanticCatalogService;
import org.junit.Assert;
import org.junit.Test;

import java.util.*;

public class QuerySemanticCatalogServiceTest {
    @Test public void matchesSynonymAndDefaultFilter() {
        Map<String,Object> metric = row("id","paidRevenue","name","已支付金额","synonyms",Collections.singletonList("成交金额"),"propertyId","amount","aggregation","SUM","filter",row("propertyId","status","operator","=","value","PAID"));
        Map<String,Object> config = row("querySemantics",row("metrics",Collections.singletonList(metric),"dimensions",Collections.emptyList()));
        QuerySemanticCatalogService.Resolution result = new QuerySemanticCatalogService().resolve(config, "按成交金额统计");
        Assert.assertFalse(result.needsClarification());
        Assert.assertEquals(Collections.singletonList("paidRevenue"), result.getMetricIds());
        Assert.assertEquals("PAID", result.getDefaultFilters().get(0).get("value"));
    }
    @Test public void rejectsAmbiguousMetric() {
        Map<String,Object> config = row("querySemantics",row("metrics",Arrays.asList(row("id","a","name","金额"),row("id","b","name","金额")),"dimensions",Collections.emptyList()));
        QuerySemanticCatalogService.Resolution result = new QuerySemanticCatalogService().resolve(config, "查询金额");
        Assert.assertTrue(result.needsClarification());
    }
    @Test public void unknownMetricCannotGuess() {
        Map<String,Object> config = row("querySemantics",row("metrics",Collections.singletonList(row("id","a","name","收入")),"dimensions",Collections.emptyList()));
        Assert.assertTrue(new QuerySemanticCatalogService().resolve(config, "查询订单").needsClarification());
    }
    @Test
    public void recognizesTableDataAsDetailQuery() {
        Map<String, Object> config = row("objects", Collections.singletonList(row("id", "users", "name", "用户信息表", "table", "sys_user")),
                "properties", Collections.singletonList(row("id", "userId", "objectId", "users", "column", "user_id")),
                "semanticCatalog", row("metrics", Collections.singletonList(row("id", "userCount", "name", "用户数量", "propertyId", "userId", "aggregation", "COUNT")), "dimensions", Collections.emptyList()));
        QuerySemanticCatalogService.Resolution result = new QuerySemanticCatalogService().resolve(config, "用户信息表的数据");
        Assert.assertTrue(result.isDetailQuery());
        Assert.assertEquals("users", result.getDetailObjectId());
        Assert.assertTrue(new QuerySemanticCatalogService().resolve(config, "我想看用户表信息").isDetailQuery());
    }

    @Test
    public void mentioningAFilterDoesNotRequestGrouping() {
        Map<String, Object> config = row("semanticCatalog", row(
                "metrics", Collections.singletonList(row("id", "count", "name", "订单数量", "aggregation", "COUNT")),
                "dimensions", Collections.singletonList(row("id", "status", "name", "状态"))));
        QuerySemanticCatalogService service = new QuerySemanticCatalogService();
        Assert.assertTrue(service.resolve(config, "状态为已支付的订单数量").getDimensionIds().isEmpty());
        Assert.assertEquals(Collections.singletonList("status"), service.resolve(config, "按状态统计订单数量").getDimensionIds());
    }

    @Test
    public void businessObjectClarificationResolvesDuplicateMetricNames() {
        Map<String, Object> config = row("objects", Arrays.asList(row("id", "orders", "name", "订单"), row("id", "refunds", "name", "退款")),
                "properties", Arrays.asList(row("id", "orderAmount", "objectId", "orders"), row("id", "refundAmount", "objectId", "refunds")),
                "semanticCatalog", row("metrics", Arrays.asList(row("id", "amount1", "name", "金额", "propertyId", "orderAmount"),
                        row("id", "amount2", "name", "金额", "propertyId", "refundAmount"))));
        QuerySemanticCatalogService service = new QuerySemanticCatalogService();
        Assert.assertTrue(service.resolve(config, "统计金额").getClarification().contains("订单的金额"));
        Assert.assertEquals(Collections.singletonList("amount1"), service.resolve(config, "统计金额\n补充说明：订单").getMetricIds());
    }

    @Test
    public void clarificationAnswerSelectsObjectMetricAndItsStatusDimension() {
        List<Map<String, Object>> objects = Arrays.asList(
                row("id", "analysis", "name", "分析任务"),
                row("id", "knowledge", "name", "本体分析知识记录"),
                row("id", "proposal", "name", "本体方案"));
        List<Map<String, Object>> properties = new ArrayList<>();
        List<Map<String, Object>> metrics = new ArrayList<>();
        List<Map<String, Object>> dimensions = new ArrayList<>();
        for (Map<String, Object> object : objects) {
            String id = String.valueOf(object.get("id"));
            properties.add(row("id", id + "TaskId", "objectId", id, "column", "task_id", "dataType", "bigint"));
            properties.add(row("id", id + "Status", "objectId", id, "column", "status", "dataType", "varchar"));
            metrics.add(row("id", id + "Count", "name", "task_id", "propertyId", id + "TaskId", "aggregation", "COUNT"));
            dimensions.add(row("id", id + "StatusDim", "name", "状态", "propertyId", id + "Status"));
        }
        Map<String, Object> config = row("objects", objects, "properties", properties,
                "semanticCatalog", row("metrics", metrics, "dimensions", dimensions));
        QuerySemanticCatalogService service = new QuerySemanticCatalogService();
        Assert.assertTrue(service.resolve(config, "按任务状态统计task_id").needsClarification());
        QuerySemanticCatalogService.Resolution resolved = service.resolve(config,
                "按任务状态统计task_id\n补充说明：分析任务的");
        Assert.assertEquals(Collections.singletonList("analysisCount"), resolved.getMetricIds());
        Assert.assertEquals(Collections.singletonList("analysisStatusDim"), resolved.getDimensionIds());
    }

    @Test
    public void describeIncludesOnlyTheRelationFieldsNeededForDrilldownReachability() {
        Map<String, Object> config = row("objects", Arrays.asList(row("id", "orders"), row("id", "stores")),
                "properties", Collections.singletonList(row("id", "orderCount", "objectId", "orders")),
                "semanticCatalog", row("metrics", Collections.singletonList(row("id", "count", "name", "orders", "propertyId", "orderCount")),
                        "relations", Collections.singletonList(row("id", "order-store", "sourceObjectId", "orders", "targetObjectId", "stores", "sourceColumn", "store_id", "targetColumn", "id"))));
        Map<String, Object> described = new QuerySemanticCatalogService().describe(config);
        List<Map<String, Object>> relations = (List<Map<String, Object>>) described.get("relations");
        Assert.assertEquals(1, relations.size());
        Assert.assertEquals("orders", relations.get(0).get("sourceObjectId"));
        Assert.assertFalse(relations.get(0).containsKey("sourceColumn"));
    }

    @Test
    public void duplicateObjectNamesAreClarifiedInsteadOfSelectingFirstTable() {
        Map<String, Object> config = row("objects", Arrays.asList(row("id", "a", "name", "用户信息表", "table", "sys_user"), row("id", "b", "name", "用户信息表", "table", "app_user")));
        QuerySemanticCatalogService service = new QuerySemanticCatalogService();
        Assert.assertTrue(service.resolve(config, "查看用户表数据").getClarification().contains("app_user"));
        Assert.assertEquals("a", service.resolve(config, "查看用户表数据\n补充说明：sys_user").getDetailObjectId());
    }

    @Test
    public void objectNameContainingInformationDoesNotTurnUserCountIntoDetails() {
        Map<String, Object> config = row("objects", Collections.singletonList(row("id", "users", "name", "用户信息表", "table", "sys_user")),
                "properties", Collections.singletonList(row("id", "userId", "objectId", "users", "column", "user_id")),
                "semanticCatalog", row("metrics", Collections.singletonList(row("id", "count", "name", "用户数", "propertyId", "userId", "aggregation", "COUNT"))));
        QuerySemanticCatalogService service = new QuerySemanticCatalogService();
        Assert.assertEquals(Collections.singletonList("count"), service.resolve(config, "查看用户信息表的用户数").getMetricIds());
        Assert.assertTrue(service.resolve(config, "查看用户信息表的数据").isDetailQuery());
        Assert.assertTrue(service.resolve(config, "看用户").isDetailQuery());
    }

    private Map<String,Object> row(Object... values) { Map<String,Object> result = new LinkedHashMap<>(); for (int i = 0; i < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]); return result; }
}

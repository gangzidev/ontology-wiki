package com.ontologywiki.query.planning;

import com.ontologywiki.query.catalog.QuerySemanticCatalogService;
import org.junit.Assert;
import org.junit.Test;

import java.util.*;

public class QueryPlannerFastPathTest {
    private final QuerySemanticCatalogService catalog = new QuerySemanticCatalogService();
    private final QueryPlannerService planner = new QueryPlannerService(null, null, null, null, null, null);
    private final Map<String, Object> config = row("semanticCatalog", row(
            "metrics", Collections.singletonList(row("id", "orderAmount", "name", "订单金额", "aggregation", "SUM")),
            "dimensions", Collections.singletonList(row("id", "region", "name", "地区"))));

    @Test
    public void simpleCatalogAggregationCanSkipModelPlanning() {
        String question = "按地区统计订单金额";
        Assert.assertTrue(planner.isSimpleAggregationQuestion(config, question, catalog.resolve(config, question)));
        Assert.assertTrue(planner.isSimpleAggregationQuestion(config, "总订单金额", catalog.resolve(config, "总订单金额")));
    }

    @Test
    public void filtersTimeAndAggregationOverridesKeepModelPlanning() {
        assertNotSimple("按地区统计上个月订单金额");
        assertNotSimple("按地区统计订单金额且状态为已支付");
        assertNotSimple("平均订单金额");
    }

    @Test
    public void selectedBusinessObjectClarificationDoesNotFallBackToModel() {
        Map<String, Object> config = row(
                "objects", Collections.singletonList(row("id", "analysis", "name", "分析任务", "table", "analysis_task")),
                "properties", Collections.singletonList(row("id", "taskId", "objectId", "analysis", "column", "task_id")),
                "semanticCatalog", row("metrics", Collections.singletonList(row("id", "taskCount", "name", "task_id", "propertyId", "taskId", "aggregation", "COUNT")),
                        "dimensions", Collections.singletonList(row("id", "status", "name", "状态"))));
        String question = "按任务状态统计task_id\n补充说明：分析任务的";
        Assert.assertTrue(planner.isSimpleAggregationQuestion(config, question, catalog.resolve(config, question)));
    }

    private void assertNotSimple(String question) {
        Assert.assertFalse(question, planner.isSimpleAggregationQuestion(config, question, catalog.resolve(config, question)));
    }

    private Map<String, Object> row(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]);
        return result;
    }
}

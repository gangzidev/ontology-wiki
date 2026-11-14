package com.ontologywiki.query.service;

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
    private Map<String,Object> row(Object... values) { Map<String,Object> result = new LinkedHashMap<>(); for (int i = 0; i < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]); return result; }
}

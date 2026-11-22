package com.ontologywiki.query.service;

import com.ontologywiki.query.presentation.QueryChartHintService;
import org.junit.Assert;
import org.junit.Test;

import java.util.*;

public class QueryChartHintServiceTest {
    @Test
    public void timeGranularityProducesTrendHint() {
        Map<String, Object> plan = row("metrics", Collections.singletonList(row("id", "revenue")), "timeGranularity", "MONTH");
        List<Map<String, Object>> rows = Arrays.asList(row("time_month", "2026-01", "revenue", 10), row("time_month", "2026-02", "revenue", 12));
        Map<String, Object> hint = new QueryChartHintService().hint(plan, rows, row("metrics", Collections.singletonList(row("name", "收入"))));
        Assert.assertEquals("line", hint.get("type"));
        Assert.assertEquals("time_month", hint.get("xKey"));
        Assert.assertEquals("revenue", hint.get("yKey"));
    }

    private Map<String, Object> row(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]);
        return result;
    }
}

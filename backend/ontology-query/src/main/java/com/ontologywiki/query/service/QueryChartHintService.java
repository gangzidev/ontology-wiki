package com.ontologywiki.query.service;

import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 分析查询结果形状，推断最适合的图表类型。
 * 结果只是建议，前端可自行切换展示方式。
 */
@Service
public class QueryChartHintService {

    private static final List<String> TIME_KEYWORDS = Arrays.asList(
            "年", "月", "日", "时", "季", "周", "date", "time", "year", "month",
            "day", "hour", "week", "quarter", "created", "updated", "统计日", "创建"
    );

    /**
     * 根据查询计划、执行结果和语义说明生成图表建议。
     *
     * @param plan        查询计划（含 metrics / dimensions / filters）
     * @param rows        查询结果行
     * @param explanation 语义说明（含指标和维度的中文名称）
     * @return 包含 type / xKey / yKey / nameKey / valueKey / xLabel / yLabel 的 Map
     */
    public Map<String, Object> hint(Map<String, Object> plan, List<Map<String, Object>> rows,
                                    Map<String, Object> explanation) {
        Map<String, Object> result = new LinkedHashMap<>();
        int rowCount = rows.size();
        List<Map<String, Object>> metrics = rows(plan.get("metrics"));
        List<Map<String, Object>> dimensions = rows(plan.get("dimensions"));
        int mCount = metrics.size();
        int dCount = dimensions.size();
        boolean comparison = plan.get("comparison") instanceof Map && !"NONE".equalsIgnoreCase(String.valueOf(((Map<?, ?>) plan.get("comparison")).get("type")));
        String metricPrefix = comparison ? "current_" : "";

        if (rowCount == 0) {
            result.put("type", "table");
            return result;
        }

        // 时间粒度由查询计划隐式加入时间列，结果仍可直接绘制趋势图。
        if (plan.get("timeGranularity") != null && mCount >= 1 && rowCount > 1) {
            result.put("type", "line");
            result.put("xKey", "time_" + String.valueOf(plan.get("timeGranularity")).toLowerCase(Locale.ROOT));
            result.put("yKey", metricPrefix + id(metrics.get(0)));
            result.put("xLabel", String.valueOf(plan.get("timeGranularity")));
            result.put("yLabel", metricLabel(explanation, 0));
            return result;
        }

        // 单指标无维度单行 → stat 数字卡片
        if (mCount == 1 && dCount == 0 && rowCount == 1) {
            result.put("type", "stat");
            result.put("valueKey", firstKey(rows.get(0)));
            result.put("label", metricLabel(explanation, 0));
            return result;
        }

        // 单维度 + 至少一个指标
        if (dCount == 1 && mCount >= 1) {
            String dimId = id(dimensions.get(0));
            String metId = id(metrics.get(0));
            String xKey = dimId != null ? dimId.toLowerCase(Locale.ROOT) : firstKey(rows.get(0));
            String yKey = metId != null ? metricPrefix + metId.toLowerCase(Locale.ROOT) : lastKey(rows.get(0));
            String dimName = dimensionLabel(explanation, 0);
            String metName = metricLabel(explanation, 0);

            result.put("xKey", xKey);
            result.put("yKey", yKey);
            result.put("xLabel", dimName != null ? dimName : dimId);
            result.put("yLabel", (metName != null ? metName : metId) + (comparison ? "（当前期）" : ""));

            if (isTime(dimName != null ? dimName : dimId)) {
                result.put("type", "line");
            } else if (rowCount <= 8 && mCount == 1) {
                result.put("type", "pie");
                result.put("nameKey", xKey);
                result.put("valueKey", yKey);
            } else {
                result.put("type", "bar");
            }
            return result;
        }

        // 多指标无维度单行 → 横向柱状图（指标对比）
        if (mCount > 1 && dCount == 0 && rowCount == 1) {
            result.put("type", "bar");
            result.put("multiMetric", true);
            return result;
        }

        result.put("type", "table");
        return result;
    }

    // ── 工具方法 ──────────────────────────────────────────────────────────────

    private boolean isTime(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        for (String kw : TIME_KEYWORDS) {
            if (lower.contains(kw.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private String id(Map<String, Object> item) {
        if (item == null) return null;
        Object v = item.get("id");
        return v == null ? null : String.valueOf(v);
    }

    private String firstKey(Map<String, Object> row) {
        if (row == null || row.isEmpty()) return null;
        return row.keySet().iterator().next();
    }

    private String lastKey(Map<String, Object> row) {
        if (row == null || row.isEmpty()) return null;
        String last = null;
        for (String k : row.keySet()) last = k;
        return last;
    }

    private String metricLabel(Map<String, Object> explanation, int index) {
        return labelFromExplanation(explanation, "metrics", index);
    }

    private String dimensionLabel(Map<String, Object> explanation, int index) {
        return labelFromExplanation(explanation, "dimensions", index);
    }

    @SuppressWarnings("unchecked")
    private String labelFromExplanation(Map<String, Object> explanation, String key, int index) {
        if (explanation == null) return null;
        Object listVal = explanation.get(key);
        if (!(listVal instanceof List)) return null;
        List<?> list = (List<?>) listVal;
        if (list.size() <= index) return null;
        Object item = list.get(index);
        if (!(item instanceof Map)) return null;
        Object name = ((Map<?, ?>) item).get("name");
        return name != null && !String.valueOf(name).isEmpty() ? String.valueOf(name) : null;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(Object value) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (value instanceof List) {
            for (Object row : (List<?>) value) {
                if (row instanceof Map) result.add((Map<String, Object>) row);
            }
        }
        return result;
    }
}

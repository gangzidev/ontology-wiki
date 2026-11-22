package com.ontologywiki.query.comparison;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.WeekFields;
import java.util.*;

public class QueryComparisonCalculator {
    public Map<String, Object> previousPlan(Map<String, Object> currentPlan, String comparisonType, int periods) {
        Map<String, Object> previousPlan = new LinkedHashMap<>(currentPlan);
        Map<?, ?> range = (Map<?, ?>) currentPlan.get("timeRange");
        int months = "YOY".equals(comparisonType) ? 12 * periods : "QOQ".equals(comparisonType) ? 3 * periods : "MOM".equals(comparisonType) ? periods : 0;
        int days = "WOW".equals(comparisonType) ? 7 * periods : 0;
        Map<String, Object> previousRange = new LinkedHashMap<>();
        previousRange.put("from", shiftDate(text(range.get("from")), months, days));
        previousRange.put("to", shiftDate(text(range.get("to")), months, days));
        previousPlan.put("timeRange", previousRange);
        return previousPlan;
    }

    public List<Map<String, Object>> compareRows(List<Map<String, Object>> current, List<Map<String, Object>> previous,
                                                  Map<String, Object> plan, String type, int periods) {
        Map<String, Map<String, Object>> priorByKey = new LinkedHashMap<>();
        for (Map<String, Object> row : previous) priorByKey.put(rowKey(row, plan, type, periods, true), row);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : current) {
            Map<String, Object> merged = new LinkedHashMap<>();
            for (String id : dimensionIds(plan)) merged.put(id.toLowerCase(Locale.ROOT), value(row, id));
            String granularity = text(plan.get("timeGranularity")).toLowerCase(Locale.ROOT);
            if (!granularity.isEmpty()) merged.put("time_" + granularity, value(row, "time_" + granularity));
            Map<String, Object> prior = priorByKey.get(rowKey(row, plan, type, periods, false));
            for (String metricId : metricIds(plan)) {
                Object currentValue = value(row, metricId);
                Object previousValue = prior == null ? null : value(prior, metricId);
                merged.put("current_" + metricId, currentValue);
                merged.put("previous_" + metricId, previousValue);
                BigDecimal currentNumber = decimal(currentValue), previousNumber = decimal(previousValue);
                BigDecimal change = currentNumber == null || previousNumber == null ? null : currentNumber.subtract(previousNumber);
                merged.put("change_" + metricId, change);
                merged.put("change_rate_" + metricId, change == null || previousNumber.compareTo(BigDecimal.ZERO) == 0
                        ? null : change.divide(previousNumber, 8, java.math.RoundingMode.HALF_UP));
            }
            result.add(merged);
        }
        return result;
    }

    private String rowKey(Map<String, Object> row, Map<String, Object> plan, String type, int periods, boolean shift) {
        List<String> values = new ArrayList<>();
        for (String id : dimensionIds(plan)) values.add(String.valueOf(value(row, id)));
        String granularity = text(plan.get("timeGranularity")).toUpperCase(Locale.ROOT);
        if (!granularity.isEmpty()) {
            String key = "time_" + granularity.toLowerCase(Locale.ROOT);
            Object raw = value(row, key);
            String normalized = normalizePeriod(raw, granularity);
            int months = "YOY".equals(type) ? 12 * periods : "QOQ".equals(type) ? 3 * periods : "MOM".equals(type) ? periods : 0;
            int days = "WOW".equals(type) ? 7 * periods : 0;
            values.add(shift ? shiftPeriod(normalized, granularity, months, days) : normalized);
        }
        return String.join("\u001f", values);
    }

    private String normalizePeriod(Object value, String granularity) {
        if (value == null) return "";
        LocalDate date = null;
        if (value instanceof java.sql.Date) date = ((java.sql.Date) value).toLocalDate();
        else if (value instanceof Timestamp) date = ((Timestamp) value).toLocalDateTime().toLocalDate();
        else if (value instanceof java.util.Date) date = new java.sql.Date(((java.util.Date) value).getTime()).toLocalDate();
        else {
            String text = String.valueOf(value);
            try {
                if (text.matches("\\d{4}-Q[1-4]")) return text;
                if (text.matches("\\d{4}-\\d{2}")) return text;
                if (text.matches("\\d{4}-\\d{2}-\\d{2}")) date = LocalDate.parse(text);
                else if (text.matches("\\d{4}")) return text;
                else date = Timestamp.valueOf(text.replace('T', ' ')).toLocalDateTime().toLocalDate();
            } catch (Exception ignored) { return text; }
        }
        if ("YEAR".equals(granularity)) return String.valueOf(date.getYear());
        if ("QUARTER".equals(granularity)) return date.getYear() + "-Q" + ((date.getMonthValue() - 1) / 3 + 1);
        if ("MONTH".equals(granularity)) return YearMonth.from(date).toString();
        if ("WEEK".equals(granularity)) return date.get(WeekFields.ISO.weekBasedYear()) + "-" + String.format(Locale.ROOT, "%02d", date.get(WeekFields.ISO.weekOfWeekBasedYear()));
        return date.toString();
    }

    private String shiftPeriod(String period, String granularity, int months, int days) {
        try {
            if ("YEAR".equals(granularity)) return String.valueOf(Integer.parseInt(period) + months / 12);
            if ("QUARTER".equals(granularity)) {
                String[] parts = period.split("-Q");
                LocalDate shifted = LocalDate.of(Integer.parseInt(parts[0]), (Integer.parseInt(parts[1]) - 1) * 3 + 1, 1).plusMonths(months).plusDays(days);
                return shifted.getYear() + "-Q" + ((shifted.getMonthValue() - 1) / 3 + 1);
            }
            if ("MONTH".equals(granularity)) return YearMonth.parse(period).plusMonths(months).plusMonths(days / 30).toString();
            if ("WEEK".equals(granularity)) {
                String[] parts = period.split("-");
                LocalDate monday = LocalDate.of(Integer.parseInt(parts[0]), 1, 4).with(WeekFields.ISO.weekOfWeekBasedYear(), Integer.parseInt(parts[1])).with(WeekFields.ISO.dayOfWeek(), 1);
                LocalDate shifted = monday.plusMonths(months).plusDays(days);
                return shifted.get(WeekFields.ISO.weekBasedYear()) + "-" + String.format(Locale.ROOT, "%02d", shifted.get(WeekFields.ISO.weekOfWeekBasedYear()));
            }
            return LocalDate.parse(period).plusMonths(months).plusDays(days).toString();
        } catch (Exception ignored) { return period; }
    }

    private String shiftDate(String value, int months, int days) { return LocalDate.parse(value).minusMonths(months).minusDays(days).toString(); }
    private Object value(Map<String, Object> row, String key) { return row.get(key.toLowerCase(Locale.ROOT)); }
    private BigDecimal decimal(Object value) { if (!(value instanceof Number)) return null; return new BigDecimal(String.valueOf(value)); }
    private List<String> dimensionIds(Map<String, Object> plan) {
        List<String> result = new ArrayList<>();
        for (Map<String, Object> row : rows(plan.get("dimensions"))) result.add(text(row.get("id")));
        Map<?, ?> drilldown = plan.get("drilldown") instanceof Map ? (Map<?, ?>) plan.get("drilldown") : Collections.emptyMap();
        String next = text(drilldown.get("nextDimensionId")); if (!next.isEmpty() && !result.contains(next)) result.add(next);
        return result;
    }
    private List<String> metricIds(Map<String, Object> plan) { List<String> result = new ArrayList<>(); for (Map<String, Object> row : rows(plan.get("metrics"))) result.add(text(row.get("id"))); return result; }
    public List<Object> concat(List<Object> left, List<Object> right) { List<Object> result = new ArrayList<>(left); result.addAll(right); return result; }
    private List<Map<String, Object>> rows(Object value) { List<Map<String, Object>> result = new ArrayList<>(); if (value instanceof List) for (Object row : (List<?>) value) if (row instanceof Map) result.add((Map<String, Object>) row); return result; }


    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
}

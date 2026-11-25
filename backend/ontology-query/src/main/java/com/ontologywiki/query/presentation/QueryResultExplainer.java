package com.ontologywiki.query.presentation;

import com.ontologywiki.query.catalog.SemanticCatalog;

import java.util.*;

public class QueryResultExplainer {
    public Map<String, Object> explain(Map<String, Object> plan, Map<String, Object> config, List<String> objectIds) {
        Map<String, Object> semantics = mergedSemantics(config);
        Map<String, Object> explanation = new LinkedHashMap<>();
        explanation.put("metrics", describe(plan.get("metrics"), semantics == null ? null : semantics.get("metrics")));
        explanation.put("dimensions", describe(plan.get("dimensions"), semantics == null ? null : semantics.get("dimensions")));
        explanation.put("filters", plan.getOrDefault("filters", Collections.emptyList()));
        if (plan.get("timeDimension") != null) explanation.put("timeDimension", plan.get("timeDimension"));
        if (plan.get("timeRange") != null) explanation.put("timeRange", plan.get("timeRange"));
        if (plan.get("timeGranularity") != null) explanation.put("timeGranularity", plan.get("timeGranularity"));
        if (plan.get("orderBy") != null) explanation.put("orderBy", plan.get("orderBy"));
        explanation.put("sourceObjects", objectIds);
        if (plan.get("detailObjectId") != null) {
            String objectId = String.valueOf(plan.get("detailObjectId"));
            for (Map<String, Object> object : rows(config.get("objects"))) if (objectId.equals(String.valueOf(object.get("id")))) {
                Map<String, Object> detail = new LinkedHashMap<>();
                for (String key : Arrays.asList("id", "name", "table")) detail.put(key, object.get(key));
                explanation.put("detailObject", detail);
            }
            Set<String> selected = new HashSet<>();
            for (Map<String, Object> field : rows(plan.get("fields"))) selected.add(String.valueOf(field.get("id")));
            Map<String, String> columns = new LinkedHashMap<>();
            for (Map<String, Object> property : rows(config.get("properties"))) {
                if (!objectId.equals(String.valueOf(property.get("objectId"))) || property.get("column") == null) continue;
                if (!selected.isEmpty() && !selected.contains(String.valueOf(property.get("id")))) continue;
                String column = String.valueOf(property.get("column")).toLowerCase(Locale.ROOT);
                if (!column.isEmpty()) columns.put(column, property.get("name") == null ? column : String.valueOf(property.get("name")));
            }
            explanation.put("columnLabels", columns);
        }
        return explanation;
    }
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(Object raw) { List<Map<String, Object>> result = new ArrayList<>(); if (raw instanceof List) for (Object item : (List<?>) raw) if (item instanceof Map) result.add((Map<String, Object>) item); return result; }
    private List<Map<String, Object>> describe(Object selected, Object definitions) {
        List<Map<String, Object>> result = new ArrayList<>();
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        if (definitions instanceof List) for (Object value : (List<?>) definitions) if (value instanceof Map) { Map<String, Object> row = (Map<String, Object>) value; byId.put(String.valueOf(row.get("id")), row); }
        if (selected instanceof List) for (Object value : (List<?>) selected) if (value instanceof Map) {
            Object id = ((Map<?, ?>) value).get("id"); Map<String, Object> definition = byId.get(String.valueOf(id));
            if (definition != null) { Map<String, Object> item = new LinkedHashMap<>(); item.put("id", id); item.put("name", definition.get("name")); item.put("definition", definition.get("definition")); item.put("propertyId", definition.get("propertyId")); item.put("aggregation", definition.get("aggregation")); result.add(item); }
        }
        return result;
    }
    private Map<String, Object> mergedSemantics(Map<String, Object> config) { return SemanticCatalog.from(config); }
}

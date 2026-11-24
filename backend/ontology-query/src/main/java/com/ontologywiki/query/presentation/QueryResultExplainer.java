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
        Map<String, Map<String, Object>> objectsById = index(rows(config.get("objects")));
        List<Map<String, Object>> sourceDetails = new ArrayList<>();
        for (String objectId : objectIds) {
            Map<String, Object> object = objectsById.get(objectId);
            if (object == null) continue;
            Map<String, Object> detail = new LinkedHashMap<>();
            for (String key : Arrays.asList("id", "name", "schema", "table")) if (object.containsKey(key)) detail.put(key, object.get(key));
            sourceDetails.add(detail);
        }
        explanation.put("sourceDetails", sourceDetails);
        Set<String> sourceIds = new LinkedHashSet<>(objectIds);
        List<Map<String, Object>> relations = rows(semantics == null ? null : semantics.get("relations"));
        if (relations.isEmpty()) relations = rows(config.get("relations"));
        List<Map<String, Object>> related = new ArrayList<>();
        for (Map<String, Object> relation : relations) {
            if (!sourceIds.contains(String.valueOf(relation.get("sourceObjectId")))
                    || !sourceIds.contains(String.valueOf(relation.get("targetObjectId")))) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            for (String key : Arrays.asList("id", "sourceObjectId", "targetObjectId", "sourceColumn", "targetColumn",
                    "sourceKeyColumn", "targetKeyColumn", "viaObjectId", "viaTable", "viaSourceColumn", "viaTargetColumn",
                    "cardinality", "inferenceMethod", "evidence", "reviewRequired")) {
                if (relation.containsKey(key)) item.put(key, relation.get(key));
            }
            addObjectLabel(item, "source", objectsById.get(String.valueOf(relation.get("sourceObjectId"))));
            addObjectLabel(item, "target", objectsById.get(String.valueOf(relation.get("targetObjectId"))));
            related.add(item);
        }
        explanation.put("sourceRelations", related);
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
    private Map<String, Map<String, Object>> index(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) result.put(String.valueOf(row.get("id")), row);
        return result;
    }
    private void addObjectLabel(Map<String, Object> relation, String prefix, Map<String, Object> object) {
        if (object == null) return;
        relation.put(prefix + "Name", object.get("name"));
        relation.put(prefix + "Table", object.get("table"));
        relation.put(prefix + "Schema", object.get("schema"));
    }
}

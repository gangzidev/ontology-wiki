package com.ontologywiki.ontology.service.analysis;

import java.util.*;

public class ProposalProjectionSynchronizer {
    public void synchronize(Map<String, Object> config) {
        Map<String, Object> target = map(config.get("targetConfig"));
        Map<String, Object> catalog = new LinkedHashMap<>();
        for (Object source : Arrays.asList(target.get("querySemantics"), target.get("semanticCatalog"), config.get("querySemantics"), config.get("semanticCatalog"))) catalog.putAll(map(source));
        for (String category : Arrays.asList("metrics", "dimensions", "timeDimensions", "terms")) {
            if (config.get(category) instanceof List) catalog.put(category, config.get(category));
        }
        if (!catalog.isEmpty()) {
            if (config.get("relations") instanceof List) {
                List<Map<String,Object>> relations = new ArrayList<>(rows(config.get("relations")));
                relations.removeIf(relation -> "m2mReification".equals(relation.get("inferenceMethod")));
                catalog.put("relations", relations);
            }
            config.put("semanticCatalog", catalog);
            if (config.containsKey("querySemantics")) config.put("querySemantics", catalog);
            if (target.containsKey("querySemantics")) target.put("querySemantics", catalog);
        }
        Map<String, Map<String, Object>> objects = index(config.get("objects"));
        List<Map<String, Object>> nodes = rows(map(config.get("taxonomy")).get("nodes"));
        nodes.removeIf(node -> !objects.containsKey(String.valueOf(node.get("id"))));
        for (Map<String, Object> node : nodes) node.put("name", objects.get(String.valueOf(node.get("id"))).get("name"));
        Map<String,Object> taxonomy = map(config.get("taxonomy"));
        rows(taxonomy.get("edges")).removeIf(edge -> !objects.containsKey(String.valueOf(edge.get("sourceId"))) || !objects.containsKey(String.valueOf(edge.get("targetId"))));
        if (taxonomy.get("rootIds") instanceof List) ((List<?>) taxonomy.get("rootIds")).removeIf(id -> !objects.containsKey(String.valueOf(id)));
        if (config.containsKey("mappings")) {
            List<Map<String, Object>> mappings = new ArrayList<>();
            for (Map<String, Object> property : rows(config.get("properties"))) {
                Map<String, Object> object = objects.get(String.valueOf(property.get("objectId")));
                if (object == null) continue;
                Map<String, Object> mapping = new LinkedHashMap<>();
                mapping.put("objectId", object.get("id"));
                mapping.put("propertyId", property.get("id"));
                mapping.put("table", object.get("table"));
                mapping.put("schema", object.get("schema"));
                mapping.put("column", property.get("column"));
                mappings.add(mapping);
            }
            config.put("mappings", mappings);
        }
        for (String category : Arrays.asList("objects", "properties", "relations", "logics", "actions", "serviceFlows", "mappings", "validationRules", "axioms", "semanticCatalog", "semanticNamingReport")) {
            if (config.containsKey(category)) target.put(category, config.get(category));
        }
        if (config.containsKey("permissionCandidates")) target.put("permissions", config.get("permissionCandidates"));
        for (Map<String, Object> stage : rows(config.get("stageReports"))) {
            Map<String, Object> result = map(stage.get("result"));
            for (String category : new ArrayList<>(result.keySet())) if (config.containsKey(category)) result.put(category, config.get(category));
            if (Integer.valueOf(8).equals(stage.get("stage"))) stage.put("result", config.get("semanticCatalog"));
        }
    }

    private Map<String, Map<String, Object>> index(Object value) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows(value)) result.put(String.valueOf(row.get("id")), row);
        return result;
    }
    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) { return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<>(); }
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(Object value) { return value instanceof List ? (List<Map<String, Object>>) value : new ArrayList<>(); }
}

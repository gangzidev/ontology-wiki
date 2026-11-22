package com.ontologywiki.query.planning;

import com.ontologywiki.query.catalog.QuerySemanticCatalogService;
import com.ontologywiki.query.catalog.SemanticCatalog;

import java.util.*;

final class QueryContextRetriever {
    private static final int MAX_CONTEXT_PROPERTIES = 200;
    private static final int MAX_SEMANTIC_CANDIDATES = 20;

    Map<String, Object> candidates(Map<String, Object> config, String question) {
        Map<String, Object> semantics = semantics(config);
        Map<String, Map<String, Object>> properties = index(rows(config.get("properties")));
        Map<String, Map<String, Object>> objects = index(rows(config.get("objects")));
        Map<String, Object> result = new LinkedHashMap<>();
        for (String category : Arrays.asList("metrics", "dimensions")) {
            List<Map<String, Object>> definitions = rows(semantics.get(category));
            Map<String, Integer> scores = new HashMap<>();
            for (Map<String, Object> definition : definitions) {
                Map<String, Object> property = properties.getOrDefault(text(definition.get("propertyId")), Collections.emptyMap());
                Map<String, Object> object = objects.getOrDefault(text(property.get("objectId")), Collections.emptyMap());
                int score = score(definition, question) * 2 + score(object, question);
                for (Map<String, Object> term : rows(semantics.get("terms"))) {
                    Object mapping = term.get("mapsTo");
                    if (mapping instanceof Map && text(definition.get("id")).equals(text(((Map<?, ?>) mapping).get(category.substring(0, category.length() - 1) + "Id")))) {
                        score += score(term, question) * 2;
                    }
                }
                scores.put(text(definition.get("id")), score);
            }
            definitions.removeIf(definition -> scores.get(text(definition.get("id"))) <= 0);
            definitions.sort(Comparator.comparingInt((Map<String, Object> definition) -> scores.get(text(definition.get("id")))).reversed());
            List<Map<String, Object>> selected = new ArrayList<>();
            for (Map<String, Object> definition : definitions.subList(0, Math.min(MAX_SEMANTIC_CANDIDATES, definitions.size()))) {
                Map<String, Object> item = compact(definition, "id", "name", "definition", "synonyms", "aggregation", "propertyId", "filter", "filters");
                Map<String, Object> property = properties.getOrDefault(text(definition.get("propertyId")), Collections.emptyMap());
                Map<String, Object> object = objects.getOrDefault(text(property.get("objectId")), Collections.emptyMap());
                item.put("objectName", object.get("name"));
                item.put("table", object.get("table"));
                item.put("fieldMeaning", property.get("description"));
                selected.add(item);
            }
            result.put(category, selected);
        }
        return result;
    }

    Map<String, Object> context(Map<String, Object> config, String question, QuerySemanticCatalogService.Resolution resolution) {
        Map<String, Object> semantics = semantics(config);
        Map<String, Map<String, Object>> metrics = index(rows(semantics.get("metrics")));
        Map<String, Map<String, Object>> properties = index(rows(config.get("properties")));
        Set<String> required = new LinkedHashSet<>();
        Set<String> visitedMetrics = new HashSet<>();
        for (String metricId : resolution.getMetricIds()) collectMetric(metricId, metrics, required, visitedMetrics);
        for (Map<String, Object> dimension : rows(semantics.get("dimensions"))) {
            if (resolution.getDimensionIds().contains(text(dimension.get("id")))) required.add(text(dimension.get("propertyId")));
        }
        for (Map<String, Object> filter : resolution.getDefaultFilters()) required.add(text(filter.get("propertyId")));
        Set<String> selectedObjects = new LinkedHashSet<>();
        if (resolution.isDetailQuery()) selectedObjects.add(resolution.getDetailObjectId());
        for (String propertyId : required) {
            Map<String, Object> property = properties.get(propertyId);
            if (property != null) selectedObjects.add(text(property.get("objectId")));
        }
        List<Map<String, Object>> relations = rows(semantics.get("relations"));
        if (relations.isEmpty()) relations = rows(config.get("relations"));
        Set<String> reachable = new LinkedHashSet<>(selectedObjects);
        boolean changed;
        do {
            changed = false;
            for (Map<String, Object> relation : relations) {
                String source = text(relation.get("sourceObjectId")), target = text(relation.get("targetObjectId"));
                if (reachable.contains(source) || reachable.contains(target)) {
                    changed |= reachable.add(source);
                    changed |= reachable.add(target);
                    if (relation.get("viaObjectId") != null) changed |= reachable.add(text(relation.get("viaObjectId")));
                }
            }
        } while (changed);
        Set<String> mentionedObjects = new HashSet<>();
        for (Map<String, Object> object : rows(config.get("objects"))) {
            if (reachable.contains(text(object.get("id"))) && score(object, question) > 0) mentionedObjects.add(text(object.get("id")));
        }
        List<Map<String, Object>> candidates = new ArrayList<>();
        Map<String, Integer> scores = new HashMap<>();
        for (Map<String, Object> property : properties.values()) {
            String propertyId = text(property.get("id")), objectId = text(property.get("objectId"));
            int match = score(property, question);
            if (!required.contains(propertyId) && !selectedObjects.contains(objectId)
                    && !(reachable.contains(objectId) && (match > 0 || mentionedObjects.contains(objectId)))) continue;
            int score = (required.contains(propertyId) ? 100000 : 0) + match * 100
                    + (selectedObjects.contains(objectId) ? 20 : 0) + (mentionedObjects.contains(objectId) ? 30 : 0);
            scores.put(propertyId, score);
            candidates.add(property);
        }
        candidates.sort(Comparator.comparingInt((Map<String, Object> property) -> scores.get(text(property.get("id")))).reversed());
        Set<String> selectedProperties = new LinkedHashSet<>(required);
        for (Map<String, Object> property : candidates) {
            if (selectedProperties.size() >= MAX_CONTEXT_PROPERTIES) break;
            selectedProperties.add(text(property.get("id")));
        }
        List<Map<String, Object>> timeDimensions = new ArrayList<>();
        for (Map<String, Object> time : rows(semantics.get("timeDimensions"))) {
            Map<String, Object> property = properties.get(text(time.get("propertyId")));
            if (property != null && (selectedObjects.contains(text(property.get("objectId")))
                    || (reachable.contains(text(property.get("objectId"))) && score(time, question) > 0))) {
                timeDimensions.add(time);
                selectedProperties.add(text(property.get("id")));
            }
        }
        List<Map<String, Object>> selected = new ArrayList<>();
        Set<String> contextObjects = new LinkedHashSet<>();
        for (String propertyId : selectedProperties) {
            Map<String, Object> property = properties.get(propertyId);
            if (property == null) continue;
            selected.add(compact(property, "id", "name", "description", "column", "objectId", "dataType"));
            contextObjects.add(text(property.get("objectId")));
        }
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("properties", selected);
        context.put("timeDimensions", timeDimensions);
        List<Map<String, Object>> objects = rows(config.get("objects"));
        objects.removeIf(object -> !contextObjects.contains(text(object.get("id"))));
        List<Map<String, Object>> objectNames = new ArrayList<>();
        for (Map<String, Object> object : objects) objectNames.add(compact(object, "id", "name", "table"));
        context.put("objects", objectNames);
        relations.removeIf(relation -> !contextObjects.contains(text(relation.get("sourceObjectId")))
                || !contextObjects.contains(text(relation.get("targetObjectId"))));
        context.put("relations", relations);
        return context;
    }

    void validateReferences(Map<String, Object> plan, Map<String, Object> context) {
        Set<String> properties = index(rows(context.get("properties"))).keySet();
        for (Map<String, Object> filter : rows(plan.get("filters"))) {
            if (!properties.contains(text(filter.get("propertyId")))) throw new IllegalArgumentException("条件解析引用了本次查询范围外的字段");
        }
        if (plan.get("timeDimension") != null && !index(rows(context.get("timeDimensions"))).containsKey(text(plan.get("timeDimension")))) {
            throw new IllegalArgumentException("条件解析引用了本次查询范围外的时间维度");
        }
    }

    private void collectMetric(String id, Map<String, Map<String, Object>> metrics, Set<String> required, Set<String> visited) {
        if (!visited.add(id)) return;
        Map<String, Object> metric = metrics.get(id);
        if (metric == null) return;
        collectReferences(metric, metrics, required, visited);
    }

    private void collectReferences(Object value, Map<String, Map<String, Object>> metrics, Set<String> required, Set<String> visited) {
        if (value instanceof Map) {
            Map<?, ?> entry = (Map<?, ?>) value;
            if (entry.get("propertyId") != null) required.add(text(entry.get("propertyId")));
            if (entry.get("metricId") != null) collectMetric(text(entry.get("metricId")), metrics, required, visited);
            for (Object nested : entry.values()) if (nested instanceof Map || nested instanceof List) collectReferences(nested, metrics, required, visited);
        } else if (value instanceof List) {
            for (Object nested : (List<?>) value) collectReferences(nested, metrics, required, visited);
        }
    }

    private int score(Map<String, Object> item, String question) {
        String normalized = QuerySemanticCatalogService.normalize(question);
        Set<String> aliases = new LinkedHashSet<>();
        for (String field : Arrays.asList("name", "label", "term", "column", "table")) aliases.add(text(item.get(field)));
        if (item.get("synonyms") instanceof List) for (Object alias : (List<?>) item.get("synonyms")) aliases.add(text(alias));
        int best = 0;
        for (String alias : aliases) {
            String value = QuerySemanticCatalogService.normalize(alias);
            if (value.length() < 2) continue;
            if (normalized.contains(value)) best = Math.max(best, 50 + value.length());
            else {
                int overlap = 0;
                for (int offset = 0; offset + 1 < value.length(); offset++) {
                    String pair = value.substring(offset, offset + 2);
                    if (pair.matches("[\\p{IsHan}]{2}") && !Arrays.asList("数量", "金额", "是否", "统计", "查询").contains(pair) && normalized.contains(pair)) overlap++;
                }
                best = Math.max(best, overlap);
            }
        }
        return best;
    }

    static Map<String, Object> semantics(Map<String, Object> config) { return SemanticCatalog.from(config); }
    private Map<String, Object> compact(Map<String, Object> row, String... fields) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String field : fields) if (row.containsKey(field)) result.put(field, row.get(field));
        return result;
    }
    private Map<String, Map<String, Object>> index(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) result.put(text(row.get("id")), row);
        return result;
    }
    private List<Map<String, Object>> rows(Object value) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (value instanceof List) for (Object row : (List<?>) value) if (row instanceof Map) result.add((Map<String, Object>) row);
        return result;
    }
    private String text(Object value) { return value == null ? "" : String.valueOf(value); }
}

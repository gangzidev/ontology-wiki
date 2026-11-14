package com.ontologywiki.ontology.service;

import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 外部本体平台配置的通用双向适配器。
 * 只转换语义配置结构，不携带凭据，也不执行导入副作用。
 */
@Service
public class ExternalOntologyConfigAdapter {

    /** 将常见 external objects/entities/attributes 结构归一化为本系统配置。 */
    public Map<String, Object> importConfig(Map<String, Object> external) {
        if (external == null) throw new IllegalArgumentException("外部本体配置不能为空");
        Map<String, Object> source = map(external.get("targetConfig"));
        if (source.isEmpty()) source = external;
        Map<String, Object> result = new LinkedHashMap<>(source);
        Map<String, Object> catalog = map(source.get("semanticCatalog"));
        if (catalog.isEmpty()) catalog = map(source.get("querySemantics"));
        catalog.put("metrics", normalizeMetrics(firstList(source, "metrics", catalog.get("metrics"))));
        catalog.put("dimensions", normalizeDimensions(firstList(source, "dimensions", catalog.get("dimensions"))));
        catalog.put("timeDimensions", normalizeDimensions(firstList(source, "timeDimensions", catalog.get("timeDimensions"))));
        catalog.put("terms", copyRows(firstList(source, "terms", catalog.get("terms"))));
        catalog.put("relations", normalizeRelations(firstList(source, "relations", catalog.get("relations"))));
        result.put("objects", normalizeObjects(firstList(source, "objects", source.get("entities"))));
        result.put("properties", normalizeProperties(firstList(source, "properties", source.get("attributes"))));
        result.put("relations", catalog.get("relations"));
        result.remove("querySemantics");
        result.put("semanticCatalog", catalog);
        result.put("externalSource", external.get("source") == null ? external.get("platform") : external.get("source"));
        return result;
    }

    /** 导出为外部平台通用 objects/properties/relations/semanticCatalog 结构。 */
    public Map<String, Object> exportConfig(Map<String, Object> config) {
        if (config == null) throw new IllegalArgumentException("本体配置不能为空");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", "1.0");
        result.put("objects", copyRows(list(config.get("objects"))));
        result.put("properties", copyRows(list(config.get("properties"))));
        result.put("relations", copyRows(firstList(config, "relations", map(config.get("semanticCatalog")).get("relations"))));
        Map<String, Object> catalog = map(config.get("semanticCatalog"));
        if (catalog.isEmpty()) catalog = map(config.get("querySemantics"));
        Map<String, Object> semantic = new LinkedHashMap<>();
        semantic.put("metrics", copyRows(list(catalog.get("metrics"))));
        semantic.put("dimensions", copyRows(list(catalog.get("dimensions"))));
        semantic.put("timeDimensions", copyRows(list(catalog.get("timeDimensions"))));
        semantic.put("terms", copyRows(list(catalog.get("terms"))));
        semantic.put("relations", copyRows(list(catalog.get("relations"))));
        result.put("semanticCatalog", semantic);
        return result;
    }

    private List<Map<String, Object>> normalizeObjects(Object value) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : copyRows(list(value))) {
            Map<String, Object> normalized = new LinkedHashMap<>(row);
            if (!normalized.containsKey("id") && normalized.get("entityId") != null) normalized.put("id", normalized.get("entityId"));
            if (!normalized.containsKey("name") && normalized.get("label") != null) normalized.put("name", normalized.get("label"));
            if (!normalized.containsKey("table") && normalized.get("physicalTable") != null) normalized.put("table", normalized.get("physicalTable"));
            if (!normalized.containsKey("schema") && normalized.get("schemaName") != null) normalized.put("schema", normalized.get("schemaName"));
            result.add(normalized);
        }
        return result;
    }

    private List<Map<String, Object>> normalizeProperties(Object value) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : copyRows(list(value))) {
            Map<String, Object> normalized = new LinkedHashMap<>(row);
            if (!normalized.containsKey("id") && normalized.get("fieldId") != null) normalized.put("id", normalized.get("fieldId"));
            if (!normalized.containsKey("objectId") && normalized.get("entityId") != null) normalized.put("objectId", normalized.get("entityId"));
            if (!normalized.containsKey("column") && normalized.get("physicalColumn") != null) normalized.put("column", normalized.get("physicalColumn"));
            if (!normalized.containsKey("column") && normalized.get("columnName") != null) normalized.put("column", normalized.get("columnName"));
            if (!normalized.containsKey("name") && normalized.get("label") != null) normalized.put("name", normalized.get("label"));
            result.add(normalized);
        }
        return result;
    }

    private List<Map<String, Object>> normalizeMetrics(Object value) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : copyRows(list(value))) {
            Map<String, Object> normalized = new LinkedHashMap<>(row);
            if (!normalized.containsKey("name") && normalized.get("label") != null) normalized.put("name", normalized.get("label"));
            if (!normalized.containsKey("aggregation") && normalized.get("aggregate") != null) normalized.put("aggregation", normalized.get("aggregate"));
            if (!normalized.containsKey("propertyId")) normalized.put("propertyId", first(row, "fieldId", "attributeId"));
            result.add(normalized);
        }
        return result;
    }

    private List<Map<String, Object>> normalizeDimensions(Object value) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : copyRows(list(value))) {
            Map<String, Object> normalized = new LinkedHashMap<>(row);
            if (!normalized.containsKey("name") && normalized.get("label") != null) normalized.put("name", normalized.get("label"));
            if (!normalized.containsKey("propertyId")) normalized.put("propertyId", first(row, "fieldId", "attributeId"));
            result.add(normalized);
        }
        return result;
    }

    private Object firstList(Map<String, Object> source, String key, Object fallback) {
        Object value = source.get(key);
        if (value instanceof List && !((List<?>) value).isEmpty()) return value;
        return fallback == null ? value : fallback;
    }
    private List<Map<String, Object>> normalizeRelations(Object value) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : copyRows(value)) {
            Map<String, Object> normalized = new LinkedHashMap<>(row);
            if (!normalized.containsKey("sourceObjectId")) normalized.put("sourceObjectId", first(row, "source", "subjectId", "fromObjectId"));
            if (!normalized.containsKey("targetObjectId")) normalized.put("targetObjectId", first(row, "target", "objectId", "toObjectId"));
            if (!normalized.containsKey("sourceColumn")) normalized.put("sourceColumn", first(row, "sourceField", "sourceAttribute"));
            if (!normalized.containsKey("targetColumn")) normalized.put("targetColumn", first(row, "targetField", "targetAttribute"));
            if (!normalized.containsKey("sourceKeyColumn")) normalized.put("sourceKeyColumn", first(row, "sourceKeyField", "sourceObjectField"));
            if (!normalized.containsKey("targetKeyColumn")) normalized.put("targetKeyColumn", first(row, "targetKeyField", "targetObjectField"));
            if (!normalized.containsKey("viaSourceColumn")) normalized.put("viaSourceColumn", first(row, "middleSourceField", "viaSourceField"));
            if (!normalized.containsKey("viaTargetColumn")) normalized.put("viaTargetColumn", first(row, "middleTargetField", "viaTargetField"));
            if (!normalized.containsKey("viaTable")) normalized.put("viaTable", first(row, "middleTable", "joinTable", "physicalJoinTable"));
            if (!normalized.containsKey("viaObjectId")) normalized.put("viaObjectId", first(row, "middleObjectId", "joinObjectId"));
            result.add(normalized);
        }
        return result;
    }
    private Object first(Map<String, Object> row, String... keys) { for (String key : keys) if (row.get(key) != null) return row.get(key); return null; }
    private Map<String, Object> map(Object value) { if (!(value instanceof Map)) return new LinkedHashMap<>(); Map<String, Object> result = new LinkedHashMap<>(); for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) result.put(String.valueOf(e.getKey()), e.getValue()); return result; }
    private List<?> list(Object value) { return value instanceof List ? (List<?>) value : Collections.emptyList(); }
    private List<Map<String, Object>> copyRows(Object value) { List<Map<String, Object>> result = new ArrayList<>(); for (Object item : list(value)) if (item instanceof Map) result.add(map(item)); return result; }
}

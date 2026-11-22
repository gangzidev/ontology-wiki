package com.ontologywiki.query.validation;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 查询配置校验器：检查审核状态及语义映射，评测作为独立的质量记录。 */
public class QueryConfigurationValidator {
    private static final Set<String> AGGREGATIONS = new HashSet<>(java.util.Arrays.asList("COUNT", "SUM", "AVG", "MIN", "MAX"));
    /** 返回阻止查询的原因；返回空列表才允许进入后续评测或查询。 */
    public List<String> validate(String status, JsonNode config, boolean evaluationPassed) {
        List<String> reasons = new ArrayList<>();
        if (!"COMPLETED".equalsIgnoreCase(status) && !"IMPORTED".equalsIgnoreCase(status)) {
            reasons.add("配置草案尚未完成审核");
        }
        JsonNode semantics = mergedSemantics(config);
        if (semantics == null || !semantics.isObject()) {
            if (!hasDetailMapping(config)) reasons.add("缺少可查询的对象字段映射或 querySemantics 查询语义定义");
        } else {
            Map<String, JsonNode> properties = index(config.path("properties"));
            Set<String> objects = ids(config.path("objects"));
            JsonNode metrics = semantics.path("metrics");
            boolean noMetrics = metrics.isMissingNode() || metrics.isNull() || (metrics.isArray() && metrics.size() == 0);
            if (!noMetrics || !hasDetailMapping(config)) checkPropertyEntries(semantics, "metrics", properties, reasons);
            checkPropertyEntries(semantics, "dimensions", properties, reasons);
            checkTimeDimensions(semantics, properties, reasons);
            JsonNode relations = semantics.path("relations");
            if (!relations.isMissingNode() && !relations.isNull()) {
                if (!relations.isArray()) reasons.add("querySemantics.relations 必须是数组");
                else checkRelations(relations, objects, properties, reasons);
            }
        }
        return reasons;
    }

    private boolean hasDetailMapping(JsonNode config) {
        if (config == null) return false;
        Map<String, JsonNode> objects = index(config.path("objects"));
        for (JsonNode property : config.path("properties")) {
            JsonNode object = objects.get(text(property, "objectId"));
            if (object != null && !text(object, "table").isEmpty() && !text(property, "id").isEmpty() && !text(property, "column").isEmpty()) return true;
        }
        return false;
    }

    private void checkPropertyEntries(JsonNode semantics, String key, Map<String, JsonNode> properties, List<String> reasons) {
        JsonNode entries = semantics.path(key);
        if ("dimensions".equals(key) && (entries.isMissingNode() || entries.isNull())) return;
        if ("dimensions".equals(key) && entries.isArray() && entries.size() == 0) return;
        if (!entries.isArray() || entries.size() == 0) {
            reasons.add("querySemantics." + key + " 不能为空");
            return;
        }
        for (int i = 0; i < entries.size(); i++) {
            JsonNode entry = entries.get(i);
            requireText(entry, "id", key, i, reasons);
            requireText(entry, "name", key, i, reasons);
            if ("metrics".equals(key)) {
                String type = text(entry, "type").toUpperCase(java.util.Locale.ROOT);
                String aggregation = text(entry, "aggregation");
                boolean computed = "COMPUTED".equals(type) || "COMPUTED".equals(aggregation) || entry.path("formula").isObject();
                if (computed) {
                    checkFormula(entry.path("formula"), entries, properties, reasons, key, i, new HashSet<String>());
                    continue;
                }
                String propertyId = text(entry, "propertyId");
                JsonNode property = properties.get(propertyId);
                if (property == null) {
                    reasons.add("querySemantics." + key + "[" + i + "] 引用了不存在的属性 " + propertyId);
                } else if (text(property, "objectId").isEmpty() || text(property, "column").isEmpty()) {
                    reasons.add("querySemantics." + key + "[" + i + "] 属性没有完整的对象和物理字段映射");
                }
                if (!AGGREGATIONS.contains(aggregation)) {
                    reasons.add("querySemantics.metrics[" + i + "] 聚合方式不受支持");
                } else if (("SUM".equals(aggregation) || "AVG".equals(aggregation))
                        && property != null && !isNumeric(text(property, "dataType"))) {
                    reasons.add("querySemantics.metrics[" + i + "] 的 SUM/AVG 属性必须是数值类型");
                }
            } else {
                String propertyId = text(entry, "propertyId");
                JsonNode property = properties.get(propertyId);
                if (property == null) reasons.add("querySemantics." + key + "[" + i + "] 引用了不存在的属性 " + propertyId);
                else if (text(property, "objectId").isEmpty() || text(property, "column").isEmpty()) reasons.add("querySemantics." + key + "[" + i + "] 属性没有完整的对象和物理字段映射");
            }
        }
    }

    private void checkFormula(JsonNode formula, JsonNode metrics, Map<String, JsonNode> properties,
                              List<String> reasons, String key, int index, Set<String> visiting) {
        if (!formula.isObject()) { reasons.add("querySemantics." + key + "[" + index + "].formula 必须是对象"); return; }
        String metricId = text(formula, "metricId");
        if (!metricId.isEmpty()) {
            boolean found = false;
            if (metrics.isArray()) for (JsonNode metric : metrics) if (metricId.equals(text(metric, "id"))) { found = true; break; }
            if (!found) reasons.add("querySemantics." + key + "[" + index + "] 引用了不存在的计算指标 " + metricId);
            else if (!visiting.add(metricId)) reasons.add("querySemantics." + key + "[" + index + "] 计算指标存在循环引用 " + metricId);
            else for (JsonNode metric : metrics) if (metricId.equals(text(metric, "id")) && metric.path("formula").isObject()) {
                checkFormula(metric.path("formula"), metrics, properties, reasons, key, index, visiting);
                break;
            }
            visiting.remove(metricId);
            return;
        }
        String propertyId = text(formula, "propertyId");
        if (!propertyId.isEmpty()) {
            if (!properties.containsKey(propertyId)) reasons.add("querySemantics." + key + "[" + index + "] 公式属性不存在 " + propertyId);
            return;
        }
        if (formula.has("value")) { if (!formula.get("value").isNumber()) reasons.add("querySemantics." + key + "[" + index + "] 公式常量必须是数字"); return; }
        String operator = text(formula, "operator").toUpperCase(java.util.Locale.ROOT);
        if (java.util.Arrays.asList("SUM", "COUNT", "AVG", "MIN", "MAX").contains(operator)) {
            if (!formula.path("left").isObject()) reasons.add("querySemantics." + key + "[" + index + "] 聚合公式缺少操作数");
            else checkFormula(formula.path("left"), metrics, properties, reasons, key, index, visiting);
            return;
        }
        if (!java.util.Arrays.asList("ADD", "SUBTRACT", "MULTIPLY", "DIVIDE", "SAFE_DIVIDE").contains(operator)) reasons.add("querySemantics." + key + "[" + index + "] 公式操作不受支持");
        if (!formula.path("left").isObject() || !formula.path("right").isObject()) reasons.add("querySemantics." + key + "[" + index + "] 公式必须包含左右操作数");
        else {
            checkFormula(formula.path("left"), metrics, properties, reasons, key, index, visiting);
            checkFormula(formula.path("right"), metrics, properties, reasons, key, index, visiting);
        }
    }

    private void checkRelations(JsonNode relations, Set<String> objects, Map<String, JsonNode> properties, List<String> reasons) {
        if (!relations.isArray()) return;
        for (int i = 0; i < relations.size(); i++) {
            JsonNode relation = relations.get(i);
            requireText(relation, "id", "relations", i, reasons);
            String sourceId = text(relation, "sourceObjectId");
            String targetId = text(relation, "targetObjectId");
            if (!objects.contains(sourceId) || !objects.contains(targetId)) {
                reasons.add("querySemantics.relations[" + i + "] 引用了不存在的对象");
            }
            if (relation.hasNonNull("viaTable") || relation.hasNonNull("viaObjectId")) {
                requireMappedColumn(properties.values(), sourceId, text(relation, "sourceKeyColumn"), "sourceKeyColumn", i, reasons);
                requireMappedColumn(properties.values(), targetId, text(relation, "targetKeyColumn"), "targetKeyColumn", i, reasons);
                if (text(relation, "viaSourceColumn").isEmpty() || text(relation, "viaTargetColumn").isEmpty()) {
                    reasons.add("querySemantics.relations[" + i + "] 中间表字段映射不完整");
                }
                String viaObjectId = text(relation, "viaObjectId");
                if (!viaObjectId.isEmpty() && !objects.contains(viaObjectId)) reasons.add("querySemantics.relations[" + i + "] 引用了不存在的中间对象");
                if (text(relation, "viaTable").isEmpty() && viaObjectId.isEmpty()) reasons.add("querySemantics.relations[" + i + "] 缺少中间表映射");
            } else {
                requireMappedColumn(properties.values(), sourceId, text(relation, "sourceColumn"), "sourceColumn", i, reasons);
                requireMappedColumn(properties.values(), targetId, text(relation, "targetColumn"), "targetColumn", i, reasons);
            }
        }
    }

    private void checkTimeDimensions(JsonNode semantics, Map<String, JsonNode> properties, List<String> reasons) {
        JsonNode entries = semantics.path("timeDimensions");
        if (entries.isMissingNode() || entries.isNull()) return;
        if (!entries.isArray()) { reasons.add("querySemantics.timeDimensions 必须是数组"); return; }
        for (int i = 0; i < entries.size(); i++) {
            JsonNode entry = entries.get(i);
            requireText(entry, "id", "timeDimensions", i, reasons);
            requireText(entry, "name", "timeDimensions", i, reasons);
            String propertyId = text(entry, "propertyId");
            JsonNode property = properties.get(propertyId);
            if (property == null) reasons.add("querySemantics.timeDimensions[" + i + "] 引用了不存在的属性 " + propertyId);
            else if (text(property, "objectId").isEmpty() || text(property, "column").isEmpty()) reasons.add("querySemantics.timeDimensions[" + i + "] 属性没有完整的对象和物理字段映射");
        }
    }

    private void requireMappedColumn(Iterable<JsonNode> properties, String objectId, String column, String field, int index, List<String> reasons) {
        for (JsonNode property : properties) {
            if (objectId.equals(text(property, "objectId")) && column.equalsIgnoreCase(text(property, "column"))) return;
        }
        reasons.add("querySemantics.relations[" + index + "]." + field + " 未映射到本体属性");
    }

    private void requireText(JsonNode entry, String field, String category, int index, List<String> reasons) {
        if (text(entry, field).isEmpty()) reasons.add("querySemantics." + category + "[" + index + "]." + field + " 未填写");
    }

    private Map<String, JsonNode> index(JsonNode entries) {
        Map<String, JsonNode> result = new HashMap<>();
        if (entries.isArray()) for (JsonNode entry : entries) result.put(text(entry, "id"), entry);
        return result;
    }

    private Set<String> ids(JsonNode entries) {
        Set<String> result = new HashSet<>();
        if (entries.isArray()) for (JsonNode entry : entries) result.add(text(entry, "id"));
        return result;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.path(field);
        return value == null || !value.isValueNode() ? "" : value.asText().trim();
    }

    private boolean isNumeric(String dataType) {
        String type = dataType.toUpperCase(java.util.Locale.ROOT);
        return type.matches(".*\\b(TINYINT|SMALLINT|MEDIUMINT|INT|INTEGER|BIGINT|DECIMAL|NUMERIC|FLOAT|DOUBLE|REAL|NUMBER)\\b.*");
    }

    /** 新目录优先，旧目录补齐未迁移的字段。 */
    private JsonNode mergedSemantics(JsonNode config) {
        JsonNode merged = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        copyObject(config == null ? null : config.path("querySemantics"), (com.fasterxml.jackson.databind.node.ObjectNode) merged);
        copyObject(config == null ? null : config.path("semanticCatalog"), (com.fasterxml.jackson.databind.node.ObjectNode) merged);
        return merged;
    }

    private void copyObject(JsonNode source, com.fasterxml.jackson.databind.node.ObjectNode target) {
        if (source != null && source.isObject()) source.fields().forEachRemaining(entry -> target.set(entry.getKey(), entry.getValue()));
    }
}

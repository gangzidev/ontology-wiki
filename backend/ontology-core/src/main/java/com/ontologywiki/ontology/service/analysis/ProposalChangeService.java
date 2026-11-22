package com.ontologywiki.ontology.service.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class ProposalChangeService {
    private final ObjectMapper json = new ObjectMapper();

    private static final Map<String,Set<String>> ANALYSIS_EDITABLE_FIELDS = new LinkedHashMap<>();
    static {
        ANALYSIS_EDITABLE_FIELDS.put("objects", new HashSet<>(Arrays.asList("name", "table", "technicalName", "description")));
        ANALYSIS_EDITABLE_FIELDS.put("properties", new HashSet<>(Arrays.asList("name", "column", "technicalName", "objectId", "dataType", "description", "nullable", "isPrimaryKey")));
        ANALYSIS_EDITABLE_FIELDS.put("relations", new HashSet<>(Arrays.asList("name", "sourceObjectId", "targetObjectId", "sourceColumn", "targetColumn", "cardinality", "description")));
        ANALYSIS_EDITABLE_FIELDS.put("logics", new HashSet<>(Arrays.asList("name", "conditionText", "risk", "status", "description")));
        ANALYSIS_EDITABLE_FIELDS.put("actions", new HashSet<>(Arrays.asList("name", "operation", "objectId", "risk", "status", "description")));
        ANALYSIS_EDITABLE_FIELDS.put("serviceFlows", new HashSet<>(Arrays.asList("name", "description", "trigger", "steps")));
        ANALYSIS_EDITABLE_FIELDS.put("permissionCandidates", new HashSet<>(Arrays.asList("operation", "resourceId", "subjectType", "suggestedSubjects", "rowFilterRequired", "status")));
        ANALYSIS_EDITABLE_FIELDS.put("metrics", new HashSet<>(Arrays.asList("name", "definition", "aggregation", "propertyId", "synonyms")));
        ANALYSIS_EDITABLE_FIELDS.put("dimensions", new HashSet<>(Arrays.asList("name", "definition", "propertyId", "synonyms")));
        ANALYSIS_EDITABLE_FIELDS.put("timeDimensions", new HashSet<>(Arrays.asList("name", "definition", "propertyId", "synonyms", "granularities", "default")));
        ANALYSIS_EDITABLE_FIELDS.put("terms", new HashSet<>(Arrays.asList("name", "description", "synonyms")));
    }

    private Map<String,Object> readAnalysisConfig(Object raw) {
        if (raw == null || String.valueOf(raw).trim().isEmpty()) return new LinkedHashMap<>();
        try { return json.readValue(String.valueOf(raw), new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() { }); }
        catch (Exception e) { throw new IllegalStateException("分析草案结构无法解析"); }
    }

    private String writeJson(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("分析草案结构序列化失败"); }
    }

    public List<Map<String,Object>> normalizeAnalysisChanges(Map<String,Object> base, List<?> requested, boolean strict) {
        return normalizeAnalysisChanges(base, requested, strict, true);
    }

    public List<Map<String,Object>> normalizeAnalysisChanges(Map<String,Object> base, List<?> requested, boolean strict, boolean removeUnchanged) {
        Map<String,Map<String,Object>> latest = new LinkedHashMap<>();
        Map<String,Object> working = readAnalysisConfig(writeJson(base));
        if (requested == null) return new ArrayList<>();
        if (requested.size() > 200) throw new IllegalArgumentException("单次分析修正不能超过200项");
        for (Object raw : requested) {
            if (!(raw instanceof Map)) { if (strict) throw new IllegalArgumentException("分析修正格式无效"); else continue; }
            Map<?,?> item = (Map<?,?>) raw;
            String category = item.get("category") == null ? "" : String.valueOf(item.get("category"));
            String operation = item.get("op") == null ? "UPDATE" : String.valueOf(item.get("op")).trim().toUpperCase(Locale.ROOT);
            if (!Arrays.asList("ADD", "UPDATE", "DELETE").contains(operation)) { if (strict) throw new IllegalArgumentException("变更类型只支持 ADD、UPDATE 或 DELETE"); else continue; }
            Object rawValue = item.get("value");
            String id = item.get("id") == null ? "" : String.valueOf(item.get("id"));
            if ("ADD".equals(operation) && rawValue instanceof Map && ((Map<?,?>) rawValue).get("id") != null) id = String.valueOf(((Map<?,?>) rawValue).get("id"));
            String field = item.get("field") == null ? "" : String.valueOf(item.get("field"));
            Object value = item.get("value");
            List<?> candidates = candidateRows(working, category);
            if (!ANALYSIS_EDITABLE_FIELDS.containsKey(category)) { if (strict) throw new IllegalArgumentException("不支持修改该分析分类：" + category); else continue; }
            final String candidateId = id;
            boolean found = candidates.stream().anyMatch(candidate -> candidate instanceof Map && Objects.equals(candidateId, String.valueOf(((Map<?,?>) candidate).get("id"))));
            if (alreadyAppliedAnalysisChange(base, item, category, operation, id, field, value, found)) continue;
            if ("ADD".equals(operation)) {
                if (!(value instanceof Map)) { if (strict) throw new IllegalArgumentException("新增项必须包含完整对象 value"); else continue; }
                Map<String,Object> added = copyMap((Map<?,?>) value);
                if (id.trim().isEmpty()) id = UUID.randomUUID().toString();
                added.put("id", id);
                if (!requiredAddFields(category, added) || found || !validAddedReferences(working, category, added)) { if (strict) throw new IllegalArgumentException("新增项字段不完整、ID 重复或引用不存在的配置"); else continue; }
                Map<String,Object> change = new LinkedHashMap<>(); change.put("op", operation); change.put("category", category); change.put("id", id); change.put("value", added);
                if (item.get("reason") != null) change.put("reason", String.valueOf(item.get("reason")).trim());
                latest.put(category + "\u0000" + id, change);
                applyOne(working, change);
                continue;
            }
            if (id.trim().isEmpty() || !found) { if (strict) throw new IllegalArgumentException("修改或删除必须对应当前配置中的现有结构 ID"); else continue; }
            if ("DELETE".equals(operation)) {
                if (isReferenced(working, category, id)) { if (strict) throw new IllegalArgumentException("该配置项仍被其他候选引用，请先解除引用后再删除"); else continue; }
                if (strict && item.get("before") instanceof Map && !Objects.equals(findAnalysisCandidate(working, category, id), item.get("before"))) throw new IllegalArgumentException("待删除项已被其他操作修改，请刷新草案后重新确认");
                Map<String,Object> change = new LinkedHashMap<>(); change.put("op", operation); change.put("category", category); change.put("id", id);
                Map<?,?> original = findAnalysisCandidate(base, category, id); if (original != null) change.put("before", copyMap(original));
                if (item.get("reason") != null) change.put("reason", String.valueOf(item.get("reason")).trim());
                latest.put(category + "\u0000" + id, change);
                applyOne(working, change);
                continue;
            }
            boolean validValue = value instanceof String || value instanceof Number || value instanceof Boolean || value instanceof List;
            if (!ANALYSIS_EDITABLE_FIELDS.get(category).contains(field) || !validValue) { if (strict) throw new IllegalArgumentException("修改字段不在白名单中或值格式无效"); else continue; }
            String text = String.valueOf(value).trim();
            if (text.isEmpty() || text.length() > 4000) { if (strict) throw new IllegalArgumentException("分析修正内容不能为空且不超过4000字"); else continue; }
            if ("aggregation".equals(field) && !Arrays.asList("COUNT", "SUM", "AVG", "MIN", "MAX").contains(text.toUpperCase(Locale.ROOT))) { if (strict) throw new IllegalArgumentException("指标聚合方式无效"); else continue; }
            if ("propertyId".equals(field) && !exists(working, "properties", text)) { if (strict) throw new IllegalArgumentException("查询语义必须引用当前配置中的属性"); else continue; }
            if (Arrays.asList("objectId", "sourceObjectId", "targetObjectId").contains(field) && !exists(working, "objects", text)) { if (strict) throw new IllegalArgumentException("修改必须引用当前配置中的对象"); else continue; }
            if ("synonyms".equals(field) && text.split("[,，;；\\n]").length > 20) { if (strict) throw new IllegalArgumentException("同义词最多20个"); else continue; }
            if ("risk".equals(field) && !Arrays.asList("LOW", "MEDIUM", "HIGH", "CRITICAL").contains(text.toUpperCase(Locale.ROOT))) { if (strict) throw new IllegalArgumentException("风险等级无效"); else continue; }
            if ("operation".equals(field) && !Arrays.asList("READ", "SELECT", "INSERT", "UPDATE", "DELETE").contains(text.toUpperCase(Locale.ROOT))) { if (strict) throw new IllegalArgumentException("操作类型无效"); else continue; }
            if ("subjectType".equals(field) && !Arrays.asList("ROLE", "USER", "GROUP", "SERVICE").contains(text.toUpperCase(Locale.ROOT))) { if (strict) throw new IllegalArgumentException("主体类型无效"); else continue; }
            if ("trigger".equals(field) && !Arrays.asList("MCP_REQUEST", "MANUAL", "SCHEDULE", "EVENT", "WEBHOOK").contains(text.toUpperCase(Locale.ROOT))) { if (strict) throw new IllegalArgumentException("触发方式无效"); else continue; }
            if ("cardinality".equals(field) && !Arrays.asList("ONE_TO_ONE", "ONE_TO_MANY", "MANY_TO_ONE", "MANY_TO_MANY").contains(text.toUpperCase(Locale.ROOT))) { if (strict) throw new IllegalArgumentException("关系基数无效"); else continue; }
            Object normalizedValue = value instanceof String ? text : value;
            if (Arrays.asList("risk", "operation", "subjectType", "trigger", "cardinality", "aggregation").contains(field)) normalizedValue = text.toUpperCase(Locale.ROOT);
            if ("synonyms".equals(field)) {
                List<String> synonyms = new ArrayList<>();
                List<?> values = value instanceof List ? (List<?>) value : Arrays.asList(text.split("[,，;；\\n]"));
                for (Object synonym : values) if (synonym instanceof String && !((String) synonym).trim().isEmpty()) synonyms.add(((String) synonym).trim());
                if (synonyms.isEmpty() || synonyms.size() > 20) { if (strict) throw new IllegalArgumentException("同义词需为1到20项"); else continue; }
                normalizedValue = synonyms;
            }
            if (Arrays.asList("nullable", "isPrimaryKey", "rowFilterRequired").contains(field)) {
                if (!("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text))) { if (strict) throw new IllegalArgumentException("布尔值无效"); else continue; }
                normalizedValue = Boolean.parseBoolean(text);
            }
            Map<String,Object> change = new LinkedHashMap<>();
            change.put("op", operation); change.put("category", category); change.put("id", id); change.put("field", field); change.put("value", normalizedValue);
            Map<?,?> original = findAnalysisCandidate(base, category, id); if (original != null) change.put("before", original.get(field));
            if (strict && item.containsKey("before") && original != null && !Objects.equals(original.get(field), item.get("before")) && !Objects.equals(original.get(field), normalizedValue)) throw new IllegalArgumentException("配置已被其他操作修改，请刷新草案后重新确认");
            if (item.get("reason") != null) change.put("reason", String.valueOf(item.get("reason")).trim());
            latest.put(category + "\u0000" + id + "\u0000" + field, change);
            applyOne(working, change);
        }
        List<Map<String,Object>> changes = new ArrayList<>();
        for (Map<String,Object> change : latest.values()) {
            if (!"UPDATE".equals(change.get("op"))) { changes.add(change); continue; }
            Map<?,?> candidate = findAnalysisCandidate(base, String.valueOf(change.get("category")), String.valueOf(change.get("id")));
            if (removeUnchanged && candidate != null && Objects.equals(candidate.get(change.get("field")), change.get("value"))) continue;
            changes.add(change);
        }
        return changes;
    }

    public List<Map<String,Object>> mergeAnalysisChangeMarkers(Map<String,Object> base, List<Map<String,Object>> existing, List<Map<String,Object>> updates) {
        Map<String,Map<String,Object>> merged = new LinkedHashMap<>();
        for (Map<String,Object> change : existing) merged.put(changeKey(change), change);
        for (Map<String,Object> change : updates) merged.put(changeKey(change), change);
        return new ArrayList<>(merged.values());
    }

    public List<Map<String,Object>> recordedChanges(Map<String,Object> config) {
        List<Map<String,Object>> result = new ArrayList<>();
        Object markers = config.get("analysisCorrections");
        if (markers instanceof List) for (Object raw : (List<?>) markers) {
            if (!(raw instanceof Map)) continue;
            Map<String,Object> marker = copyMap((Map<?,?>) raw);
            if (ANALYSIS_EDITABLE_FIELDS.containsKey(value(marker.get("category"))) && Arrays.asList("ADD", "UPDATE", "DELETE").contains(value(marker.get("op")))) result.add(marker);
        }
        return result;
    }

    private String changeKey(Map<String,Object> change) {
        return change.get("category") + "\u0000" + change.get("id") + "\u0000" + change.getOrDefault("field", change.get("op"));
    }

    /** 保存后的差异会随配置一同回传；识别已落地的相同操作，避免二次保存重复新增或误报删除失败。 */
    private boolean alreadyAppliedAnalysisChange(Map<String,Object> base, Map<?,?> requested, String category, String operation, String id, String field, Object value, boolean found) {
        Object rawMarkers = base.get("analysisCorrections");
        if (!(rawMarkers instanceof List)) return false;
        for (Object raw : (List<?>) rawMarkers) {
            if (!(raw instanceof Map)) continue;
            Map<?,?> marker = (Map<?,?>) raw;
            if (!Objects.equals(category, String.valueOf(marker.get("category"))) || !Objects.equals(id, String.valueOf(marker.get("id"))) || !Objects.equals(operation, String.valueOf(marker.get("op")))) continue;
            if ("ADD".equals(operation) && found) {
                Map<?,?> current = findAnalysisCandidate(base, category, id);
                if (current != null && Objects.equals(current, value)) return true;
            }
            if ("DELETE".equals(operation) && !found) return true;
            if ("UPDATE".equals(operation) && Objects.equals(field, String.valueOf(marker.get("field")))) {
                Map<?,?> current = findAnalysisCandidate(base, category, id);
                if (current != null && Objects.equals(current.get(field), value)) return true;
            }
        }
        return false;
    }

    private Map<?,?> findAnalysisCandidate(Map<String,Object> config, String category, String id) {
        for (Object candidate : candidateRows(config, category)) if (candidate instanceof Map && Objects.equals(id, String.valueOf(((Map<?,?>) candidate).get("id")))) return (Map<?,?>) candidate;
        return null;
    }

    public List<?> candidateRows(Map<String,Object> config, String category) {
        Object direct = config.get(category);
        if (direct instanceof List) return (List<?>) direct;
        for (String container : Arrays.asList("semanticCatalog", "querySemantics")) {
            Object nested = config.get(container);
            if (nested instanceof Map && ((Map<?,?>) nested).get(category) instanceof List) return (List<?>) ((Map<?,?>) nested).get(category);
        }
        return Collections.emptyList();
    }

    public Map<String,Object> applyAnalysisChanges(Map<String,Object> base, List<Map<String,Object>> changes) {
        Map<String,Object> config = readAnalysisConfig(writeJson(base));
        for (Map<String,Object> change : changes) applyOne(config, change);
        new ProposalProjectionSynchronizer().synchronize(config);
        return config;
    }

    private void applyOne(Map<String,Object> config, Map<String,Object> change) {
        String category = String.valueOf(change.get("category")), id = String.valueOf(change.get("id"));
        List<Map<String,Object>> candidates = mutableCandidateRows(config, category);
        if ("ADD".equals(change.get("op"))) candidates.add(copyMap((Map<?,?>) change.get("value")));
        else if ("DELETE".equals(change.get("op"))) candidates.removeIf(candidate -> Objects.equals(id, String.valueOf(candidate.get("id"))));
        else for (Map<String,Object> candidate : candidates) if (Objects.equals(id, String.valueOf(candidate.get("id")))) {
            candidate.put(String.valueOf(change.get("field")), change.get("value"));
            if ("name".equals(change.get("field")) && candidate.containsKey("namingStatus")) candidate.put("namingStatus", "CORRECTED");
            break;
        }
    }

    public Map<String,Object> preserveCorrections(Map<String,Object> generated, Map<String,Object> previous) {
        Map<String,Object> current = readAnalysisConfig(writeJson(generated));
        List<Map<String,Object>> accepted = new ArrayList<>();
        List<Map<String,Object>> conflicts = new ArrayList<>();
        Object markers = previous.get("analysisCorrections");
        if (markers instanceof List) for (Object raw : (List<?>) markers) {
            if (!(raw instanceof Map)) continue;
            Map<String,Object> change = copyMap((Map<?,?>) raw);
            String category = value(change.get("category")), id = value(change.get("id")), field = value(change.get("field"));
            Map<?,?> candidate = findAnalysisCandidate(current, category, id);
            try {
                if ("DELETE".equals(change.get("op")) && candidate == null) { accepted.add(change); continue; }
                if ("UPDATE".equals(change.getOrDefault("op", "UPDATE")) && candidate != null && !Arrays.asList("name", "description", "definition", "synonyms").contains(field)
                        && !Objects.equals(candidate.get(field), change.get("before")) && !Objects.equals(candidate.get(field), change.get("value"))) throw new IllegalArgumentException("新结构与人工修正存在冲突");
                change.remove("before");
                List<Map<String,Object>> normalized = normalizeAnalysisChanges(current, Collections.singletonList(change), true);
                current = applyAnalysisChanges(current, normalized);
                accepted.addAll(normalized.isEmpty() ? Collections.singletonList(change) : normalized);
            } catch (IllegalArgumentException failure) {
                change.put("conflictReason", failure.getMessage());
                conflicts.add(change);
            }
        }
        current.put("analysisCorrections", accepted);
        current.put("analysisCorrectionConflicts", conflicts);
        new ProposalProjectionSynchronizer().synchronize(current);
        return current;
    }

    private Map<String,Object> copyMap(Map<?,?> source) { Map<String,Object> result = new LinkedHashMap<>(); source.forEach((key, value) -> result.put(String.valueOf(key), value)); return result; }

    private List<Map<String,Object>> mutableCandidateRows(Map<String,Object> config, String category) {
        Object direct = config.get(category);
        if (direct instanceof List) return (List<Map<String,Object>>) direct;
        for (String container : Arrays.asList("semanticCatalog", "querySemantics")) {
            Object nested = config.get(container);
            if (nested instanceof Map) {
                Object rows = ((Map<?,?>) nested).get(category);
                if (rows instanceof List) return (List<Map<String,Object>>) rows;
            }
        }
        List<Map<String,Object>> rows = new ArrayList<>(); config.put(category, rows); return rows;
    }

    private boolean requiredAddFields(String category, Map<String,Object> row) {
        List<String> required = new ArrayList<>(Collections.singletonList("name"));
        if ("properties".equals(category)) required.addAll(Arrays.asList("objectId", "column", "dataType"));
        if ("relations".equals(category)) required.addAll(Arrays.asList("sourceObjectId", "targetObjectId", "sourceColumn", "targetColumn"));
        if ("metrics".equals(category)) required.addAll(Arrays.asList("propertyId", "aggregation"));
        if (Arrays.asList("dimensions", "timeDimensions").contains(category)) required.add("propertyId");
        if ("metrics".equals(category) && !Arrays.asList("COUNT", "SUM", "AVG", "MIN", "MAX").contains(value(row.get("aggregation")).toUpperCase(Locale.ROOT))) return false;
        if ("objects".equals(category) && value(row.get("table")).isEmpty() && value(row.get("technicalName")).isEmpty()) return false;
        for (String field : required) if (value(row.get(field)).trim().isEmpty()) return false;
        for (String field : row.keySet()) if (!"id".equals(field) && !ANALYSIS_EDITABLE_FIELDS.get(category).contains(field)
                && !Arrays.asList("table", "column", "technicalName", "objectId", "sourceObjectId", "targetObjectId", "sourceColumn", "targetColumn", "conditionText", "steps", "definition", "aggregation", "propertyId", "synonyms", "granularities", "default", "resourceId", "suggestedSubjects").contains(field)) return false;
        return true;
    }

    private boolean validAddedReferences(Map<String,Object> config, String category, Map<String,Object> row) {
        if ("properties".equals(category) && !exists(config, "objects", row.get("objectId"))) return false;
        if ("relations".equals(category) && (!exists(config, "objects", row.get("sourceObjectId")) || !exists(config, "objects", row.get("targetObjectId")))) return false;
        if ("actions".equals(category) && row.get("objectId") != null && !exists(config, "objects", row.get("objectId"))) return false;
        if (("metrics".equals(category) || "dimensions".equals(category) || "timeDimensions".equals(category)) && !exists(config, "properties", row.get("propertyId"))) return false;
        return true;
    }

    private boolean exists(Map<String,Object> config, String category, Object id) {
        if (id == null) return false;
        return candidateRows(config, category).stream().anyMatch(row -> row instanceof Map && Objects.equals(String.valueOf(id), String.valueOf(((Map<?,?>) row).get("id"))));
    }

    private boolean isReferenced(Map<String,Object> config, String category, String id) {
        if ("objects".equals(category)) {
            for (Object row : candidateRows(config, "properties")) if (row instanceof Map && id.equals(String.valueOf(((Map<?,?>) row).get("objectId")))) return true;
            for (Object row : candidateRows(config, "relations")) if (row instanceof Map && (id.equals(String.valueOf(((Map<?,?>) row).get("sourceObjectId"))) || id.equals(String.valueOf(((Map<?,?>) row).get("targetObjectId"))))) return true;
        }
        if ("properties".equals(category)) for (String related : Arrays.asList("metrics", "dimensions", "timeDimensions")) for (Object row : candidateRows(config, related))
            if (references(row, "propertyId", id)) return true;
        if ("metrics".equals(category)) for (Object row : candidateRows(config, "metrics")) if (references(row, "metricId", id)) return true;
        if ("objects".equals(category)) for (String related : Arrays.asList("actions", "logics", "permissionCandidates", "derivedProperties", "validationRules")) {
            for (Object row : candidateRows(config, related)) if (references(row, "objectId", id) || references(row, "resourceId", id)) return true;
        }
        if ("actions".equals(category)) for (Object row : candidateRows(config, "serviceFlows")) {
            if (row instanceof Map && ((Map<?,?>) row).get("steps") instanceof List && ((List<?>) ((Map<?,?>) row).get("steps")).contains(id)) return true;
        }
        return false;
    }

    private boolean references(Object value, String key, String id) {
        if (value instanceof Map) {
            Map<?,?> row = (Map<?,?>) value;
            if (id.equals(row.get(key))) return true;
            for (Object child : row.values()) if (references(child, key, id)) return true;
        } else if (value instanceof List) for (Object child : (List<?>) value) if (references(child, key, id)) return true;
        return false;
    }

    private String value(Object value) { return value == null ? "" : String.valueOf(value); }
}

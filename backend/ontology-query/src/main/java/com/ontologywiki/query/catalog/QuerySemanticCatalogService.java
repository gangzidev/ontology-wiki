package com.ontologywiki.query.catalog;

import com.ontologywiki.ontology.service.OntologyFieldSemantics;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 确定性语义目录：只用名称、同义词和显式映射召回语义项，不依赖向量或 Embedding。
 */
@Service
public class QuerySemanticCatalogService {
    public Map<String, Object> describe(Map<String, Object> config) {
        Map<String, Object> semantics = SemanticCatalog.from(config);
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Map<String, Object>> properties = new LinkedHashMap<>();
        for (Map<String, Object> property : rows(config.get("properties"))) properties.put(text(property.get("id")), property);
        for (String category : Arrays.asList("metrics", "dimensions", "timeDimensions")) {
            List<Map<String, Object>> entries = new ArrayList<>();
            for (Map<String, Object> definition : rows(semantics.get(category))) {
                if ("metrics".equals(category) && !isBusinessMetric(definition, config)) continue;
                Map<String, Object> entry = new LinkedHashMap<>();
                for (String key : Arrays.asList("id", "propertyId", "aggregation", "filter", "filters", "definition", "synonyms", "evidence")) entry.put(key, definition.get(key));
                entry.put("name", definition.get("name") == null ? definition.get("label") : definition.get("name"));
                Map<String, Object> property = properties.get(text(definition.get("propertyId")));
                if (property != null) entry.put("objectId", property.get("objectId"));
                entries.add(entry);
            }
            result.put(category, entries);
        }
        Map<String, List<Map<String, Object>>> fieldsByObject = new LinkedHashMap<>();
        Map<String, Map<String, Object>> objectsById = new LinkedHashMap<>();
        for (Map<String, Object> property : properties.values()) {
            if (text(property.get("column")).isEmpty()) continue;
            Map<String, Object> field = new LinkedHashMap<>();
            for (String key : Arrays.asList("id", "name", "column", "dataType", "description")) if (property.containsKey(key)) field.put(key, property.get(key));
            fieldsByObject.computeIfAbsent(text(property.get("objectId")), key -> new ArrayList<>()).add(field);
        }
        List<Map<String, Object>> objects = new ArrayList<>();
        for (Map<String, Object> object : rows(config.get("objects"))) {
            if (text(object.get("table")).isEmpty()) continue;
            objectsById.put(text(object.get("id")), object);
            Map<String, Object> entry = new LinkedHashMap<>();
            for (String key : Arrays.asList("id", "name", "table", "schema", "synonyms")) if (object.containsKey(key)) entry.put(key, object.get(key));
            List<Map<String, Object>> fields = fieldsByObject.getOrDefault(text(object.get("id")), Collections.emptyList());
            if (fields.isEmpty()) continue;
            entry.put("fields", fields);
            entry.put("fieldCount", fields.size());
            objects.add(entry);
        }
        result.put("objects", objects);
        List<Map<String, Object>> relations = rows(semantics.get("relations"));
        if (relations.isEmpty()) relations = rows(config.get("relations"));
        List<Map<String, Object>> relationEntries = new ArrayList<>();
        for (Map<String, Object> relation : relations) {
            if ("m2mReification".equals(String.valueOf(relation.get("inferenceMethod")))) continue;
            Map<String, Object> entry = new LinkedHashMap<>();
            for (String key : Arrays.asList("id", "name", "sourceObjectId", "targetObjectId", "sourceColumn", "targetColumn",
                    "sourceKeyColumn", "targetKeyColumn", "viaObjectId", "viaTable", "viaSourceColumn", "viaTargetColumn",
                    "cardinality", "inferenceMethod", "confidence", "evidence", "reviewRequired")) {
                if (relation.containsKey(key)) entry.put(key, relation.get(key));
            }
            addObjectLabel(entry, "source", objectsById.get(text(relation.get("sourceObjectId"))));
            addObjectLabel(entry, "target", objectsById.get(text(relation.get("targetObjectId"))));
            addObjectLabel(entry, "via", objectsById.get(text(relation.get("viaObjectId"))));
            boolean reviewRequired = Boolean.TRUE.equals(relation.get("reviewRequired"))
                    || "NEEDS_REVIEW".equalsIgnoreCase(text(relation.get("status")));
            String status = text(relation.get("status")).toUpperCase(Locale.ROOT);
            boolean explicitlyUnavailable = Arrays.asList("REJECTED", "DISABLED", "UNEXECUTABLE").contains(status);
            boolean knownApproved = Arrays.asList("CONFIRMED", "APPROVED", "ACTIVE", "EXECUTABLE", "VERIFIED").contains(status);
            boolean unknownStatus = !status.isEmpty() && !reviewRequired && !explicitlyUnavailable && !knownApproved;
            boolean awaitingReview = reviewRequired || (!status.isEmpty() && !knownApproved && !explicitlyUnavailable);
            boolean hasIntermediate = !text(relation.get("viaObjectId")).isEmpty()
                    || !text(relation.get("viaTable")).isEmpty();
            boolean mappingComplete = hasIntermediate
                    ? !text(relation.get("sourceKeyColumn")).isEmpty()
                        && !text(relation.get("targetKeyColumn")).isEmpty()
                        && !text(relation.get("viaSourceColumn")).isEmpty()
                        && !text(relation.get("viaTargetColumn")).isEmpty()
                    : !text(relation.get("sourceColumn")).isEmpty()
                        && !text(relation.get("targetColumn")).isEmpty();
            // 只有审核通过且连接映射完整的关系，才向查询侧标记为可执行。
            entry.put("executionStatus", awaitingReview ? "PENDING_REVIEW"
                    : explicitlyUnavailable || unknownStatus || !mappingComplete ? "UNEXECUTABLE" : "EXECUTABLE");
            if (!mappingComplete) entry.put("unavailableReason", "连接字段映射不完整");
            else if (explicitlyUnavailable) entry.put("unavailableReason", "关系已禁用或拒绝");
            if (relation.get("description") != null) entry.put("description", relation.get("description"));
            relationEntries.add(entry);
        }
        result.put("relations", relationEntries);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("tableCount", objects.size());
        summary.put("fieldCount", objects.stream().mapToInt(object -> ((Number) object.get("fieldCount")).intValue()).sum());
        summary.put("relationCount", relationEntries.size());
        result.put("structureSummary", summary);
        List<Map<String, Object>> profiles = rows(config.get("dataProfiles"));
        if (profiles.isEmpty()) profiles = rows(map(config.get("targetConfig")).get("dataProfiles"));
        result.put("dataProfiles", profiles);
        return result;
    }

    private void addObjectLabel(Map<String, Object> relation, String prefix, Map<String, Object> object) {
        if (object == null) return;
        relation.put(prefix + "Name", object.get("name"));
        relation.put(prefix + "Table", object.get("table"));
        relation.put(prefix + "Schema", object.get("schema"));
    }

    public Resolution resolve(Map<String, Object> config, String question) {
        String normalizedQuestion = normalize(question);
        if (normalizedQuestion.isEmpty()) return Resolution.clarification("请输入要查询的问题");
        Resolution projection = resolveCatalogField(config, question);
        if (projection != null) return projection;
        Map<String, Object> catalog = map(config == null ? null : config.get("semanticCatalog"));
        boolean hasCatalog = !catalog.isEmpty();
        Map<String, Object> semantics = SemanticCatalog.from(config);

        List<Map<String, Object>> metrics = rows(semantics.get("metrics"));
        List<Map<String, Object>> dimensions = rows(semantics.get("dimensions"));
        List<Map<String, Object>> terms = rows(semantics.get("terms"));
        List<Match> metricMatches = matches(metrics, terms, normalizedQuestion, "metric");
        Resolution detail = resolveDetail(config, question, !metricMatches.isEmpty());
        if (detail != null) return detail;
        List<Match> usableMetrics = new ArrayList<>(metricMatches);
        usableMetrics.removeIf(match -> !isBusinessMetric(find(metrics, match.id), config));
        if (!usableMetrics.isEmpty()) metricMatches = usableMetrics;
        List<Match> dimensionMatches = requestsGrouping(question) ? matches(dimensions, terms, normalizedQuestion, "dimension") : new ArrayList<>();
        metricMatches = preferMentionedObject(metricMatches, metrics, config, normalizedQuestion);
        dimensionMatches = preferMentionedObject(dimensionMatches, dimensions, config, normalizedQuestion);
        metricMatches = mostSpecific(metricMatches);
        dimensionMatches = mostSpecific(dimensionMatches);
        if (metricMatches.isEmpty()) return Resolution.unmatched("当前配置没有找到对应的统计口径，请说明要统计的业务对象或指标");
        if (distinctIds(metricMatches).size() > 1) return Resolution.clarification("要查询哪个业务对象的指标：" + names(metricMatches, metrics, config));
        if (distinctIds(dimensionMatches).size() > 1) return Resolution.clarification("要按哪个业务分类统计：" + names(dimensionMatches, dimensions, config));

        Map<String, Object> metric = find(metrics, metricMatches.get(0).id);
        if (!isBusinessMetric(metric, config)) return Resolution.clarification("这个字段表示分类或是否状态。请说明要统计哪类记录，例如“外链菜单有多少个”，而不是对状态编码求和。");
        Map<String, Object> dimension = dimensionMatches.isEmpty() ? null : find(dimensions, dimensionMatches.get(0).id);
        List<Map<String, Object>> defaults = new ArrayList<>();
        if (metric != null && metric.get("filter") instanceof Map) defaults.add(copy((Map<?, ?>) metric.get("filter")));
        if (metric != null && metric.get("filters") instanceof List) for (Object value : (List<?>) metric.get("filters")) if (value instanceof Map) defaults.add(copy((Map<?, ?>) value));
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("metric", metricMatches.get(0).alias);
        if (dimension != null) evidence.put("dimension", dimensionMatches.get(0).alias);
        evidence.put("source", hasCatalog ? "semanticCatalog" : "querySemantics");
        return new Resolution(Collections.singletonList(metricMatches.get(0).id),
                dimension == null ? Collections.<String>emptyList() : Collections.singletonList(dimensionMatches.get(0).id), defaults, evidence, null);
    }

    // Recognize complete catalogue requests before metric synonyms (a COUNT metric often aliases its ID column).
    private Resolution resolveCatalogField(Map<String, Object> config, String question) {
        if (config == null) return null;
        java.util.regex.Matcher request = FIELD_REQUEST.matcher(question.trim());
        if (!request.matches()) return null;
        String table = request.group(1).trim();
        String field = request.group(2).trim();
        List<String> objects = new ArrayList<>();
        for (Map<String,Object> object : rows(config.get("objects"))) {
            if (objectAliases(object).stream().anyMatch(table::equalsIgnoreCase)) objects.add(text(object.get("id")));
        }
        boolean identifierField = field.matches("(?iu)(?:[a-z_][a-z0-9_]*|[\\p{L}0-9_]*(?:ID|编号))");
        if (objects.isEmpty()) {
            if ((identifierField || table.contains(".")) && table.matches("(?i)[a-z_][a-z0-9_.]*"))
                return Resolution.clarification("当前草案的数据目录没有表 " + table + "，请确认所选草案和表名");
            return null;
        }
        if (objects.size() > 1) return Resolution.clarification("表名匹配多个对象，请指定 Schema 和表名");
        String objectId = objects.get(0);
        if (Arrays.asList("数据", "明细", "全部数据", "记录列表").contains(field)) return Resolution.detail(objectId);
        List<String> fields = new ArrayList<>();
        Set<String> columns = new LinkedHashSet<>();
        for (Map<String,Object> property : rows(config.get("properties"))) {
            if (!objectId.equals(text(property.get("objectId"))) || text(property.get("column")).isEmpty()) continue;
            if (field.equalsIgnoreCase(text(property.get("column")))) columns.add(text(property.get("id")));
            for (String key : Arrays.asList("name", "column", "label", "technicalName")) {
                if (field.equalsIgnoreCase(text(property.get(key)))) {
                    fields.add(text(property.get("id")));
                    break;
                }
            }
        }
        if (!columns.isEmpty()) fields = new ArrayList<>(columns);
        if (fields.size() > 1) return Resolution.clarification("多个字段使用了这个名称，请指定物理列名");
        if (fields.isEmpty()) {
            if (identifierField) return Resolution.clarification(table + " 的目录没有匹配字段“" + field + "”，请确认列名");
            return null;
        }
        Resolution result = Resolution.detail(objectId);
        result.fieldId = fields.get(0);
        result.evidence.put("fieldId", result.fieldId);
        return result;
    }

    private static final java.util.regex.Pattern FIELD_REQUEST = java.util.regex.Pattern.compile(
            "(?:请|我想)?(?:查看|查询|展示|列出|显示|看看)\\s*(.+?)的\\s*(.+?)[。？?]?");

    private Resolution resolveDetail(Map<String, Object> config, String question, boolean matchedMetric) {
        String normalized = normalize(question);
        List<Map<String, Object>> objects = rows(config == null ? null : config.get("objects"));
        List<String> names = new ArrayList<>();
        for (Map<String, Object> object : objects) names.addAll(objectAliases(object));
        names.sort((a, b) -> Integer.compare(b.length(), a.length()));
        String intent = normalized;
        for (String name : names) intent = intent.replace(normalize(name), "");
        // Statistical requests still use the reviewed metric catalogue, even when they mention a table.
        if (intent.matches("(?is).*(统计|多少|数量|总数|数据量|合计|求和|平均|同比|环比|分布|count|sum\\(|average).*")) return null;
        boolean explicitRows = intent.matches("(?is).*(数据|明细|列表|记录|信息|详情|\\brows\\b|\\brecords\\b).*");
        if (matchedMetric && !explicitRows && !intent.matches("(?:我想|想|请|查看|查询|展示|看看|看|一下|的|表|补充说明)*")) return null;
        if (!explicitRows && !normalized.matches("(?is).*(看|查询|展示|列出|显示|\\bshow\\b|\\blist\\b).*")) return null;
        List<Match> matches = new ArrayList<>();
        for (Map<String, Object> object : objects) {
            for (String alias : objectAliases(object)) {
                if (!alias.isEmpty() && normalized.contains(normalize(alias))) matches.add(new Match(text(object.get("id")), alias, "object"));
            }
        }
        // A short alias embedded inside a longer table name does not denote a second object.
        List<Match> selected = new ArrayList<>();
        for (Match match : matches) {
            boolean contained = false;
            for (Match other : matches) if (normalize(other.alias).length() > normalize(match.alias).length()
                    && normalize(other.alias).contains(normalize(match.alias))) { contained = true; break; }
            if (!contained) selected.add(match);
        }
        Set<String> ids = distinctIds(selected);
        Set<String> physical = new LinkedHashSet<>();
        for (Map<String, Object> object : objects) {
            String table = normalize(text(object.get("table")));
            if (!table.isEmpty() && normalized.contains(table)) physical.add(text(object.get("id")));
        }
        if (physical.size() == 1) ids = physical;
        if (ids.size() > 1) {
            List<String> choices = new ArrayList<>();
            for (String id : ids) { Map<String, Object> object = find(objects, id); choices.add(text(object.get("name")) + "（" + text(object.get("table")) + "）"); }
            return Resolution.clarification("匹配到多个数据对象，请指定要查看的表：" + String.join("、", choices));
        }
        if (ids.size() == 1) return Resolution.detail(ids.iterator().next());
        return explicitRows ? Resolution.clarification("请说明要查看哪张表的数据；可在左侧数据目录选择表名") : null;
    }

    public boolean isSimpleDetailQuestion(Map<String, Object> config, String question, String objectId) {
        Map<String, Object> object = find(rows(config.get("objects")), objectId);
        if (object == null) return false;
        String remaining = normalize(question);
        List<String> names = objectAliases(object);
        names.sort((a, b) -> Integer.compare(b.length(), a.length()));
        for (String name : names) remaining = remaining.replace(normalize(name), "");
        // Only filler words may be skipped. Filters, projections, time, sorting and limits go to the parser.
        return remaining.replaceAll("补充说明|我想|请帮我|帮我|请|想要|我要|查看|查询|展示|列出|显示|看看|看|一下|全部|所有|原始|数据|信息|明细|列表|记录|详情|的|表", "").isEmpty();
    }

    private List<String> objectAliases(Map<String, Object> object) {
        List<String> aliases = new ArrayList<>();
        for (String key : Arrays.asList("name", "table", "technicalName", "label")) {
            String value = text(object.get(key));
            if (!value.isEmpty()) {
                aliases.add(value);
                // 中文用户常把“用户信息表”简称为“用户表”，物理表名也常省略“表”。
                if (value.endsWith("信息表") && value.length() > 3) {
                    aliases.add(value.substring(0, value.length() - 3) + "表");
                    aliases.add(value.substring(0, value.length() - 3));
                }
                if (value.endsWith("表")) aliases.add(value.substring(0, value.length() - 1));
            }
        }
        if (!text(object.get("schema")).isEmpty() && !text(object.get("table")).isEmpty()) aliases.add(text(object.get("schema")) + "." + text(object.get("table")));
        Object synonyms = object.get("synonyms");
        if (synonyms instanceof List) for (Object value : (List<?>) synonyms) if (!text(value).isEmpty()) aliases.add(text(value));
        return aliases;
    }

    private List<Match> preferMentionedObject(List<Match> matches, List<Map<String, Object>> definitions, Map<String, Object> config, String question) {
        if (distinctIds(matches).size() < 2) return matches;
        List<Match> selected = new ArrayList<>();
        for (Match match : matches) {
            Map<String, Object> object = sourceObject(find(definitions, match.id), config);
            for (String field : Arrays.asList("name", "table")) {
                String name = normalize(text(object.get(field)));
                if (!name.isEmpty() && question.contains(name)) { selected.add(match); break; }
            }
        }
        return selected.isEmpty() ? matches : selected;
    }

    private Map<String, Object> sourceObject(Map<String, Object> definition, Map<String, Object> config) {
        if (definition == null) return Collections.emptyMap();
        Map<String, Object> property = find(rows(config.get("properties")), text(definition.get("propertyId")));
        Map<String, Object> object = property == null ? null : find(rows(config.get("objects")), text(property.get("objectId")));
        return object == null ? Collections.emptyMap() : object;
    }

    boolean requestsGrouping(String question) {
        return question != null && question.matches("(?is).*(按|各|每个|分别|分组|分布|排名|最高|最低|最多|最少|group\\s+by|\\bby\\b|\\bper\\b).*");
    }

    public Resolution select(Map<String, Object> config, String metricId, String dimensionId) {
        Map<String, Object> semantics = SemanticCatalog.from(config);
        Map<String, Object> metric = find(rows(semantics.get("metrics")), metricId);
        Map<String, Object> dimension = find(rows(semantics.get("dimensions")), dimensionId);
        if (metric == null || (!dimensionId.isEmpty() && dimension == null)) throw new IllegalArgumentException("查询引用了不存在的业务指标或维度");
        if (!isBusinessMetric(metric, config)) return Resolution.clarification("当前指标使用了状态编码，请在配置管理中确认记录计数口径后查询");
        List<Map<String, Object>> filters = new ArrayList<>();
        if (metric.get("filter") instanceof Map) filters.add(copy((Map<?, ?>) metric.get("filter")));
        filters.addAll(rows(metric.get("filters")));
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("metric", metric.get("name"));
        if (dimension != null) evidence.put("dimension", dimension.get("name"));
        evidence.put("source", "semanticCatalogRetrieval");
        return new Resolution(Collections.singletonList(metricId), dimension == null ? Collections.emptyList() : Collections.singletonList(dimensionId), filters, evidence, null);
    }

    public boolean isBusinessMetric(Map<String, Object> metric, Map<String, Object> config) {
        if (metric == null) return false;
        Map<String, Object> property = find(rows(config.get("properties")), text(metric.get("propertyId")));
        if (property == null || !OntologyFieldSemantics.isCategorical(text(property.get("column")), text(property.get("dataType")), text(property.get("name")) + text(property.get("description")))) return true;
        String aggregation = text(metric.get("aggregation"));
        if (Arrays.asList("SUM", "AVG").contains(aggregation)) return false;
        return !"COUNT".equals(aggregation) || !text(property.get("name")).contains("是否")
                || metric.get("filter") instanceof Map || !rows(metric.get("filters")).isEmpty();
    }

    private List<Match> matches(List<Map<String, Object>> definitions, List<Map<String, Object>> terms, String question, String category) {
        List<Match> result = new ArrayList<>();
        for (Map<String, Object> definition : definitions) {
            String id = text(definition.get("id"));
            for (String alias : aliases(definition)) if (!alias.isEmpty() && question.contains(normalize(alias))) result.add(new Match(id, alias, category));
        }
        for (Map<String, Object> term : terms) {
            List<String> termAliases = aliases(term);
            String alias = "";
            for (String candidate : termAliases) if (!candidate.isEmpty() && question.contains(normalize(candidate)) && normalize(candidate).length() > normalize(alias).length()) alias = candidate;
            if (alias.isEmpty()) continue;
            Map<String, Object> mapsTo = map(term.get("mapsTo"));
            String id = text(mapsTo.get(category + "Id"));
            if (!id.isEmpty()) result.add(new Match(id, alias, category));
        }
        result.sort((left, right) -> Integer.compare(normalize(right.alias).length(), normalize(left.alias).length()));
        return result;
    }

    private List<String> aliases(Map<String, Object> definition) {
        List<String> result = new ArrayList<>();
        for (String field : Arrays.asList("name", "label", "term")) if (!text(definition.get(field)).isEmpty()) result.add(text(definition.get(field)));
        Object synonyms = definition.get("synonyms");
        if (synonyms instanceof List) for (Object value : (List<?>) synonyms) if (!text(value).isEmpty()) result.add(text(value));
        return result;
    }

    private List<Match> mostSpecific(List<Match> matches) {
        int longest = 0;
        for (Match match : matches) longest = Math.max(longest, normalize(match.alias).length());
        List<Match> result = new ArrayList<>();
        for (Match match : matches) if (normalize(match.alias).length() == longest) result.add(match);
        return result;
    }

    private String names(List<Match> matches, List<Map<String, Object>> definitions, Map<String, Object> config) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (Match match : matches) {
            Map<String, Object> object = sourceObject(find(definitions, match.id), config);
            String objectName = text(object.get("name"));
            names.add(objectName.isEmpty() ? match.alias : objectName + "的" + match.alias);
        }
        return String.join("、", names);
    }
    private Set<String> distinctIds(List<Match> matches) { Set<String> ids = new LinkedHashSet<>(); for (Match match : matches) ids.add(match.id); return ids; }
    private Map<String, Object> find(List<Map<String, Object>> rows, String id) { for (Map<String, Object> row : rows) if (id.equals(text(row.get("id")))) return row; return null; }
    private Map<String, Object> map(Object value) { if (!(value instanceof Map)) return Collections.emptyMap(); Map<String, Object> result = new LinkedHashMap<>(); for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) result.put(String.valueOf(entry.getKey()), entry.getValue()); return result; }
    private Map<String, Object> copy(Map<?, ?> value) { return map(value); }
    private List<Map<String, Object>> rows(Object value) { List<Map<String, Object>> result = new ArrayList<>(); if (value instanceof List) for (Object row : (List<?>) value) if (row instanceof Map) result.add(map(row)); return result; }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    public static String normalize(String value) { return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "").replaceAll("[，。！？、；：,.!?;:'\\\"（）()【】\\[\\]{}]", ""); }

    public static class Resolution {
        private final List<String> metricIds;
        private final List<String> dimensionIds;
        private final List<Map<String, Object>> defaultFilters;
        private final Map<String, Object> evidence;
        private final String clarification;
        private final String detailObjectId;
        private String fieldId;
        private boolean unmatched;
        private Resolution(List<String> metricIds, List<String> dimensionIds, List<Map<String, Object>> defaultFilters, Map<String, Object> evidence, String clarification) { this(metricIds, dimensionIds, defaultFilters, evidence, clarification, ""); }
        private Resolution(List<String> metricIds, List<String> dimensionIds, List<Map<String, Object>> defaultFilters, Map<String, Object> evidence, String clarification, String detailObjectId) { this.metricIds = metricIds; this.dimensionIds = dimensionIds; this.defaultFilters = defaultFilters; this.evidence = evidence; this.clarification = clarification; this.detailObjectId = detailObjectId; }
        public static Resolution clarification(String value) { return new Resolution(Collections.<String>emptyList(), Collections.<String>emptyList(), Collections.<Map<String, Object>>emptyList(), Collections.<String, Object>emptyMap(), value); }
        public static Resolution detail(String objectId) { Map<String, Object> evidence = new LinkedHashMap<>(); evidence.put("objectId", objectId); evidence.put("source", "objectDetail"); return new Resolution(Collections.<String>emptyList(), Collections.<String>emptyList(), Collections.<Map<String, Object>>emptyList(), evidence, null, objectId); }
        static Resolution unmatched(String value) { Resolution result = clarification(value); result.unmatched = true; return result; }
        public boolean needsRetrieval() { return unmatched; }
        public boolean needsClarification() { return clarification != null; }
        public boolean isDetailQuery() { return !detailObjectId.isEmpty(); }
        public String getDetailObjectId() { return detailObjectId; }
        public String getFieldId() { return fieldId; }
        public String getClarification() { return clarification; }
        public List<String> getMetricIds() { return metricIds; }
        public List<String> getDimensionIds() { return dimensionIds; }
        public List<Map<String, Object>> getDefaultFilters() { return defaultFilters; }
        public Map<String, Object> getEvidence() { return evidence; }
    }

    private static class Match { final String id; final String alias; final String category; Match(String id, String alias, String category) { this.id = id; this.alias = alias; this.category = category; } }
}

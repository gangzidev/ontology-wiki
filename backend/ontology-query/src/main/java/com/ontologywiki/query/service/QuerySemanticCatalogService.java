package com.ontologywiki.query.service;

import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 确定性语义目录：只用名称、同义词和显式映射召回语义项，不依赖向量或 Embedding。
 */
@Service
public class QuerySemanticCatalogService {
    public Resolution resolve(Map<String, Object> config, String question) {
        String normalizedQuestion = normalize(question);
        if (normalizedQuestion.isEmpty()) return Resolution.clarification("请输入要查询的问题");
        Map<String, Object> catalog = map(config == null ? null : config.get("semanticCatalog"));
        Map<String, Object> legacy = map(config == null ? null : config.get("querySemantics"));
        boolean hasCatalog = !catalog.isEmpty();
        Map<String, Object> semantics = merged(catalog, legacy);

        List<Map<String, Object>> metrics = rows(semantics.get("metrics"));
        List<Map<String, Object>> dimensions = rows(semantics.get("dimensions"));
        List<Map<String, Object>> terms = rows(semantics.get("terms"));
        List<Match> metricMatches = matches(metrics, terms, normalizedQuestion, "metric");
        List<Match> dimensionMatches = matches(dimensions, terms, normalizedQuestion, "dimension");
        metricMatches = mostSpecific(metricMatches);
        dimensionMatches = mostSpecific(dimensionMatches);
        if (metricMatches.isEmpty()) return Resolution.clarification("无法从已审核语义目录唯一确定指标，请说明要查询的指标");
        if (distinctIds(metricMatches).size() > 1) return Resolution.clarification("检测到多个指标候选，请明确指标名称：" + names(metricMatches));
        if (distinctIds(dimensionMatches).size() > 1) return Resolution.clarification("检测到多个维度候选，请明确分组维度：" + names(dimensionMatches));

        Map<String, Object> metric = find(metrics, metricMatches.get(0).id);
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

    private String names(List<Match> matches) { LinkedHashSet<String> names = new LinkedHashSet<>(); for (Match match : matches) names.add(match.alias); return String.join("、", names); }
    private Set<String> distinctIds(List<Match> matches) { Set<String> ids = new LinkedHashSet<>(); for (Match match : matches) ids.add(match.id); return ids; }
    private Map<String, Object> find(List<Map<String, Object>> rows, String id) { for (Map<String, Object> row : rows) if (id.equals(text(row.get("id")))) return row; return null; }
    private Map<String, Object> map(Object value) { if (!(value instanceof Map)) return Collections.emptyMap(); Map<String, Object> result = new LinkedHashMap<>(); for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) result.put(String.valueOf(entry.getKey()), entry.getValue()); return result; }
    private Map<String, Object> copy(Map<?, ?> value) { return map(value); }
    private Map<String, Object> merged(Map<String, Object> primary, Map<String, Object> fallback) {
        Map<String, Object> result = new LinkedHashMap<>(fallback);
        result.putAll(primary);
        for (String key : Arrays.asList("metrics", "dimensions", "terms", "relations")) {
            if (!result.containsKey(key) && fallback.containsKey(key)) result.put(key, fallback.get(key));
        }
        return result;
    }
    private List<Map<String, Object>> rows(Object value) { List<Map<String, Object>> result = new ArrayList<>(); if (value instanceof List) for (Object row : (List<?>) value) if (row instanceof Map) result.add(map(row)); return result; }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    static String normalize(String value) { return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "").replaceAll("[，。！？、；：,.!?;:'\\\"（）()【】\\[\\]{}]", ""); }

    public static class Resolution {
        private final List<String> metricIds;
        private final List<String> dimensionIds;
        private final List<Map<String, Object>> defaultFilters;
        private final Map<String, Object> evidence;
        private final String clarification;
        private Resolution(List<String> metricIds, List<String> dimensionIds, List<Map<String, Object>> defaultFilters, Map<String, Object> evidence, String clarification) { this.metricIds = metricIds; this.dimensionIds = dimensionIds; this.defaultFilters = defaultFilters; this.evidence = evidence; this.clarification = clarification; }
        static Resolution clarification(String value) { return new Resolution(Collections.<String>emptyList(), Collections.<String>emptyList(), Collections.<Map<String, Object>>emptyList(), Collections.<String, Object>emptyMap(), value); }
        public boolean needsClarification() { return clarification != null; }
        public String getClarification() { return clarification; }
        public List<String> getMetricIds() { return metricIds; }
        public List<String> getDimensionIds() { return dimensionIds; }
        public List<Map<String, Object>> getDefaultFilters() { return defaultFilters; }
        public Map<String, Object> getEvidence() { return evidence; }
    }

    private static class Match { final String id; final String alias; final String category; Match(String id, String alias, String category) { this.id = id; this.alias = alias; this.category = category; } }
}

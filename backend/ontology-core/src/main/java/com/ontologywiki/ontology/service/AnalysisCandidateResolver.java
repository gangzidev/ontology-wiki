package com.ontologywiki.ontology.service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 在当前分析草案内确定字段候选，避免让模型从完整字段清单猜测 ID。 */
final class AnalysisCandidateResolver {
    private static final Pattern LATIN = Pattern.compile("[a-z0-9_$]+");
    private static final Pattern HAN_RUN = Pattern.compile("[\\u4e00-\\u9fff]+");
    private static final Set<String> STOP_WORDS = new HashSet<>(Arrays.asList("什么", "怎么", "如何", "可以", "是否", "请问", "帮我", "字段", "类型", "当前", "这个", "那个", "应该", "修改", "改成", "改为"));
    Result resolve(Map<String, Object> config, String question, Map<String, Object> savedFocus) {
        List<Map<String, Object>> objects = maps(config.get("objects"));
        List<ScoredCandidate> scored = new ArrayList<>();
        for (Map<String, Object> property : maps(config.get("properties"))) {
            Map<String, Object> owner = ownerOf(property, objects);
            int score = score(property, owner, question);
            if (score > 0 && !value(property.get("id")).isEmpty()) scored.add(new ScoredCandidate(candidate(property, owner), score));
        }
        scored.sort(Comparator.comparingInt((ScoredCandidate item) -> item.score).reversed());
        if (!scored.isEmpty()) {
            int best = scored.get(0).score;
            List<Candidate> matches = new ArrayList<>();
            for (ScoredCandidate item : scored) {
                if (item.score != best || matches.size() == 3) break;
                matches.add(item.candidate);
            }
            return new Result(matches.size() == 1 ? matches.get(0) : null, matches, matches.size() > 1, false);
        }

        List<ScoredCandidate> fuzzy = fuzzyMatches(config, objects, question);
        if (!fuzzy.isEmpty()) {
            int best = fuzzy.get(0).score;
            List<Candidate> suggestions = new ArrayList<>();
            for (ScoredCandidate item : fuzzy) {
                if (best - item.score > 10 || suggestions.size() == 3) break;
                suggestions.add(item.candidate);
            }
            // 关键词召回只用于给用户展示缩小后的范围；用户确认前不自动选定字段。
            return new Result(null, suggestions, true, false);
        }

        Candidate focus = findFocus(config, objects, savedFocus);
        if (focus != null && isFollowUp(question)) return new Result(focus, Collections.singletonList(focus), false, true);
        return new Result(null, Collections.<Candidate>emptyList(), false, false);
    }

    private int score(Map<String, Object> property, Map<String, Object> owner, String question) {
        if (question == null || question.trim().isEmpty()) return 0;
        String table = value(owner.get("table"));
        String column = first(value(property.get("column")), value(property.get("technicalName")));
        String qualifiedName = table.isEmpty() || column.isEmpty() ? "" : table + "." + column;
        if (!qualifiedName.isEmpty() && identifierMention(question, qualifiedName)) return 1000;

        boolean columnMentioned = identifierMention(question, column) || identifierMention(question, value(property.get("technicalName")));
        boolean nameMentioned = phraseMention(question, value(property.get("name")));
        if (!columnMentioned && !nameMentioned) return 0;

        int score = columnMentioned ? 200 : 150;
        if (phraseMention(question, table)) score += 80;
        if (phraseMention(question, value(owner.get("name")))) score += 60;
        return score;
    }

    private List<ScoredCandidate> fuzzyMatches(Map<String, Object> config, List<Map<String, Object>> objects, String question) {
        Set<String> queryTerms = terms(question);
        if (queryTerms.size() < 2) return Collections.emptyList();
        List<ScoredCandidate> matches = new ArrayList<>();
        for (Map<String, Object> property : maps(config.get("properties"))) {
            if (value(property.get("id")).isEmpty()) continue;
            Map<String, Object> owner = ownerOf(property, objects);
            Set<String> fieldTerms = terms(value(property.get("name")) + " " + value(property.get("column")) + " " + value(property.get("technicalName")));
            int fieldOverlap = overlap(queryTerms, fieldTerms);
            if (fieldOverlap < 2) continue;
            int score = fieldOverlap * 20;
            score += Math.min(4, overlap(queryTerms, terms(value(owner.get("name")) + " " + value(owner.get("table"))))) * 3;
            score += Math.min(4, overlap(queryTerms, terms(value(property.get("description")))));
            matches.add(new ScoredCandidate(candidate(property, owner), score));
        }
        matches.sort(Comparator.comparingInt((ScoredCandidate item) -> item.score).reversed());
        return matches;
    }

    private Set<String> terms(String value) {
        Set<String> result = new LinkedHashSet<>();
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
        Matcher latin = LATIN.matcher(normalized);
        while (latin.find()) {
            String token = latin.group();
            result.add(token);
            for (String part : token.split("[_$]+")) if (part.length() > 1) result.add(part);
        }
        Matcher han = HAN_RUN.matcher(normalized);
        while (han.find()) {
            String run = han.group();
            if (run.length() <= 12 && !STOP_WORDS.contains(run)) result.add(run);
            for (int i = 0; i + 1 < run.length(); i++) {
                String bigram = run.substring(i, i + 2);
                if (!STOP_WORDS.contains(bigram)) result.add(bigram);
            }
        }
        return result;
    }

    private int overlap(Set<String> left, Set<String> right) {
        int count = 0;
        for (String term : left) if (right.contains(term)) count++;
        return count;
    }

    private Candidate findFocus(Map<String, Object> config, List<Map<String, Object>> objects, Map<String, Object> savedFocus) {
        if (savedFocus == null || !"properties".equals(value(savedFocus.get("category")))) return null;
        String id = value(savedFocus.get("id"));
        if (id.isEmpty()) return null;
        for (Map<String, Object> property : maps(config.get("properties"))) {
            if (!id.equals(value(property.get("id")))) continue;
            Candidate current = candidate(property, ownerOf(property, objects));
            String savedName = value(savedFocus.get("qualifiedName"));
            return savedName.isEmpty() || savedName.equalsIgnoreCase(current.qualifiedName()) ? current : null;
        }
        return null;
    }

    private boolean isFollowUp(String question) {
        String text = normalize(question);
        return text.contains("它") || text.contains("这个字段") || text.contains("该字段") || text.contains("这个")
                || text.contains("改成") || text.contains("改为") || text.contains("改成") || text.contains("设置为")
                || text.contains("设为") || text.contains("调整为") || text.contains("变为") || text.contains("类型");
    }

    private Candidate candidate(Map<String, Object> property, Map<String, Object> owner) {
        String column = first(value(property.get("column")), value(property.get("technicalName")));
        String table = first(value(owner.get("table")), value(owner.get("technicalName")));
        return new Candidate(value(property.get("id")), value(property.get("name")), column, table,
                value(owner.get("name")), value(property.get("dataType")), property);
    }

    private Map<String, Object> ownerOf(Map<String, Object> property, List<Map<String, Object>> objects) {
        String objectId = value(property.get("objectId"));
        for (Map<String, Object> object : objects) if (!objectId.isEmpty() && objectId.equals(value(object.get("id")))) return object;
        return Collections.emptyMap();
    }

    private boolean identifierMention(String text, String identifier) {
        if (identifier == null || identifier.trim().isEmpty()) return false;
        String source = text.toLowerCase(Locale.ROOT);
        String target = identifier.trim().toLowerCase(Locale.ROOT);
        int index = source.indexOf(target);
        while (index >= 0) {
            int end = index + target.length();
            if ((index == 0 || !identifierChar(source.charAt(index - 1)))
                    && (end == source.length() || !identifierChar(source.charAt(end)))) return true;
            index = source.indexOf(target, index + 1);
        }
        return false;
    }

    private boolean identifierChar(char value) {
        return (value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z')
                || (value >= '0' && value <= '9') || value == '_' || value == '$';
    }

    private boolean phraseMention(String text, String phrase) {
        if (phrase == null || phrase.trim().isEmpty() || text == null) return false;
        return normalize(text).contains(normalize(phrase));
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[`\\\"'‘’“”\\s]", "");
    }

    private List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List)) return Collections.emptyList();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object item : (List<?>) value) if (item instanceof Map) rows.add((Map<String, Object>) item);
        return rows;
    }

    private String value(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private String first(String first, String second) { return first.isEmpty() ? second : first; }

    static final class Result {
        final Candidate unique;
        final List<Candidate> matches;
        final boolean ambiguous;
        final boolean usedFocus;

        Result(Candidate unique, List<Candidate> matches, boolean ambiguous, boolean usedFocus) {
            this.unique = unique; this.matches = matches; this.ambiguous = ambiguous; this.usedFocus = usedFocus;
        }
    }

    static final class Candidate {
        final String id, name, column, table, objectName, dataType;
        final Map<String, Object> source;

        Candidate(String id, String name, String column, String table, String objectName, String dataType, Map<String, Object> source) {
            this.id = id; this.name = name; this.column = column; this.table = table;
            this.objectName = objectName; this.dataType = dataType; this.source = source;
        }

        String qualifiedName() { return table.isEmpty() ? column : table + "." + column; }
    }

    private static final class ScoredCandidate {
        final Candidate candidate;
        final int score;
        ScoredCandidate(Candidate candidate, int score) { this.candidate = candidate; this.score = score; }
    }
}

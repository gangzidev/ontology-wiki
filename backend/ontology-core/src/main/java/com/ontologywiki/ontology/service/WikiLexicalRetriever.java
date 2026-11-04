package com.ontologywiki.ontology.service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 使用已有资产元数据做轻量关键词召回，不引入向量或外部检索服务。 */
final class WikiLexicalRetriever {
    private static final Pattern LATIN = Pattern.compile("[a-z0-9_]{2,}");
    private static final Pattern HAN_RUN = Pattern.compile("[\\u4e00-\\u9fff]+");
    private static final Set<String> STOP_WORDS = new HashSet<>(Arrays.asList(
            "什么", "怎么", "如何", "可以", "是否", "请问", "帮我", "说明", "介绍", "当前", "这个", "那个", "字段", "用户", "业务", "资料", "信息"));

    List<Map<String, Object>> rank(List<Map<String, Object>> rows, String query, int limit) {
        List<ScoredRow> scored = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            int score = metadataScore(row, query);
            if (score > 0) scored.add(new ScoredRow(row, score));
        }
        scored.sort(Comparator.comparingInt((ScoredRow item) -> item.score).reversed());
        List<Map<String, Object>> result = new ArrayList<>();
        for (ScoredRow item : scored) {
            if (result.size() >= limit) break;
            result.add(item.row);
        }
        return result;
    }

    String evidenceSnippet(String content, String query, int limit) {
        if (content == null || content.trim().isEmpty() || limit <= 0) return "";
        List<String> blocks = Arrays.asList(content.split("\\r?\\n\\s*\\r?\\n"));
        List<ScoredBlock> scored = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            String block = blocks.get(i).trim();
            if (!block.isEmpty()) scored.add(new ScoredBlock(i, block, textScore(block, query)));
        }
        scored.sort(Comparator.comparingInt((ScoredBlock item) -> item.score).reversed().thenComparingInt(item -> item.index));
        StringBuilder result = new StringBuilder();
        int kept = 0;
        for (ScoredBlock block : scored) {
            if (kept >= 3 || block.score == 0) break;
            String next = block.text;
            int remaining = limit - result.length();
            if (remaining <= 0) break;
            if (next.length() > remaining) next = next.substring(0, remaining);
            if (result.length() > 0) result.append("\n\n");
            result.append(next);
            kept++;
        }
        if (result.length() == 0) return content.substring(0, Math.min(content.length(), limit));
        return result.toString();
    }

    private int metadataScore(Map<String, Object> row, String query) {
        String title = value(row.get("asset_name"));
        String domain = value(row.get("domain_name"));
        String description = value(row.get("description"));
        int score = textScore(title, query) * 4 + textScore(domain, query) * 2 + textScore(description, query);
        String normalizedQuery = normalize(query);
        if (normalizedQuery.length() >= 2 && normalize(title).contains(normalizedQuery)) score += 12;
        return score;
    }

    private int textScore(String text, String query) {
        if (text == null || query == null) return 0;
        String normalized = normalize(text);
        int score = 0;
        for (String term : terms(query)) {
            if (normalized.contains(term)) score += term.length() > 3 ? 3 : 1;
        }
        return score;
    }

    private Set<String> terms(String query) {
        Set<String> terms = new LinkedHashSet<>();
        String normalized = normalize(query);
        Matcher latin = LATIN.matcher(normalized);
        while (latin.find()) terms.add(latin.group());
        Matcher han = HAN_RUN.matcher(normalized);
        while (han.find()) {
            String run = han.group();
            if (run.length() <= 12 && !STOP_WORDS.contains(run)) terms.add(run);
            for (int i = 0; i + 1 < run.length(); i++) {
                String bigram = run.substring(i, i + 2);
                if (!STOP_WORDS.contains(bigram)) terms.add(bigram);
            }
        }
        return terms;
    }

    private String normalize(String value) { return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", ""); }
    private String value(Object value) { return value == null ? "" : String.valueOf(value); }

    private static final class ScoredRow {
        final Map<String, Object> row; final int score;
        ScoredRow(Map<String, Object> row, int score) { this.row = row; this.score = score; }
    }
    private static final class ScoredBlock {
        final int index; final String text; final int score;
        ScoredBlock(int index, String text, int score) { this.index = index; this.text = text; this.score = score; }
    }
}

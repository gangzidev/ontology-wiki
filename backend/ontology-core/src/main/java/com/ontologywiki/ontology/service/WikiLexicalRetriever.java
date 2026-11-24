package com.ontologywiki.ontology.service;

import java.util.*;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 使用已有资产元数据做轻量关键词召回，不引入向量或外部检索服务。 */
public final class WikiLexicalRetriever {
    private static final Pattern LATIN = Pattern.compile("[a-z0-9_]{2,}");
    private static final Pattern HAN_RUN = Pattern.compile("[\\u4e00-\\u9fff]+");
    private static final Set<String> STOP_WORDS = new HashSet<>(Arrays.asList(
            "什么", "怎么", "如何", "可以", "是否", "请问", "帮我", "说明", "介绍", "当前", "这个", "那个", "字段", "业务", "资料", "信息"));

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

    // Bound SQL expression size and each source's share of the prompt.
    private static final int MAX_TERMS = 16;
    private static final int CHUNK_LENGTH = 1000;
    private static final int CHUNK_OVERLAP = 80;
    private static final Pattern PARAGRAPH_BREAK = Pattern.compile("\\r?\\n[ \\t]*\\r?\\n");

    public List<String> searchTerms(String query) {
        return terms(query).stream().filter(term -> term.length() <= CHUNK_OVERLAP)
                .limit(MAX_TERMS).collect(Collectors.toList());
    }

    public List<Map<String, Object>> evidence(List<Map<String, Object>> candidates, List<String> terms, int limit) {
        List<Map<String, Object>> hits = new ArrayList<>();
        for (Map<String, Object> row : candidates) {
            String content = value(row.get("content"));
            int metadata = scoreTerms(value(row.get("asset_name")), terms) * 4
                    + scoreTerms(value(row.get("domain_name")), terms) * 2
                    + scoreTerms(value(row.get("description")), terms);
            Matcher breaks = PARAGRAPH_BREAK.matcher(content);
            int start = 0;
            int paragraph = 0;
            int bestScore = -1;
            Map<String, Object> best = new LinkedHashMap<>();
            while (start < content.length()) {
                boolean hasBreak = breaks.find();
                int end = hasBreak ? breaks.start() : content.length();
                while (start < end && Character.isWhitespace(content.charAt(start))) start++;
                while (end > start && Character.isWhitespace(content.charAt(end - 1))) end--;
                if (end > start) paragraph++;
                for (int pos = start; pos < end;) {
                    int next = Math.min(pos + CHUNK_LENGTH, end);
                    if (next < end && Character.isLowSurrogate(content.charAt(next))) next--;
                    String excerpt = content.substring(pos, next);
                    int score = metadata + 2 * scoreTerms(excerpt, terms);
                    if (score > bestScore && score > 0) {
                        bestScore = score;
                        best.put("paragraph", paragraph);
                        best.put("offset", pos);
                        best.put("excerpt", excerpt);
                        best.put("score", score);
                    }
                    if (next == end) break;
                    pos = next - CHUNK_OVERLAP;
                    if (Character.isLowSurrogate(content.charAt(pos))) pos--;
                }
                start = hasBreak ? breaks.end() : content.length();
            }
            if (!best.isEmpty()) {
                best.put("assetId", row.get("asset_id"));
                best.put("title", row.get("asset_name"));
                best.put("assetType", row.get("asset_type"));
                best.put("version", org.springframework.util.DigestUtils.md5DigestAsHex(
                        content.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                hits.add(best);
            }
        }
        hits.sort(Comparator.comparingInt((Map<String, Object> hit) -> (int) hit.get("score")).reversed());
        return hits.stream().limit(limit).collect(Collectors.toList());
    }

    private int scoreTerms(String text, List<String> terms) {
        String lower = text.toLowerCase(Locale.ROOT);
        int score = 0;
        for (String term : terms) {
            if (lower.contains(term)) score += term.length() > 3 ? 3 : 1;
        }
        return score;
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
        String normalized = value(query).toLowerCase(Locale.ROOT);
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

package com.ontologywiki.ontology.service.analysis;

import java.util.*;

public final class AnalysisInput {
    private static final java.util.regex.Pattern QUERY_PATTERN_LINE = java.util.regex.Pattern.compile("(?i)^\\s*(select|insert|update|delete)\\b");
    private AnalysisInput() { }
    public static Set<String> requestedTableNames(String raw) {
        Set<String> names = new LinkedHashSet<>();
        if (raw != null) for (String name : raw.split(",")) if (name != null && !name.trim().isEmpty()) names.add(name.trim().toLowerCase(Locale.ROOT));
        return names;
    }

    public static List<String> queryPatternsFromScope(Object scope) {
        List<String> result = new ArrayList<>();
        if (scope == null) return result;
        for (String line : String.valueOf(scope).split("\\r?\\n")) {
            String candidate = line.trim();
            if (candidate.length() <= 500 && QUERY_PATTERN_LINE.matcher(candidate).find()) result.add(candidate);
            if (result.size() == 20) break;
        }
        return result;
    }

    public static String scopeForPrompt(Object scope) {
        if (scope == null) return "";
        List<String> lines = new ArrayList<>();
        for (String line : String.valueOf(scope).split("\\r?\\n")) if (!QUERY_PATTERN_LINE.matcher(line.trim()).find()) lines.add(line);
        return String.join("\n", lines).trim();
    }

}

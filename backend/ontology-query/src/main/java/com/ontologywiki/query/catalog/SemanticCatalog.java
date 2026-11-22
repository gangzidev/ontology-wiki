package com.ontologywiki.query.catalog;

import java.util.LinkedHashMap;
import java.util.Map;

public final class SemanticCatalog {
    private SemanticCatalog() { }

    public static Map<String, Object> from(Map<String, Object> config) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (config == null) return result;
        for (String key : new String[]{"querySemantics", "semanticCatalog"}) {
            Object source = config.get(key);
            if (source instanceof Map) ((Map<?, ?>) source).forEach((name, value) -> result.put(String.valueOf(name), value));
        }
        return result;
    }
}

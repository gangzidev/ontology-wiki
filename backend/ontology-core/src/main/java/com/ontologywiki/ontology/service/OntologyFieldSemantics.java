package com.ontologywiki.ontology.service;

import java.util.Locale;

public final class OntologyFieldSemantics {
    private OntologyFieldSemantics() { }

    public static boolean isCategorical(String column, String dataType, String description) {
        String name = column == null ? "" : column.toLowerCase(Locale.ROOT);
        String type = dataType == null ? "" : dataType.toUpperCase(Locale.ROOT);
        String label = description == null ? "" : description;
        return type.matches(".*\\b(BOOLEAN|BOOL|BIT|ENUM)\\b.*")
                || name.matches("^(is|has|can|allow|enable|disable)_.*")
                || name.matches(".*(^|_)(status|state|type|category|class|level|flag|code|enabled|disabled|deleted|gender|sex)$")
                || label.matches("(?s).*(是否|状态|类型|类别|分类|标志|性别).*")
                || label.matches("(?s).*[(（]\\s*[0-9]+\\s*[:：=]?\\s*[^0-9)）]+[0-9]+.*[)）].*");
    }

    public static String displayName(String name) {
        if (name == null) return "";
        return name.replaceAll("[（(]\\s*[0-9]+[^）)]*[）)]", "").trim();
    }
}

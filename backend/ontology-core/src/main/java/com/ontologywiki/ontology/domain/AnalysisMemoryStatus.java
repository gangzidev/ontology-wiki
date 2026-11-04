package com.ontologywiki.ontology.domain;

/**
 * 分析知识状态枚举
 *
 * @author ontology-wiki
 */
public enum AnalysisMemoryStatus {

    /** 草稿（未确认） */
    DRAFT("DRAFT", "草稿"),

    /** 已确认（可复用） */
    CONFIRMED("CONFIRMED", "已确认");

    private final String code;
    private final String description;

    AnalysisMemoryStatus(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static AnalysisMemoryStatus fromCode(String code) {
        if (code == null) {
            return DRAFT;
        }
        for (AnalysisMemoryStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        return DRAFT;
    }
}

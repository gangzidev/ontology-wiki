package com.ontologywiki.ontology.domain;

/**
 * Wiki 资产类型枚举
 *
 * @author ontology-wiki
 */
public enum WikiAssetType {

    /** 原始业务文档（待整理） */
    DOCUMENT("DOCUMENT", "业务文档"),

    /** URL来源（待整理） */
    URL("URL", "URL来源"),

    /** 已整理的Wiki页面 */
    WIKI_PAGE("WIKI_PAGE", "Wiki页面"),

    /** CSV数据 */
    CSV("CSV", "CSV数据"),

    /** JSON数据 */
    JSON("JSON", "JSON数据"),

    /** SQL脚本 */
    SQL("SQL", "SQL脚本"),

    /** 配置文件 */
    CONFIG("CONFIG", "配置文件");

    private final String code;
    private final String description;

    WikiAssetType(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static WikiAssetType fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (WikiAssetType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        return null;
    }
}

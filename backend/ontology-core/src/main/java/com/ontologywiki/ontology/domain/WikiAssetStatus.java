package com.ontologywiki.ontology.domain;

/**
 * Wiki 资产状态枚举
 *
 * @author ontology-wiki
 */
public enum WikiAssetStatus {

    /** 正常 */
    ACTIVE("0", "正常"),

    /** 已删除/已停用 */
    INACTIVE("1", "已停用");

    private final String code;
    private final String description;

    WikiAssetStatus(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static WikiAssetStatus fromCode(String code) {
        if (code == null) {
            return ACTIVE;
        }
        for (WikiAssetStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        return ACTIVE;
    }
}

package com.ontologywiki.ontology.repository;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.map.MapUtil;

import java.util.List;
import java.util.Map;

/** ID columns from MyBatis result maps. Required IDs must be present before a downstream write. */
public final class RowIds {
    private RowIds() {}

    public static long first(List<Map<String,Object>> rows, String column) {
        return required(CollUtil.getFirst(rows), column);
    }

    public static long required(Map<String,Object> row, String column) {
        Long value = row == null ? null : MapUtil.getLong(row, column);
        if (value == null) throw new IllegalStateException("查询结果缺少有效 ID 字段 " + column);
        return value;
    }
}

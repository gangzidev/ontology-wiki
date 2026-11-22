package com.ontologywiki.query.dao;

import com.ontologywiki.query.compiler.QuerySqlCompiler;

import java.util.List;
import java.util.Map;

public interface BusinessQueryDao {
    List<Map<String, Object>> query(Map<String, Object> source, QuerySqlCompiler.Result compiled);
}

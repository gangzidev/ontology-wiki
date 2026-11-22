package com.ontologywiki.query.repository;

import com.ontologywiki.query.mapper.QueryConfigurationMapper;
import org.springframework.stereotype.Repository;

import java.util.Map;

/** 查询配置仓储，只负责读取本体配置，不在查询模块修改草案。 */
@Repository
public class QueryConfigurationRepository {
    private final QueryConfigurationMapper mapper;

    public QueryConfigurationRepository(QueryConfigurationMapper mapper) {
        this.mapper = mapper;
    }

    /** 读取指定配置，沿用本体模块的权限与数据表。 */
    public Map<String, Object> findProposal(long proposalId) {
        return mapper.selectProposal(proposalId);
    }

    /** 读取任务关联数据源类型，用于选择 SQL 方言。 */
    public Map<String, Object> findTaskDatasource(long taskId) {
        Map<String, Object> source = mapper.selectTaskDatasource(taskId);
        return source == null ? java.util.Collections.<String, Object>emptyMap() : source;
    }
}

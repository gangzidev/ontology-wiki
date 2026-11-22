package com.ontologywiki.query.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.Map;

public interface QueryConfigurationMapper {
    Map<String, Object> selectProposal(@Param("proposalId") long proposalId);
    Map<String, Object> selectTaskDatasource(@Param("taskId") long taskId);
}

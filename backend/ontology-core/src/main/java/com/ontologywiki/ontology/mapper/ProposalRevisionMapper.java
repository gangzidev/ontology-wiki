package com.ontologywiki.ontology.mapper;

import org.apache.ibatis.annotations.Param;
import java.util.Map;

public interface ProposalRevisionMapper {
    Map<String, Object> selectForUpdate(@Param("taskId") long taskId, @Param("proposalId") long proposalId);
    int insertVersion(Map<String, Object> params);
    int updateConfig(@Param("taskId") long taskId, @Param("proposalId") long proposalId, @Param("configJson") String configJson, @Param("username") String username);
}

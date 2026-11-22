package com.ontologywiki.query.mapper;

import com.ontologywiki.query.domain.QueryEvaluation;
import org.apache.ibatis.annotations.Param;

public interface QueryEvaluationMapper {
    int countPassed(@Param("proposalId") long proposalId, @Param("configHash") String configHash);
    int saveEvaluation(QueryEvaluation evaluation);
}

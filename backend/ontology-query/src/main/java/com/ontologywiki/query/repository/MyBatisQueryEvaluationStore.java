package com.ontologywiki.query.repository;

import com.ontologywiki.query.mapper.QueryEvaluationMapper;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisQueryEvaluationStore implements QueryEvaluationStore {
    private final QueryEvaluationMapper mapper;

    public MyBatisQueryEvaluationStore(QueryEvaluationMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean isPassed(long proposalId, String configHash) {
        try {
            return mapper.countPassed(proposalId, configHash) > 0;
        } catch (org.springframework.dao.DataAccessException failure) {
            return false;
        }
    }
}

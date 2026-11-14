package com.ontologywiki.query.service;

/** 服务端评测凭证存储；查询配置不能通过 JSON 自报通过。 */
public interface QueryEvaluationStore {
    boolean isPassed(long proposalId, String configHash);
}

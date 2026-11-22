package com.ontologywiki.query.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ontologywiki.query.repository.QueryConfigurationRepository;
import com.ontologywiki.query.repository.QueryEvaluationStore;
import com.ontologywiki.query.support.QueryConfigHash;
import com.ontologywiki.query.validation.QueryConfigurationValidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 检查本体配置是否具备可评测的查询语义，不执行模型调用或业务 SQL。 */
@Service
public class QueryReadinessService {
    private final QueryConfigurationRepository repository;
    private final QueryConfigurationValidator validator;
    private final ObjectMapper json;
    private final QueryEvaluationStore evaluations;

    public QueryReadinessService(QueryConfigurationRepository repository, ObjectMapper json) {
        this(repository, json, (proposalId, configHash) -> false);
    }

    @Autowired
    public QueryReadinessService(QueryConfigurationRepository repository, ObjectMapper json, QueryEvaluationStore evaluations) {
        this.repository = repository;
        this.validator = new QueryConfigurationValidator();
        this.json = json;
        this.evaluations = evaluations;
    }

    /** 只有已完成审核且具备查询语义定义的配置才可进入准确性评测。 */
    public Readiness check(long proposalId) {
        return check(proposalId, repository.findProposal(proposalId));
    }

    /** 复用本次请求读取的配置，避免目录与准入检查重复传输大字段。 */
    public Readiness check(long proposalId, Map<String, Object> proposal) {
        if (proposal == null) {
            throw new IllegalArgumentException("配置草案不存在");
        }

        String status = String.valueOf(proposal.get("status"));
        List<String> reasons;
        boolean passed = false;
        try {
            JsonNode config = json.readTree(String.valueOf(proposal.get("config_json")));
            reasons = validator.validate(status, config, false);
            try {
                passed = evaluations.isPassed(proposalId, QueryConfigHash.of(json, config));
            } catch (Exception ignored) {
                passed = false;
            }
        } catch (Exception e) {
            reasons = new ArrayList<>();
            reasons.add("配置草案不是有效 JSON");
        }
        return new Readiness(reasons.isEmpty(), reasons.isEmpty(), reasons, passed);
    }

    /** 读取配置供计划编译使用；查询执行前仍需经过本服务的准入检查。 */
    public Map<String, Object> proposal(long proposalId) {
        return proposal(repository.findProposal(proposalId));
    }

    /** 从同一份草案快照解析配置，不缓存跨请求的审核状态。 */
    public Map<String, Object> proposal(Map<String, Object> proposal) {
        if (proposal == null) throw new IllegalArgumentException("配置草案不存在");
        try {
            return json.readValue(String.valueOf(proposal.get("config_json")),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            throw new IllegalArgumentException("配置草案不是有效 JSON", e);
        }
    }

    /** 查询准入检查结果。 */
    public static class Readiness {
        private final boolean eligibleForEvaluation;
        private final boolean queryEnabled;
        private final List<String> reasons;
        private final boolean evaluationPassed;

        public Readiness(boolean eligibleForEvaluation, boolean queryEnabled, List<String> reasons) {
            this(eligibleForEvaluation, queryEnabled, reasons, false);
        }

        public Readiness(boolean eligibleForEvaluation, boolean queryEnabled, List<String> reasons, boolean evaluationPassed) {
            this.eligibleForEvaluation = eligibleForEvaluation;
            this.queryEnabled = queryEnabled;
            this.reasons = reasons;
            this.evaluationPassed = evaluationPassed;
        }

        public boolean isEligibleForEvaluation() { return eligibleForEvaluation; }
        public boolean isQueryEnabled() { return queryEnabled; }
        public boolean isEvaluationPassed() { return evaluationPassed; }
        public List<String> getReasons() { return reasons; }
    }
}

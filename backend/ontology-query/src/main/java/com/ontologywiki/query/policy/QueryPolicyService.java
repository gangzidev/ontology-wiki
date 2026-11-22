package com.ontologywiki.query.policy;

import com.ontologywiki.query.mapper.QueryHistoryMapper;
import com.ontologywiki.query.validation.QueryPlanValidator;
import org.springframework.stereotype.Service;
import java.util.Map;

@Service
public class QueryPolicyService {
    private final QueryReadinessService readiness;
    private final QueryPlanValidator validator;
    private final QueryHistoryMapper history;

    public QueryPolicyService(QueryReadinessService readiness, QueryPlanValidator validator, QueryHistoryMapper history) {
        this.readiness = readiness;
        this.validator = validator;
        this.history = history;
    }

    public void requireExecution(long proposalId) {
        QueryReadinessService.Readiness state = readiness.check(proposalId);
        if (!state.isQueryEnabled()) throw new IllegalStateException("配置暂不具备查询条件：" + String.join("；", state.getReasons()));
    }

    public void validatePlan(Map<String,Object> plan, Map<String,Object> config) { validator.validate(plan, config); }

    public void requireUser(String username) {
        if (username == null || username.trim().isEmpty()) throw new IllegalArgumentException("查询历史必须关联当前登录用户");
    }

    public void requireSession(String username, long proposalId, String sessionId) {
        requireUser(username);
        if (sessionId == null || sessionId.isEmpty()) return;
        if (!sessionId.matches("[A-Za-z0-9_-]{8,64}")) throw new IllegalArgumentException("会话编号无效");
        if (history.countSession(username, proposalId, sessionId) != 1) throw new IllegalArgumentException("查询会话不存在或无权访问");
    }
}

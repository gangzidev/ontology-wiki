package com.ontologywiki.query.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ontologywiki.query.domain.QueryMessage;
import com.ontologywiki.query.domain.QuerySession;
import com.ontologywiki.query.mapper.QueryHistoryMapper;
import com.ontologywiki.query.policy.QueryPolicyService;
import com.ontologywiki.query.service.IQueryHistoryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class QueryHistoryServiceImpl implements IQueryHistoryService {
    private static final int PAGE_SIZE = 20;
    private final QueryHistoryMapper historyMapper;
    private final ObjectMapper json;
    private final QueryPolicyService policy;

    public QueryHistoryServiceImpl(QueryHistoryMapper historyMapper, ObjectMapper json, QueryPolicyService policy) {
        this.historyMapper = historyMapper;
        this.json = json;
        this.policy = policy;
    }

    @Override
    public void validateSession(String username, long proposalId, String sessionId) {
        policy.requireSession(username, proposalId, sessionId);
    }

    @Override
    public Map<String, Object> sessions(String username, long proposalId, int page) {
        policy.requireUser(username);
        if (page < 1 || page > 100000) throw new IllegalArgumentException("页码无效");
        List<Map<String, Object>> sessions = new ArrayList<>();
        for (QuerySession row : historyMapper.selectSessions(username, proposalId, PAGE_SIZE + 1, (page - 1) * PAGE_SIZE)) {
            Map<String, Object> session = new LinkedHashMap<>();
            session.put("sessionId", row.getSessionId());
            session.put("title", row.getTitle());
            session.put("updatedAt", row.getUpdateTime() == null ? null : row.getUpdateTime().getTime());
            sessions.add(session);
        }
        return page(sessions);
    }

    @Override
    public Map<String, Object> latest(String username, long proposalId, String sessionId) {
        validateSession(username, proposalId, sessionId);
        if (sessionId == null || sessionId.isEmpty()) return Collections.emptyMap();
        List<QueryMessage> messages = historyMapper.selectMessages(username, proposalId, sessionId, Long.MAX_VALUE, 1);
        return messages.isEmpty() ? Collections.emptyMap() : readMessage(messages.get(0));
    }

    @Override
    public Map<String, Object> messages(String username, long proposalId, String sessionId, Long beforeId) {
        if (sessionId == null || sessionId.isEmpty()) throw new IllegalArgumentException("会话编号不能为空");
        validateSession(username, proposalId, sessionId);
        List<Map<String, Object>> messages = new ArrayList<>();
        for (QueryMessage row : historyMapper.selectMessages(username, proposalId, sessionId, beforeId == null ? Long.MAX_VALUE : beforeId, PAGE_SIZE + 1)) {
            messages.add(readMessage(row));
        }
        Map<String, Object> result = page(messages);
        Collections.reverse((List<?>) result.get("items"));
        return result;
    }

    private Map<String, Object> readMessage(QueryMessage row) {
        try {
            Map<String, Object> message = json.readValue(row.getContent(), new TypeReference<Map<String, Object>>() {});
            message.put("messageId", row.getMessageId());
            message.put("createdAt", row.getCreateTime() == null ? null : row.getCreateTime().getTime());
            return message;
        } catch (Exception failure) {
            throw new IllegalStateException("历史消息读取失败", failure);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> save(String username, long proposalId, String sessionId, Map<String, Object> response) {
        validateSession(username, proposalId, sessionId);
        String activeId = sessionId == null || sessionId.isEmpty() ? "query-" + UUID.randomUUID() : sessionId;
        Map<String, Object> stored = new LinkedHashMap<>(response);
        stored.put("sessionId", activeId);
        stored.put("createdAt", System.currentTimeMillis());
        QueryMessage message = new QueryMessage();
        message.setSessionId(activeId);
        message.setCreatedBy(username);
        try {
            message.setContent(json.writeValueAsString(stored));
        } catch (Exception failure) {
            throw new IllegalStateException("查询记录保存失败", failure);
        }
        if (sessionId == null || sessionId.isEmpty()) {
            String title = String.valueOf(response.getOrDefault("question", "数据查询"));
            QuerySession session = new QuerySession();
            session.setSessionId(activeId);
            session.setProposalId(proposalId);
            session.setTitle(title.substring(0, Math.min(title.length(), 80)));
            session.setCreatedBy(username);
            historyMapper.insertSession(session);
        }
        historyMapper.insertMessage(message);
        historyMapper.touchSession(username, proposalId, activeId);
        return stored;
    }

    private Map<String, Object> page(List<Map<String, Object>> items) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("hasMore", items.size() > PAGE_SIZE);
        result.put("items", new ArrayList<>(items.subList(0, Math.min(items.size(), PAGE_SIZE))));
        return result;
    }
}

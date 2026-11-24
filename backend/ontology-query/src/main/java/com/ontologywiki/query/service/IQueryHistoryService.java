package com.ontologywiki.query.service;

import java.util.Map;

public interface IQueryHistoryService {
    void validateSession(String username, long proposalId, String sessionId);
    Map<String, Object> sessions(String username, long proposalId, int page);
    Map<String, Object> messages(String username, long proposalId, String sessionId, Long beforeId);
    Map<String, Object> latest(String username, long proposalId, String sessionId);
    Map<String, Object> save(String username, long proposalId, String sessionId, Map<String, Object> response);
}

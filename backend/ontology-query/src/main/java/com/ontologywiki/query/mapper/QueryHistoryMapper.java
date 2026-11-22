package com.ontologywiki.query.mapper;

import com.ontologywiki.query.domain.QueryMessage;
import com.ontologywiki.query.domain.QuerySession;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface QueryHistoryMapper {
    int countSession(@Param("username") String username, @Param("proposalId") long proposalId, @Param("sessionId") String sessionId);
    List<QuerySession> selectSessions(@Param("username") String username, @Param("proposalId") long proposalId,
                                     @Param("limit") int limit, @Param("offset") int offset);
    List<QueryMessage> selectMessages(@Param("username") String username, @Param("proposalId") long proposalId,
                                     @Param("sessionId") String sessionId, @Param("beforeId") long beforeId, @Param("limit") int limit);
    int insertSession(QuerySession session);
    int insertMessage(QueryMessage message);
    int touchSession(@Param("username") String username, @Param("proposalId") long proposalId, @Param("sessionId") String sessionId);
}

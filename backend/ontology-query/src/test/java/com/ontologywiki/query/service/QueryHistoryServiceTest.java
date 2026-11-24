package com.ontologywiki.query.service;

import com.ontologywiki.query.policy.QueryPolicyService;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ontologywiki.ontology.mapper.WikiChatMapper;
import com.ontologywiki.query.mapper.QueryHistoryMapper;
import com.ontologywiki.query.service.impl.QueryHistoryServiceImpl;
import com.ontologywiki.query.support.QueryMapperTestSupport;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.*;

public class QueryHistoryServiceTest {
    private IQueryHistoryService history;
    private JdbcTemplate jdbc;

    @Before
    public void setup() throws Exception {
        JdbcDataSource database = new JdbcDataSource();
        database.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(database);
        jdbc.execute("create table wiki_chat_session (session_id varchar(64), space_id bigint, scope_type varchar(20), scope_id bigint, title varchar(200), created_by varchar(64), create_time timestamp, update_time timestamp, primary key(session_id, created_by))");
        jdbc.execute("create table wiki_chat_message (message_id bigint auto_increment primary key, session_id varchar(64), role varchar(20), content longtext not null, created_by varchar(64), create_time timestamp)");
        history = historyService();
    }

    private IQueryHistoryService historyService() throws Exception {
        QueryMapperTestSupport support = new QueryMapperTestSupport(jdbc.getDataSource());
        return support.transactional(IQueryHistoryService.class, new QueryHistoryServiceImpl(support.mapper(QueryHistoryMapper.class), new ObjectMapper(), new QueryPolicyService(null, null, support.mapper(QueryHistoryMapper.class))));
    }

    @Test
    public void restoresResultsAndClarificationAcrossServiceInstances() throws Exception {
        Map<String, Object> response = message("本月订单金额", "result");
        response.put("rows", Collections.singletonList(Collections.singletonMap("amount", 125.50)));
        response.put("columns", Collections.singletonList("amount"));
        response.put("sql", "select sum(amount) from orders");
        response.put("chartHint", Collections.singletonMap("type", "stat"));
        response.put("configHash", "original-configuration");
        String sessionId = String.valueOf(history.save("alice", 7, null, response).get("sessionId"));
        Map<String, Object> clarification = message("补充地区范围", "clarification");
        clarification.put("queryQuestion", "按地区统计\n补充说明：华东地区");
        clarification.put("clarification", "请确认统计时间");
        history.save("alice", 7, sessionId, clarification);

        IQueryHistoryService reloaded = historyService();
        List<Map<String, Object>> messages = items(reloaded.messages("alice", 7, sessionId, null));
        assertEquals(2, messages.size());
        for (String key : Arrays.asList("question", "rows", "columns", "sql", "chartHint", "configHash")) assertEquals(response.get(key), messages.get(0).get(key));
        assertEquals(clarification.get("queryQuestion"), messages.get(1).get("queryQuestion"));
        assertEquals(clarification.get("clarification"), messages.get(1).get("clarification"));
        assertEquals(clarification.get("clarification"), reloaded.latest("alice", 7, sessionId).get("clarification"));
        assertTrue(reloaded.latest("alice", 7, "").isEmpty());
        assertEquals(1, items(reloaded.sessions("alice", 7, 1)).size());
    }

    @Test
    public void isolatesUsersConfigurationsAndKnowledgeConversations() {
        String sessionId = String.valueOf(history.save("alice", 7, null, message("订单数量", "result")).get("sessionId"));
        jdbc.update("insert into wiki_chat_session(session_id, scope_type, scope_id, title, created_by, create_time, update_time) values (?, 'ALL', 7, '知识问答', 'alice', current_timestamp, current_timestamp)", "wiki-session");
        assertTrue(items(history.sessions("bob", 7, 1)).isEmpty());
        assertTrue(items(history.sessions("alice", 8, 1)).isEmpty());
        assertEquals(1, items(history.sessions("alice", 7, 1)).size());
        rejected(() -> history.messages("bob", 7, sessionId, null));
        rejected(() -> history.messages("alice", 8, sessionId, null));
        rejected(() -> history.save("bob", 7, sessionId, message("冒用会话", "result")));
        rejected(() -> history.messages("alice", 7, "wiki-session", null));
        assertEquals(1, jdbc.queryForObject("select count(*) from wiki_chat_message", Integer.class).intValue());
    }

    @Test
    public void paginatesSessionsAndMessagesWithoutDroppingOlderRecords() {
        String sessionId = null;
        for (int index = 0; index < 25; index++) {
            sessionId = String.valueOf(history.save("alice", 7, sessionId, message("问题" + index, "result")).get("sessionId"));
        }
        Map<String, Object> recent = history.messages("alice", 7, sessionId, null);
        assertEquals(true, recent.get("hasMore"));
        assertEquals(20, items(recent).size());
        assertEquals("问题5", items(recent).get(0).get("question"));
        assertEquals("问题24", items(recent).get(19).get("question"));
        Map<String, Object> older = history.messages("alice", 7, sessionId, ((Number) items(recent).get(0).get("messageId")).longValue());
        assertEquals(false, older.get("hasMore"));
        assertEquals(5, items(older).size());
        assertEquals("问题0", items(older).get(0).get("question"));
        for (int index = 0; index < 22; index++) history.save("alice", 7, null, message("新查询" + index, "error"));
        assertEquals(20, items(history.sessions("alice", 7, 1)).size());
        assertEquals(true, history.sessions("alice", 7, 1).get("hasMore"));
        assertEquals(3, items(history.sessions("alice", 7, 2)).size());
        assertEquals(false, history.sessions("alice", 7, 2).get("hasMore"));
    }

    @Test
    public void knowledgeEndpointsCannotReadQueryHistory() throws Exception {
        String sessionId = String.valueOf(history.save("alice", 7, null, message("业务数据", "result")).get("sessionId"));
        jdbc.update("insert into wiki_chat_session(session_id, scope_type, scope_id, title, created_by, create_time, update_time) values (?, 'ALL', 7, '知识问答', 'alice', current_timestamp, current_timestamp)", "wiki-session");
        jdbc.update("insert into wiki_chat_message(session_id, role, content, created_by, create_time) values ('wiki-session', 'assistant', '知识回答', 'alice', current_timestamp)");
        Configuration configuration = new Configuration(new Environment("history-test", new JdbcTransactionFactory(), jdbc.getDataSource()));
        String resource = "mapper/ontology/WikiChatMapper.xml";
        try (java.io.InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        try (SqlSession session = new SqlSessionFactoryBuilder().build(configuration).openSession()) {
            WikiChatMapper knowledge = session.getMapper(WikiChatMapper.class);
            assertEquals(1, knowledge.listSessions("alice", 100).size());
            assertTrue(knowledge.selectSessionMessages(sessionId, "alice", 100).isEmpty());
            assertTrue(knowledge.selectRecentMessages(sessionId, "alice", 100).isEmpty());
            assertEquals(1, knowledge.selectSessionMessages("wiki-session", "alice", 100).size());
            assertEquals(1, knowledge.selectRecentMessages("wiki-session", "alice", 100).size());
        }
    }

    @Test
    public void rollsBackSessionCreationWhenMessageSaveFails() {
        jdbc.execute("alter table wiki_chat_message add constraint reject_content check (length(content) < 50)");
        try {
            history.save("alice", 7, null, message("保存失败的查询", "result"));
            fail("Expected failed insert");
        } catch (org.springframework.dao.DataAccessException expected) {
            assertEquals(0, jdbc.queryForObject("select count(*) from wiki_chat_session", Integer.class).intValue());
        }
    }

    private void rejected(Runnable operation) {
        try {
            operation.run();
            fail("Expected access rejection");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("无权访问"));
        }
    }

    private Map<String, Object> message(String question, String type) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("question", question);
        result.put("type", type);
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> items(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("items");
    }
}

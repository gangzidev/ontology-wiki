package com.ontologywiki.query.mapper;

import com.ontologywiki.query.domain.QueryEvaluation;
import com.ontologywiki.query.domain.QueryMessage;
import com.ontologywiki.query.domain.QuerySession;
import com.ontologywiki.query.repository.MyBatisQueryEvaluationStore;
import com.ontologywiki.query.repository.QueryConfigurationRepository;
import com.ontologywiki.query.support.QueryMapperTestSupport;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.*;

public class QueryPersistenceMapperTest {
    private QueryMapperTestSupport support;
    private JdbcTemplate jdbc;

    @Before
    public void setup() throws Exception {
        JdbcDataSource database = new JdbcDataSource();
        database.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        support = new QueryMapperTestSupport(database);
        jdbc = new JdbcTemplate(database);
    }

    @Test
    public void everyXmlStatementBindsToMapperMethod() {
        for (Class<?> mapper : QueryMapperTestSupport.MAPPERS) {
            for (Method method : mapper.getDeclaredMethods()) {
                assertTrue(mapper.getSimpleName() + "." + method.getName(), support.factory().getConfiguration().hasStatement(mapper.getName() + "." + method.getName(), false));
            }
        }
    }

    @Test
    public void evaluationUpsertDoesNotDuplicateAndInvalidatesPreviousPass() {
        jdbc.execute("create table ontology_query_evaluation (proposal_id bigint, config_hash varchar(64), benchmark_version varchar(100), passed tinyint, case_count int, passed_count int, detail text, evaluated_by varchar(64), evaluated_at timestamp, unique(proposal_id, config_hash, benchmark_version))");
        QueryEvaluationMapper mapper = support.mapper(QueryEvaluationMapper.class);
        MyBatisQueryEvaluationStore store = new MyBatisQueryEvaluationStore(mapper);
        QueryEvaluation evaluation = new QueryEvaluation();
        evaluation.setProposalId(7L);
        evaluation.setConfigHash("version-hash");
        evaluation.setBenchmarkVersion("v1");
        evaluation.setPassed(true);
        evaluation.setCaseCount(2);
        evaluation.setPassedCount(2);
        evaluation.setDetail("[]");
        evaluation.setEvaluatedBy("alice");
        mapper.saveEvaluation(evaluation);
        assertTrue(store.isPassed(7, "version-hash"));
        assertFalse(store.isPassed(8, "version-hash"));
        assertFalse(store.isPassed(7, "other-version"));
        evaluation.setPassed(false);
        evaluation.setPassedCount(1);
        evaluation.setEvaluatedBy("bob");
        evaluation.setDetail("[{\"error\":\"结果不一致\"}]");
        mapper.saveEvaluation(evaluation);
        assertFalse(store.isPassed(7, "version-hash"));
        assertEquals(1, jdbc.queryForObject("select count(*) from ontology_query_evaluation", Integer.class).intValue());
        assertEquals("bob", jdbc.queryForObject("select evaluated_by from ontology_query_evaluation", String.class));
        assertEquals(1, jdbc.queryForObject("select passed_count from ontology_query_evaluation", Integer.class).intValue());
    }

    @Test
    public void missingEvaluationTableRemainsAnOptionalQualityRecord() {
        assertFalse(new MyBatisQueryEvaluationStore(support.mapper(QueryEvaluationMapper.class)).isPassed(1, "hash"));
    }

    @Test
    public void configurationAndDatasourceProjectionKeepsContractKeys() {
        jdbc.execute("create table ontology_proposal (proposal_id bigint, task_id bigint, status varchar(30), config_json text)");
        jdbc.execute("create table analysis_task (task_id bigint, source_id bigint)");
        jdbc.execute("create table ontology_datasource (datasource_id bigint, name varchar(50), schema_name varchar(50), db_type varchar(30), host varchar(80), port int, database_name varchar(80), jdbc_url varchar(200), username varchar(50), password_enc varchar(100))");
        jdbc.update("insert into ontology_proposal values (7, 3, 'COMPLETED', '{}')");
        jdbc.update("insert into analysis_task values (3, 2)");
        jdbc.update("insert into ontology_datasource values (2, '订单库', 'sales', 'mysql', 'localhost', 3306, 'orders', 'jdbc:mysql://localhost/orders', 'reader', 'encrypted')");
        QueryConfigurationRepository repository = new QueryConfigurationRepository(support.mapper(QueryConfigurationMapper.class));
        assertEquals("COMPLETED", repository.findProposal(7).get("status"));
        assertEquals("{}", repository.findProposal(7).get("config_json"));
        assertEquals(3L, ((Number) repository.findProposal(7).get("task_id")).longValue());
        Map<String, Object> source = repository.findTaskDatasource(3);
        assertEquals("sales", source.get("schema_name"));
        assertEquals("reader", source.get("username"));
        assertEquals("encrypted", source.get("password_enc"));
        assertEquals("mysql", source.get("db_type"));
        assertNull(repository.findProposal(99));
        assertTrue(repository.findTaskDatasource(99).isEmpty());
    }

    @Test
    public void mapperItselfEnforcesHistoryOwnershipAndBindsValues() {
        jdbc.execute("create table wiki_chat_session (session_id varchar(64), scope_type varchar(20), scope_id bigint, title varchar(200), created_by varchar(64), create_time timestamp, update_time timestamp)");
        jdbc.execute("create table wiki_chat_message (message_id bigint auto_increment, session_id varchar(64), role varchar(20), content text, created_by varchar(64), create_time timestamp)");
        QueryHistoryMapper mapper = support.mapper(QueryHistoryMapper.class);
        QuerySession session = new QuerySession();
        session.setSessionId("query-test-session");
        session.setProposalId(7L);
        session.setTitle("' OR 1=1 --");
        session.setCreatedBy("alice");
        mapper.insertSession(session);
        QueryMessage message = new QueryMessage();
        message.setSessionId(session.getSessionId());
        message.setCreatedBy("alice");
        message.setContent("{\"question\":\"业务问题\"}");
        mapper.insertMessage(message);
        assertEquals(1, mapper.countSession("alice", 7, session.getSessionId()));
        assertEquals(0, mapper.countSession("alice' OR 1=1 --", 7, session.getSessionId()));
        assertTrue(mapper.selectMessages("bob", 7, session.getSessionId(), Long.MAX_VALUE, 20).isEmpty());
        assertTrue(mapper.selectMessages("alice", 8, session.getSessionId(), Long.MAX_VALUE, 20).isEmpty());
        assertEquals(1, mapper.selectMessages("alice", 7, session.getSessionId(), Long.MAX_VALUE, 20).size());
        assertEquals("' OR 1=1 --", mapper.selectSessions("alice", 7, 20, 0).get(0).getTitle());
        assertEquals(0, mapper.touchSession("bob", 7, session.getSessionId()));
    }
}

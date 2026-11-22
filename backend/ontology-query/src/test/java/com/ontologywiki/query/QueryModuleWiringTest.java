package com.ontologywiki.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ontologywiki.ontology.service.CredentialService;
import com.ontologywiki.ontology.service.llm.LlmClient;
import com.ontologywiki.query.controller.DataQueryController;
import com.ontologywiki.query.domain.QueryResult;
import com.ontologywiki.query.repository.QueryEvaluationStore;
import com.ontologywiki.query.service.IDataQueryService;
import com.ontologywiki.query.service.IQueryHistoryService;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;

import static org.junit.Assert.*;

public class QueryModuleWiringTest {
    private AnnotationConfigApplicationContext context;
    private IDataQueryService queries;
    private IQueryHistoryService history;
    private JdbcTemplate jdbc;

    @Before
    public void setup() throws Exception {
        context = new AnnotationConfigApplicationContext(TestConfiguration.class);
        queries = context.getBean(IDataQueryService.class);
        history = context.getBean(IQueryHistoryService.class);
        JdbcDataSource database = (JdbcDataSource) context.getBean(DataSource.class);
        jdbc = new JdbcTemplate(database);
        jdbc.execute("create table ontology_proposal (proposal_id bigint, task_id bigint, status varchar(30), config_json text)");
        jdbc.execute("create table analysis_task (task_id bigint, source_id bigint)");
        jdbc.execute("create table ontology_datasource (datasource_id bigint, name varchar(50), schema_name varchar(50), db_type varchar(30), host varchar(80), port int, database_name varchar(80), jdbc_url varchar(200), username varchar(50), password_enc varchar(100))");
        jdbc.execute("create table wiki_chat_session (session_id varchar(64), scope_type varchar(20), scope_id bigint, title varchar(200), created_by varchar(64), create_time timestamp, update_time timestamp)");
        jdbc.execute("create table wiki_chat_message (message_id bigint auto_increment, session_id varchar(64), role varchar(20), content text, created_by varchar(64), create_time timestamp)");
        jdbc.execute("create table ontology_query_evaluation (proposal_id bigint, config_hash varchar(64), benchmark_version varchar(100), passed tinyint, case_count int, passed_count int, detail text, evaluated_by varchar(64), evaluated_at timestamp, unique(proposal_id, config_hash, benchmark_version))");
        jdbc.execute("create table orders (order_id bigint, region varchar(30))");
        jdbc.execute("insert into orders values (1, 'east'), (2, 'west'), (3, 'east')");
        jdbc.update("insert into analysis_task values (3, 2)");
        jdbc.update("insert into ontology_datasource(datasource_id, db_type, jdbc_url, username, password_enc) values (2, 'mysql', ?, 'sa', '')", database.getURL());
        jdbc.update("insert into ontology_proposal values (7, 3, 'COMPLETED', ?)", new ObjectMapper().writeValueAsString(config()));
    }

    @After
    public void close() { if (context != null) context.close(); }

    @Test
    public void fullSpringGraphRunsQueryAndPersistsHistoryWithStableResponse() {
        assertNotNull(context.getBean(DataQueryController.class));
        assertTrue(AopUtils.isAopProxy(history));
        Map<String, Object> response = queries.ask(7, row("question", "统计订单数量"), "alice");
        assertEquals("result", response.get("type"));
        assertEquals("统计订单数量", response.get("question"));
        assertEquals(3L, ((Number) rows(response, "rows").get(0).get("order_count")).longValue());
        for (String key : Arrays.asList("plan", "columns", "rowCount", "sql", "parameters", "explanation", "configHash", "chartHint", "semanticEvidence", "queryEnabled", "sessionId")) assertTrue(key, response.containsKey(key));
        Map<String, Object> restored = history.messages("alice", 7, String.valueOf(response.get("sessionId")), null);
        assertEquals(1, rows(restored, "items").size());
        assertEquals(response.get("columns"), rows(restored, "items").get(0).get("columns"));
        assertTrue(rows(history.sessions("bob", 7, 1), "items").isEmpty());
        Map<String, Object> summary = queries.catalog(7);
        assertEquals("订单数量", rows(summary, "metrics").get(0).get("name"));
        assertEquals("orders", rows(summary, "metrics").get(0).get("objectId"));
    }

    @Test
    public void tableDataQuestionReturnsDetailRowsInsteadOfClarification() {
        Map<String, Object> response = queries.ask(7, row("question", "订单的数据"), "alice");
        assertEquals("result", response.get("type"));
        assertEquals(3, rows(response, "rows").size());
        assertEquals(Arrays.asList("order_id", "region"), response.get("columns"));
        assertTrue(String.valueOf(response.get("sql")).toLowerCase().contains("select t0.`order_id`, t0.`region`"));
    }

    @Test
    public void benchmarkPersistsEvaluatorAndUpsertsThroughXml() {
        Map<String, Object> plan = row("metrics", Collections.singletonList(row("id", "order_count")));
        Map<String, Object> benchmark = row("benchmarkVersion", "v1", "cases", Collections.singletonList(row("id", "count", "plan", plan,
                "expectedRows", Collections.singletonList(row("order_count", 3)))));
        Map<String, Object> passed = queries.evaluate(7, benchmark, "reviewer");
        assertEquals(true, passed.get("passed"));
        assertEquals("reviewer", jdbc.queryForObject("select evaluated_by from ontology_query_evaluation", String.class));
        QueryEvaluationStore evaluations = context.getBean(QueryEvaluationStore.class);
        assertTrue(evaluations.isPassed(7, String.valueOf(passed.get("configHash"))));
        benchmark.put("cases", Collections.singletonList(row("id", "count", "plan", plan, "expectedRows", Collections.singletonList(row("order_count", 99)))));
        assertEquals(false, queries.evaluate(7, benchmark, "reviewer").get("passed"));
        assertFalse(evaluations.isPassed(7, String.valueOf(passed.get("configHash"))));
        assertEquals(1, jdbc.queryForObject("select count(*) from ontology_query_evaluation", Integer.class).intValue());
        QueryResult result = queries.execute(7, plan);
        assertEquals(3L, ((Number) result.getRows().get(0).get("order_count")).longValue());
    }

    @Test
    public void historySaveFailureDoesNotLoseQueryAndRollsBackNewSession() {
        jdbc.execute("alter table wiki_chat_message add constraint reject_content check(length(content) < 10)");
        Map<String, Object> response = queries.ask(7, row("question", "统计订单数量"), "alice");
        assertEquals("result", response.get("type"));
        assertTrue(response.containsKey("historyWarning"));
        assertEquals(0, jdbc.queryForObject("select count(*) from wiki_chat_session", Integer.class).intValue());
        assertEquals(0, jdbc.queryForObject("select count(*) from wiki_chat_message", Integer.class).intValue());
    }

    @Test
    public void drilldownKeepsLegacyAndConversationRequestFormats() {
        Map<String, Object> plan = row("metrics", Collections.singletonList(row("id", "order_count")));
        Map<String, Object> legacy = queries.drilldown(7, plan, "alice");
        assertEquals("result", legacy.get("type"));
        assertEquals("继续下钻分析", legacy.get("question"));
        Map<String, Object> request = row("plan", plan, "sessionId", legacy.get("sessionId"), "question", "继续分析订单");
        Map<String, Object> continued = queries.drilldown(7, request, "alice");
        assertEquals(legacy.get("sessionId"), continued.get("sessionId"));
        assertEquals("继续分析订单", continued.get("question"));
        assertEquals(2, rows(history.messages("alice", 7, String.valueOf(legacy.get("sessionId")), null), "items").size());
    }

    @Test
    public void clarificationIsStoredAndCompileDoesNotExecuteBusinessSql() {
        Map<String, Object> clarification = queries.ask(7, row("question", "外星文明"), "alice");
        assertEquals("clarification", clarification.get("type"));
        assertTrue(clarification.containsKey("clarification"));
        assertFalse(clarification.containsKey("rows"));
        assertEquals(1, rows(history.messages("alice", 7, String.valueOf(clarification.get("sessionId")), null), "items").size());
        jdbc.execute("drop table orders");
        assertTrue(queries.compile(7, row("metrics", Collections.singletonList(row("id", "order_count")))).getSql().contains("orders"));
    }

    @Test
    public void anonymousHistoryAndUnreviewedEvaluationCannotBypassPolicy() {
        try { history.sessions("", 7, 1); fail(); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("登录用户")); }
        jdbc.update("update ontology_proposal set status = 'DRAFT' where proposal_id = 7");
        try {
            context.getBean(com.ontologywiki.query.service.IQueryExecutionService.class).executeForEvaluation(7, row("metrics", Collections.singletonList(row("id", "order_count"))));
            fail();
        } catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("查询条件")); }
    }

    @Test
    public void rejectsForeignSessionsBeforeExecutingAndRetainsDraftGuard() {
        Map<String, Object> response = queries.ask(7, row("question", "统计订单数量"), "alice");
        try {
            queries.ask(7, row("question", "统计订单数量", "sessionId", response.get("sessionId")), "bob");
            fail("Should not reuse another user's session");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("无权访问"));
        }
        jdbc.update("update ontology_proposal set status = 'DRAFT' where proposal_id = 7");
        Map<String, Object> blocked = queries.ask(7, row("question", "统计订单数量"), "alice");
        assertEquals("error", blocked.get("type"));
        assertFalse(blocked.containsKey("rows"));
    }

    @Test
    public void userTableQuestionsWorkWithoutAnyAggregateMetricAndRestoreDetailHistory() throws Exception {
        jdbc.execute("create table sys_user(user_id bigint primary key, user_name varchar(30))");
        jdbc.execute("insert into sys_user values (1, 'alice'), (2, 'bob')");
        Map<String, Object> config = row("objects", Collections.singletonList(row("id", "users", "name", "用户信息表", "table", "sys_user")),
                "properties", Arrays.asList(row("id", "userId", "objectId", "users", "column", "user_id", "name", "用户编号", "dataType", "BIGINT"),
                        row("id", "userName", "objectId", "users", "column", "user_name", "name", "用户名称", "dataType", "VARCHAR")));
        jdbc.update("update ontology_proposal set config_json = ? where proposal_id = 7", new ObjectMapper().writeValueAsString(config));
        for (String question : Arrays.asList("我想看用户表信息", "用户信息表的数据", "我想看用户表信息\n补充说明：用户信息表的数据", "查看sys_user的数据")) {
            Map<String, Object> response = queries.ask(7, row("question", question), "alice");
            assertEquals(String.valueOf(response), "result", response.get("type"));
            assertEquals(2, rows(response, "rows").size());
            assertEquals("alice", rows(response, "rows").get(0).get("user_name"));
            assertEquals("table", ((Map<?, ?>) response.get("chartHint")).get("type"));
            assertEquals("用户名称", ((Map<?, ?>) response.get("columnLabels")).get("user_name"));
            assertEquals(1, rows(history.messages("alice", 7, String.valueOf(response.get("sessionId")), null), "items").size());
        }
        Map<String, Object> page = queries.drilldown(7, row("plan", row("detailObjectId", "users", "limit", 1, "offset", 1)), "alice");
        assertEquals("bob", rows(page, "rows").get(0).get("user_name"));
        Map<String, Object> empty = queries.drilldown(7, row("plan", row("detailObjectId", "users", "filters", Collections.singletonList(row("propertyId", "userId", "operator", "=", "value", -1)))), "alice");
        assertTrue(rows(empty, "rows").isEmpty());
        assertEquals(Arrays.asList("user_id", "user_name"), empty.get("columns"));
    }

    private Map<String, Object> config() {
        return row("objects", Collections.singletonList(row("id", "orders", "name", "订单", "table", "orders")),
                "properties", Arrays.asList(row("id", "orderId", "name", "订单编号", "column", "order_id", "objectId", "orders", "dataType", "BIGINT"),
                        row("id", "region", "name", "地区", "column", "region", "objectId", "orders", "dataType", "VARCHAR")),
                "semanticCatalog", row("metrics", Collections.singletonList(row("id", "order_count", "name", "订单数量", "aggregation", "COUNT", "propertyId", "orderId")),
                        "dimensions", Collections.singletonList(row("id", "region_dimension", "name", "地区", "propertyId", "region"))));
    }

    private Map<String, Object> row(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) result.put(String.valueOf(values[index]), values[index + 1]);
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(Map<String, Object> parent, String key) { return (List<Map<String, Object>>) parent.get(key); }

    @Configuration
    @ComponentScan("com.ontologywiki.query")
    @MapperScan("com.ontologywiki.query.mapper")
    @EnableTransactionManagement
    public static class TestConfiguration {
        @Bean public DataSource dataSource() {
            JdbcDataSource database = new JdbcDataSource();
            database.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
            database.setUser("sa");
            return database;
        }
        @Bean public ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean public CredentialService credentials() { return new CredentialService("test-key"); }
        @Bean public LlmClient llmClient() {
            return new LlmClient(null, null, new ObjectMapper()) {
                @Override public String chat(String prompt, double temperature) { throw new AssertionError("Simple aggregation must not call a model"); }
            };
        }
        @Bean public PlatformTransactionManager transactionManager(DataSource dataSource) { return new DataSourceTransactionManager(dataSource); }
        @Bean public SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/query/*Mapper.xml"));
            return factory.getObject();
        }
    }
}

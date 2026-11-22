package com.ontologywiki.query.service;

import com.ontologywiki.query.policy.QueryPolicyService;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ontologywiki.ontology.service.CredentialService;
import com.ontologywiki.query.compiler.QuerySqlCompiler;
import com.ontologywiki.query.dao.JdbcBusinessQueryDao;
import com.ontologywiki.query.domain.QueryResult;
import com.ontologywiki.query.policy.QueryReadinessService;
import com.ontologywiki.query.repository.QueryConfigurationRepository;
import com.ontologywiki.query.service.impl.QueryExecutionServiceImpl;
import com.ontologywiki.query.validation.QueryPlanValidator;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.Assert;
import org.junit.Test;

import java.util.*;

/** 合成订单库端到端验证：计划校验、参数化编译、审核准入和只读结果读取。 */
public class QueryExecutionServiceTest {
    @Test
    public void reviewedConfigExecutesWithoutEvaluationAndReturnsExpectedAggregate() throws Exception {
        JdbcDataSource db = new JdbcDataSource();
        db.setURL("jdbc:h2:mem:query-e2e;MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (java.sql.Connection connection = db.getConnection(); java.sql.Statement statement = connection.createStatement()) {
            statement.execute("create table orders (amount decimal(10,2), status varchar(20))");
            statement.execute("insert into orders values (10.00, 'PAID'), (25.50, 'PAID'), (8.00, 'CANCELLED')");
        }

        Map<String, Object> config = config();
        String json = new ObjectMapper().writeValueAsString(config);
        Repository repo = new Repository(json, db.getURL());
        QueryReadinessService readiness = new QueryReadinessService(repo, new ObjectMapper(), (id, hash) -> false);
        QueryExecutionServiceImpl service = new QueryExecutionServiceImpl(repo, readiness, new QueryPolicyService(readiness, new QueryPlanValidator(), null), new QuerySqlCompiler(), new JdbcBusinessQueryDao(new CredentialService("test-key")), new ObjectMapper());
        Map<String, Object> plan = row("metrics", Collections.singletonList(row("id", "paid_revenue")),
                "filters", Collections.singletonList(row("propertyId", "status", "operator", "=", "value", "PAID")), "limit", 10);

        QueryResult result = service.execute(7L, plan);
        Assert.assertEquals(1, result.getRows().size());
        Assert.assertEquals(new java.math.BigDecimal("35.50"), result.getRows().get(0).get("paid_revenue"));
    }

    @Test
    public void refusesExecutionBeforeReviewEvenWithPassedEvaluation() {
        Repository repo = new Repository(new ObjectMapper().valueToTree(config()).toString(), "jdbc:invalid");
        repo.proposal.put("status", "DRAFT");
        QueryReadinessService readiness = new QueryReadinessService(repo, new ObjectMapper(), (id, hash) -> true);
        QueryExecutionServiceImpl service = new QueryExecutionServiceImpl(repo, readiness, new QueryPolicyService(readiness, new QueryPlanValidator(), null), new QuerySqlCompiler(), new JdbcBusinessQueryDao(new CredentialService("test-key")), new ObjectMapper());
        try {
            service.execute(7L, row("metrics", Collections.singletonList(row("id", "paid_revenue"))));
            Assert.fail("未完成审核不应触发 JDBC");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage().contains("尚未完成审核"));
        }
    }

    @Test
    public void returnsComparisonColumnsForYearOverYearPlan() throws Exception {
        JdbcDataSource db = new JdbcDataSource();
        db.setURL("jdbc:h2:mem:query-yoy;MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (java.sql.Connection connection = db.getConnection(); java.sql.Statement statement = connection.createStatement()) {
            statement.execute("create table orders_yoy (amount decimal(10,2), created_at date)");
            statement.execute("insert into orders_yoy values (20.00, '2025-01-10'), (35.00, '2026-01-10')");
        }
        Map<String, Object> config = config();
        ((List<Map<String, Object>>) config.get("objects")).get(0).put("table", "orders_yoy");
        List<Map<String, Object>> properties = new ArrayList<>((List<Map<String, Object>>) config.get("properties"));
        properties.add(row("id", "createdAt", "objectId", "orders", "column", "created_at", "dataType", "DATE"));
        config.put("properties", properties);
        ((Map<String, Object>) config.get("querySemantics")).put("timeDimensions", Collections.singletonList(row("id", "created", "name", "创建时间", "propertyId", "createdAt", "default", true)));
        Repository repo = new Repository(new ObjectMapper().writeValueAsString(config), db.getURL());
        QueryReadinessService readiness = new QueryReadinessService(repo, new ObjectMapper(), (id, hash) -> true);
        QueryExecutionServiceImpl service = new QueryExecutionServiceImpl(repo, readiness, new QueryPolicyService(readiness, new QueryPlanValidator(), null), new QuerySqlCompiler(), new JdbcBusinessQueryDao(new CredentialService("test-key")), new ObjectMapper());
        Map<String, Object> plan = row("metrics", Collections.singletonList(row("id", "paid_revenue")), "timeDimension", "created",
                "timeRange", row("from", "2026-01-01", "to", "2026-12-31"), "timeGranularity", "YEAR",
                "comparison", row("type", "YOY", "periods", 1), "limit", 10);
        QueryResult result = service.execute(7L, plan);
        Assert.assertEquals(new java.math.BigDecimal("35.00"), result.getRows().get(0).get("current_paid_revenue"));
        Assert.assertEquals(new java.math.BigDecimal("20.00"), result.getRows().get(0).get("previous_paid_revenue"));
        Assert.assertEquals(new java.math.BigDecimal("15.00"), result.getRows().get(0).get("change_paid_revenue"));
    }

    @Test
    public void countsExternalMenusByFilteringZeroInsteadOfSummingFlags() throws Exception {
        JdbcDataSource database = new JdbcDataSource();
        database.setURL("jdbc:h2:mem:external-menu;MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (java.sql.Connection connection = database.getConnection(); java.sql.Statement statement = connection.createStatement()) {
            statement.execute("create table sys_menu (menu_id bigint primary key, is_frame int)");
            statement.execute("insert into sys_menu values (1, 0), (2, 1), (3, 0)");
        }
        Map<String, Object> config = row("objects", Collections.singletonList(row("id", "menus", "table", "sys_menu")),
                "properties", Arrays.asList(row("id", "menuId", "objectId", "menus", "column", "menu_id", "dataType", "BIGINT"),
                        row("id", "external", "objectId", "menus", "column", "is_frame", "dataType", "INT", "name", "是否为外链（0是 1否）")),
                "semanticCatalog", row("metrics", Collections.singletonList(row("id", "count", "name", "菜单数量", "propertyId", "menuId", "aggregation", "COUNT"))));
        Repository repository = new Repository(new ObjectMapper().writeValueAsString(config), database.getURL());
        QueryReadinessService readiness = new QueryReadinessService(repository, new ObjectMapper());
        QueryExecutionServiceImpl service = new QueryExecutionServiceImpl(repository, readiness, new QueryPolicyService(readiness, new QueryPlanValidator(), null), new QuerySqlCompiler(), new JdbcBusinessQueryDao(new CredentialService("test-key")), new ObjectMapper());
        Map<String, Object> plan = row("metrics", Collections.singletonList(row("id", "count")),
                "filters", Collections.singletonList(row("propertyId", "external", "operator", "=", "value", 0)));
        QueryResult result = service.execute(1, plan);
        Assert.assertEquals(2L, ((Number) result.getRows().get(0).get("count")).longValue());
        Assert.assertEquals(Collections.singletonList(0), result.getParameters());
    }

    private Map<String, Object> config() {
        return row("objects", Collections.singletonList(row("id", "orders", "table", "orders")),
                "properties", Arrays.asList(row("id", "amount", "objectId", "orders", "column", "amount", "dataType", "DECIMAL(10,2)"),
                        row("id", "status", "objectId", "orders", "column", "status", "dataType", "VARCHAR")),
                "relations", Collections.emptyList(),
                "querySemantics", row("metrics", Collections.singletonList(row("id", "paid_revenue", "name", "已支付金额", "propertyId", "amount", "aggregation", "SUM")),
                        "dimensions", Collections.singletonList(row("id", "status_dimension", "name", "状态", "propertyId", "status"))));
    }

    private Map<String, Object> row(Object... values) { Map<String, Object> map = new LinkedHashMap<>(); for (int i = 0; i < values.length; i += 2) map.put(String.valueOf(values[i]), values[i + 1]); return map; }

    private static class Repository extends QueryConfigurationRepository {
        private final Map<String, Object> proposal = new LinkedHashMap<>();
        private final Map<String, Object> source = new LinkedHashMap<>();
        Repository(String config, String url) {
            super(null);
            proposal.put("status", "COMPLETED"); proposal.put("config_json", config); proposal.put("task_id", 9L);
            source.put("db_type", "mysql"); source.put("jdbc_url", url); source.put("username", ""); source.put("password_enc", "");
        }
        @Override public Map<String, Object> findProposal(long id) { return proposal; }
        @Override public Map<String, Object> findTaskDatasource(long id) { return source; }
    }
}

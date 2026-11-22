package com.ontologywiki.query.dao;

import com.ontologywiki.query.compiler.QuerySqlCompiler;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.*;

public class JdbcBusinessQueryDaoTest {
    @Test
    public void bindsUntrustedValuesAndKeepsReadonlyLimitsAndResourceLifecycle() throws Exception {
        JdbcDataSource database = database();
        String filter = "' OR 1=1 --";
        try (Connection connection = database.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("create table orders (id int, customer varchar(100))");
            statement.execute("insert into orders values (1, 'ordinary')");
            try (PreparedStatement insert = connection.prepareStatement("insert into orders values (2, ?)")) {
                insert.setString(1, filter);
                insert.executeUpdate();
            }
        }
        List<String> calls = new ArrayList<>();
        Connection connection = database.getConnection();
        Connection observed = (Connection) observe(connection, Connection.class, calls);
        JdbcBusinessQueryDao dao = new JdbcBusinessQueryDao(null) {
            @Override protected Connection openConnection(Map<String, Object> source) { return observed; }
        };
        QuerySqlCompiler.Result compiled = new QuerySqlCompiler.Result("select id as ORDER_ID, customer from orders where customer = ?", Collections.singletonList(filter), Collections.singletonList("orders"));
        List<Map<String, Object>> rows = dao.query(Collections.emptyMap(), compiled);
        assertEquals(1, rows.size());
        assertEquals(2, ((Number) rows.get(0).get("order_id")).intValue());
        assertEquals(filter, rows.get(0).get("customer"));
        assertTrue(calls.contains("setReadOnly:[true]"));
        assertTrue(calls.contains("setQueryTimeout:[30]"));
        assertTrue(calls.contains("setMaxRows:[1000]"));
        assertTrue(calls.contains("setObject:[1, " + filter + "]"));
        assertTrue(connection.isClosed());
        assertTrue(calls.stream().filter(value -> value.startsWith("close:")).count() >= 2);
    }

    @Test
    public void closesConnectionsOnFailureWithoutExposingCredentials() throws Exception {
        Connection connection = database().getConnection();
        JdbcBusinessQueryDao dao = new JdbcBusinessQueryDao(null) {
            @Override protected Connection openConnection(Map<String, Object> source) { return connection; }
        };
        try {
            dao.query(new LinkedHashMap<>(), new QuerySqlCompiler.Result("select * from nonexistent", Collections.emptyList(), Collections.emptyList()));
            fail("Expected SQL failure");
        } catch (IllegalStateException failure) {
            assertEquals("只读查询执行失败，请检查数据源权限和网络", failure.getMessage());
            assertTrue(connection.isClosed());
        }
    }

    @Test
    public void enforcesMaximumReturnedRows() throws Exception {
        JdbcDataSource database = database();
        JdbcBusinessQueryDao dao = new JdbcBusinessQueryDao(null) {
            @Override protected Connection openConnection(Map<String, Object> source) throws java.sql.SQLException { return database.getConnection(); }
        };
        List<Map<String, Object>> rows = dao.query(Collections.emptyMap(), new QuerySqlCompiler.Result("select * from system_range(1, 1200)", Collections.emptyList(), Collections.emptyList()));
        assertEquals(1000, rows.size());
    }

    private JdbcDataSource database() {
        JdbcDataSource database = new JdbcDataSource();
        database.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        return database;
    }

    private Object observe(Object target, Class<?> type, List<String> calls) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, arguments) -> {
            calls.add(method.getName() + ":" + Arrays.toString(arguments));
            try {
                Object result = method.invoke(target, arguments);
                return result instanceof PreparedStatement ? observe(result, PreparedStatement.class, calls) : result;
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        });
    }
}

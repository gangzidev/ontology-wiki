package com.ontologywiki.query.dao;

import com.ontologywiki.ontology.service.CredentialService;
import com.ontologywiki.query.compiler.QuerySqlCompiler;
import org.springframework.stereotype.Repository;

import java.sql.*;
import java.util.*;

@Repository
public class JdbcBusinessQueryDao implements BusinessQueryDao {
    private final CredentialService credentials;

    public JdbcBusinessQueryDao(CredentialService credentials) { this.credentials = credentials; }

    @Override
    public List<Map<String, Object>> query(Map<String, Object> source, QuerySqlCompiler.Result compiled) {
        try (Connection connection = openConnection(source)) {
            connection.setReadOnly(true);
            try (PreparedStatement statement = connection.prepareStatement(compiled.getSql())) {
                statement.setQueryTimeout(30);
                List<Object> parameters = compiled.getParameters();
                for (int index = 0; index < parameters.size(); index++) statement.setObject(index + 1, parameters.get(index));
                statement.setMaxRows(1000);
                try (ResultSet result = statement.executeQuery()) {
                    List<Map<String, Object>> rows = new ArrayList<>();
                    ResultSetMetaData metadata = result.getMetaData();
                    while (result.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int column = 1; column <= metadata.getColumnCount(); column++) row.put(metadata.getColumnLabel(column).toLowerCase(Locale.ROOT), result.getObject(column));
                        rows.add(row);
                    }
                    return rows;
                }
            }
        } catch (SQLException | ClassNotFoundException failure) {
            throw new IllegalStateException("只读查询执行失败，请检查数据源权限和网络", failure);
        }
    }

    protected Connection openConnection(Map<String, Object> source) throws SQLException, ClassNotFoundException {
        String dbType = text(source.get("db_type")).toLowerCase(Locale.ROOT);
        String url = text(source.get("jdbc_url"));
        if (url.isEmpty()) url = buildUrl(dbType, source);
        if (url.isEmpty()) throw new IllegalArgumentException("数据源没有 JDBC 地址");
        Class.forName(driver(dbType));
        return DriverManager.getConnection(url, text(source.get("username")), credentials.decrypt(text(source.get("password_enc"))));
    }

    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private String driver(String dbType) {
        if ("postgres".equals(dbType) || "postgresql".equals(dbType)) return "org.postgresql.Driver";
        if ("hive".equals(dbType)) return "org.apache.hive.jdbc.HiveDriver";
        return "com.mysql.cj.jdbc.Driver";
    }

    private String buildUrl(String dbType, Map<String, Object> source) {
        String host = text(source.get("host"));
        String database = text(source.get("database_name"));
        if (host.isEmpty() || database.isEmpty()) return "";
        int port = integer(source.get("port"), "postgresql".equals(dbType) || "postgres".equals(dbType) ? 5432 : "hive".equals(dbType) ? 10000 : 3306);
        if ("postgresql".equals(dbType) || "postgres".equals(dbType)) return "jdbc:postgresql://" + host + ":" + port + "/" + database;
        if ("hive".equals(dbType)) return "jdbc:hive2://" + host + ":" + port + "/" + database;
        if ("mysql".equals(dbType)) return "jdbc:mysql://" + host + ":" + port + "/" + database + "?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai";
        return "";
    }

    private int integer(Object value, int fallback) {
        try { return value == null ? fallback : Integer.parseInt(String.valueOf(value)); }
        catch (Exception ignored) { return fallback; }
    }

}

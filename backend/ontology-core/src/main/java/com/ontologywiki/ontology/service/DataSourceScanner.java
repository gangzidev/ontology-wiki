package com.ontologywiki.ontology.service;

import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** 使用 JDBC 元数据读取表、字段、主外键和受限样例。 */
@Service
public class DataSourceScanner {
    private static final Pattern SENSITIVE = Pattern.compile("(?i)(password|passwd|token|secret|phone|mobile|telephone|email|id[_-]?card|bank[_-]?card|身份证|手机号|电话|邮箱|银行卡)");
    private final CredentialService credentials;

    public DataSourceScanner(CredentialService credentials) { this.credentials = credentials; }

    /** 只建立连接并读取数据库信息，不扫描表和样例。 */
    public Map<String, Object> testConnection(Map<String, Object> source, String password) {
        String dbType = text(source.get("db_type"), "mysql").toLowerCase(Locale.ROOT);
        if (!("mysql".equals(dbType) || "postgres".equals(dbType) || "postgresql".equals(dbType) || "hive".equals(dbType))) throw new IllegalArgumentException("暂不支持该数据库类型");
        if (password == null || password.isEmpty()) throw new IllegalArgumentException("数据库密码不能为空");
        String url = text(source.get("jdbc_url"), buildUrl(dbType, source));
        try {
            Class.forName(defaultDriver(dbType));
            try (Connection connection = DriverManager.getConnection(url, text(source.get("username"), ""), password)) {
                DatabaseMetaData metadata = connection.getMetaData();
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("databaseProduct", metadata.getDatabaseProductName());
                result.put("databaseVersion", metadata.getDatabaseProductVersion());
                result.put("connection", metadata.getURL().replaceAll("(?i)(password|pass|pwd)=[^&]*", "$1=***").replaceAll("//[^/@]+:[^/@]+@", "//***@"));
                return result;
            }
        } catch (Exception e) {
            throw new IllegalStateException("数据库连接失败，请检查地址、端口、账号密码和网络权限。", e);
        }
    }

    public Map<String, Object> scan(Map<String, Object> source, Map<String, Object> options) {
        String dbType = text(source.get("db_type"), "mysql").toLowerCase(Locale.ROOT);
        if (!("mysql".equals(dbType) || "postgres".equals(dbType) || "postgresql".equals(dbType) || "hive".equals(dbType))) throw new IllegalArgumentException("暂不支持该数据库类型");
        String url = text(source.get("jdbc_url"), buildUrl(dbType, source));
        String user = text(source.get("username"), "");
        String password = credentials.decrypt(text(source.get("password_enc"), ""));
        String driver = defaultDriver(dbType);
        int sampleLimit = integer(options.get("sampleLimit"), 0);
        sampleLimit = Math.max(0, Math.min(sampleLimit, 20));
        String schema = text(options.get("schema"), text(source.get("schema_name"), null));
        Set<String> requestedTables = strings(options.get("tables"));
        try {
            if (driver != null && !driver.isEmpty()) Class.forName(driver);
            try (Connection connection = DriverManager.getConnection(url, user, password)) {
                DatabaseMetaData metadata = connection.getMetaData();
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("databaseProduct", metadata.getDatabaseProductName());
                result.put("databaseVersion", metadata.getDatabaseProductVersion());
                result.put("connection", "jdbc:" + dbType + " / " + metadata.getURL().replaceAll("(?i)(password|pass|pwd)=[^&]*", "$1=***").replaceAll("//[^/@]+:[^/@]+@", "//***@"));
                List<Map<String, Object>> tables = new ArrayList<>();
                try (ResultSet rs = metadata.getTables(connection.getCatalog(), schema, "%", new String[] {"TABLE", "VIEW"})) {
                    while (rs.next()) {
                        String tableName = rs.getString("TABLE_NAME");
                        String tableSchema = rs.getString("TABLE_SCHEM");
                        String qualifiedName = qualifiedName(tableSchema, tableName);
                        if (!requestedTables.isEmpty() && !requestedTables.contains(qualifiedName) && !requestedTables.contains(tableName.toLowerCase(Locale.ROOT))) continue;
                        Map<String, Object> table = new LinkedHashMap<>();
                        table.put("schema", tableSchema); table.put("name", tableName);
                        table.put("type", rs.getString("TABLE_TYPE")); table.put("comment", rs.getString("REMARKS"));
                        table.put("columns", columns(metadata, connection.getCatalog(), tableSchema, tableName));
                        table.put("primaryKeys", safePrimaryKeys(metadata, connection.getCatalog(), tableSchema, tableName));
                        table.put("foreignKeys", safeForeignKeys(metadata, connection.getCatalog(), tableSchema, tableName));
                        if (sampleLimit > 0 && (requestedTables.isEmpty() || requestedTables.contains(qualifiedName) || requestedTables.contains(tableName.toLowerCase(Locale.ROOT)))) table.put("samples", samples(connection, tableName, tableSchema, sampleLimit));
                        tables.add(table);
                    }
                }
                result.put("tables", tables); result.put("tableCount", tables.size());
                return result;
            }
        } catch (Exception e) {
            throw new IllegalStateException("数据源扫描失败。请检查连接地址、账号密码、网络权限和对应数据库驱动。", e);
        }
    }

    /** 返回轻量级表目录，供分析范围选择器搜索和分页。 */
    public Map<String, Object> listTables(Map<String, Object> source, String keyword, String requestedSchema, int pageNum, int pageSize) {
        String dbType = text(source.get("db_type"), "mysql").toLowerCase(Locale.ROOT);
        if (!("mysql".equals(dbType) || "postgres".equals(dbType) || "postgresql".equals(dbType) || "hive".equals(dbType))) throw new IllegalArgumentException("暂不支持该数据库类型");
        String url = text(source.get("jdbc_url"), buildUrl(dbType, source));
        String user = text(source.get("username"), "");
        String password = credentials.decrypt(text(source.get("password_enc"), ""));
        String schemaFilter = text(requestedSchema, text(source.get("schema_name"), null));
        String normalizedKeyword = text(keyword, "").toLowerCase(Locale.ROOT);
        int safePage = Math.max(1, pageNum);
        int safeSize = Math.max(1, Math.min(pageSize, 100));
        try {
            Class.forName(defaultDriver(dbType));
            try (Connection connection = DriverManager.getConnection(url, user, password)) {
                DatabaseMetaData metadata = connection.getMetaData();
                List<Map<String, Object>> matches = new ArrayList<>();
                try (ResultSet rs = metadata.getTables(connection.getCatalog(), schemaFilter, "%", new String[] {"TABLE", "VIEW"})) {
                    while (rs.next()) {
                        String name = rs.getString("TABLE_NAME");
                        String tableSchema = rs.getString("TABLE_SCHEM");
                        String comment = rs.getString("REMARKS");
                        String searchable = (String.valueOf(name) + " " + String.valueOf(tableSchema) + " " + String.valueOf(comment)).toLowerCase(Locale.ROOT);
                        if (!normalizedKeyword.isEmpty() && !searchable.contains(normalizedKeyword)) continue;
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("schema", tableSchema);
                        item.put("name", name);
                        item.put("type", rs.getString("TABLE_TYPE"));
                        item.put("comment", comment);
                        matches.add(item);
                    }
                }
                matches.sort(Comparator.comparing(item -> qualifiedName(item.get("schema"), item.get("name"))));
                int start = Math.min((safePage - 1) * safeSize, matches.size());
                int end = Math.min(start + safeSize, matches.size());
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("rows", new ArrayList<>(matches.subList(start, end)));
                result.put("total", matches.size());
                result.put("pageNum", safePage);
                result.put("pageSize", safeSize);
                return result;
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取数据源表目录失败，请检查连接和数据库权限。", e);
        }
    }

    private List<Map<String, Object>> columns(DatabaseMetaData metadata, String catalog, String schema, String table) throws SQLException {
        List<Map<String, Object>> result = new ArrayList<>();
        try (ResultSet rs = metadata.getColumns(catalog, schema, table, "%")) {
            while (rs.next()) {
                Map<String, Object> column = new LinkedHashMap<>();
                column.put("name", rs.getString("COLUMN_NAME")); column.put("type", rs.getString("TYPE_NAME"));
                column.put("size", rs.getInt("COLUMN_SIZE")); column.put("nullable", rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls);
                column.put("default", rs.getString("COLUMN_DEF")); column.put("comment", rs.getString("REMARKS"));
                result.add(column);
            }
        }
        return result;
    }

    private List<String> keys(ResultSet rs, String name) throws SQLException {
        List<String> result = new ArrayList<>();
        try (ResultSet ignored = rs) { while (rs.next()) result.add(rs.getString(name)); }
        return result;
    }

    private List<Map<String, Object>> foreignKeys(ResultSet rs) throws SQLException {
        List<Map<String, Object>> result = new ArrayList<>();
        try (ResultSet ignored = rs) { while (rs.next()) { Map<String, Object> key = new LinkedHashMap<>(); key.put("column", rs.getString("FKCOLUMN_NAME")); key.put("referencedTable", rs.getString("PKTABLE_NAME")); key.put("referencedColumn", rs.getString("PKCOLUMN_NAME")); result.add(key); } }
        return result;
    }

    private List<String> safePrimaryKeys(DatabaseMetaData metadata, String catalog, String schema, String table) {
        try { return keys(metadata.getPrimaryKeys(catalog, schema, table), "COLUMN_NAME"); } catch (SQLException e) { return Collections.emptyList(); }
    }
    private List<Map<String, Object>> safeForeignKeys(DatabaseMetaData metadata, String catalog, String schema, String table) {
        try { return foreignKeys(metadata.getImportedKeys(catalog, schema, table)); } catch (SQLException e) { return Collections.emptyList(); }
    }

    private List<Map<String, Object>> samples(Connection connection, String table, String schema, int limit) throws SQLException {
        List<Map<String, Object>> result = new ArrayList<>();
        DatabaseMetaData metadata = connection.getMetaData();
        String qualified = schema == null || schema.trim().isEmpty() ? quote(metadata, table) : quote(metadata, schema) + "." + quote(metadata, table);
        String sql = "select * from " + qualified + " limit " + limit;
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            ResultSetMetaData meta = rs.getMetaData();
            while (rs.next()) { Map<String, Object> row = new LinkedHashMap<>(); for (int i=1; i<=meta.getColumnCount(); i++) { String name=meta.getColumnLabel(i); Object value=rs.getObject(i); row.put(name, SENSITIVE.matcher(name).find() ? "[REDACTED]" : (value == null ? null : String.valueOf(value).substring(0, Math.min(String.valueOf(value).length(), 500)))); } result.add(row); }
        }
        return result;
    }

    private String quote(DatabaseMetaData metadata, String identifier) throws SQLException {
        String q = metadata.getIdentifierQuoteString(); q = q == null ? "" : q.trim();
        return q.isEmpty() ? identifier.replace("\"", "") : q + identifier.replace(q, "") + q;
    }
    private String qualifiedName(Object schema, Object table) {
        String name = String.valueOf(table).toLowerCase(Locale.ROOT);
        String owner = schema == null ? "" : String.valueOf(schema).trim().toLowerCase(Locale.ROOT);
        return owner.isEmpty() ? name : owner + "." + name;
    }
    private String buildUrl(String dbType, Map<String,Object> source) { String host=text(source.get("host"), "localhost"); int port=integer(source.get("port"), defaultPort(dbType)); String db=text(source.get("database_name"), ""); if ("postgresql".equals(dbType)||"postgres".equals(dbType)) return "jdbc:postgresql://"+host+":"+port+"/"+db; if ("hive".equals(dbType)) return "jdbc:hive2://"+host+":"+port+"/"+db; return "jdbc:mysql://"+host+":"+port+"/"+db+"?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai"; }
    private String defaultDriver(String type) { if ("postgresql".equals(type)||"postgres".equals(type)) return "org.postgresql.Driver"; if ("hive".equals(type)) return "org.apache.hive.jdbc.HiveDriver"; return "com.mysql.cj.jdbc.Driver"; }
    private int defaultPort(String type) { if ("postgresql".equals(type)||"postgres".equals(type)) return 5432; if ("hive".equals(type)) return 10000; return 3306; }
    private String text(Object value, String fallback) { return value == null || String.valueOf(value).trim().isEmpty() ? fallback : String.valueOf(value).trim(); }
    private int integer(Object value, int fallback) { try { return value == null ? fallback : Integer.parseInt(String.valueOf(value)); } catch (Exception e) { return fallback; } }
    private Set<String> strings(Object value) { Set<String> result=new HashSet<>(); if (value instanceof Collection) for (Object item:(Collection<?>)value) result.add(String.valueOf(item).trim().toLowerCase(Locale.ROOT)); return result; }
}

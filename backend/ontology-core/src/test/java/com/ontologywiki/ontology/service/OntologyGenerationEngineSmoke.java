package com.ontologywiki.ontology.service;

import java.util.*;

/** Run with assertions enabled after compiling ontology-core test sources. */
public final class OntologyGenerationEngineSmoke {
    public static void main(String[] args) {
        OntologyGenerationEngine engine = new OntologyGenerationEngine();
        Map<String, Object> customer = table("customers", Arrays.asList("id"),
                column("id", "BIGINT", false), column("name", "VARCHAR", false));
        customer.put("schema", "sales"); customer.put("comment", "客户");
        customer.put("samples", Arrays.asList(sample("id", 1, "name", "Alice"), sample("id", 2, "name", "Bob")));
        Map<String, Object> order = table("orders", Arrays.asList("id"),
                column("id", "BIGINT", false), column("customer_id", "BIGINT", false));
        order.put("schema", "sales");
        order.put("foreignKeys", Collections.singletonList(link("customer_id", "customers", "id")));
        Map<String, Object> membership = table("customer_group", Arrays.asList("customer_id", "group_id"),
                column("customer_id", "BIGINT", false), column("group_id", "BIGINT", false));
        membership.put("foreignKeys", Arrays.asList(link("customer_id", "customers", "id"), link("group_id", "groups", "id")));
        Map<String, Object> group = table("groups", Arrays.asList("id"), column("id", "BIGINT", false));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("tables", Arrays.asList(customer, order, membership, group));

        Map<String, Object> result = engine.generate(schema, Collections.<Map<String, Object>>emptyList(),
                Collections.singletonList(rule("订单状态必须是有效值")),
                Arrays.asList("select * from orders join customers on orders.customer_id=customers.id", "update customers set name='x'"),
                Collections.<Integer>emptySet());
        require(size(result, "objects") == 5, "association table should be reified as an object");
        require(size(result, "relations") == 6, "foreign-key, reified, and direct many-to-many relations expected");
        require(size(result, "validationRules") == 7, "NOT NULL rules expected for seven columns");
        require(size(result, "dataProfiles") == 1, "sample profile expected for the sampled table");
        require("客户".equals(rows(result, "objects").get(0).get("name")), "table comment should become display name");
        require(!String.valueOf(result).contains("Alice") && !String.valueOf(result).contains("Bob"), "sample values must not be persisted in candidates");
        List<Map<String, Object>> actions = rows(result, "actions");
        require(actions.size() == 3, "read actions for two tables and one update expected");
        require(actions.stream().filter(x -> "UPDATE".equals(x.get("operation"))).count() == 1,
                "only one explicit update action expected");
        require(size(result, "serviceFlows") == actions.size(), "every action should have a service flow candidate");
        require(size(result, "permissionCandidates") == actions.size(), "every action should have a permission candidate");
        require(((Map<?, ?>) result.get("targetConfig")).containsKey("permissions"), "generic target config should include permissions");
        require(Boolean.TRUE.equals(rows(result, "logics").get(0).get("executable")), "simple business rule should be expressionized");
        require(Boolean.TRUE.equals(((Map<?, ?>) result.get("consistencyReport")).get("isConsistent")),
                "consistency report should pass");
    }

    private static Map<String, Object> table(String name, List<String> pks, Map<String, Object>... columns) {
        Map<String, Object> table = new LinkedHashMap<>();
        table.put("name", name); table.put("comment", name); table.put("primaryKeys", pks);
        table.put("columns", Arrays.asList(columns)); table.put("foreignKeys", Collections.emptyList());
        return table;
    }

    private static Map<String, Object> column(String name, String type, boolean nullable) {
        Map<String, Object> column = new LinkedHashMap<>();
        column.put("name", name); column.put("type", type); column.put("nullable", nullable);
        return column;
    }

    private static Map<String, Object> link(String column, String table, String targetColumn) {
        Map<String, Object> link = new LinkedHashMap<>();
        link.put("column", column); link.put("referencedTable", table); link.put("referencedColumn", targetColumn);
        return link;
    }

    private static Map<String, Object> sample(String key1, Object value1, String key2, Object value2) {
        Map<String, Object> row = new LinkedHashMap<>(); row.put(key1, value1); row.put(key2, value2); return row;
    }

    private static Map<String, Object> rule(String content) { Map<String, Object> rule = new LinkedHashMap<>(); rule.put("title", "订单规则"); rule.put("content", content.replace("有效值", "status = 'VALID'")); rule.put("sourceId", "rule-1"); return rule; }

    @SuppressWarnings("unchecked") private static List<Map<String, Object>> rows(Map<String, Object> result, String key) {
        return (List<Map<String, Object>>) result.get(key);
    }
    private static int size(Map<String, Object> result, String key) { return rows(result, key).size(); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}

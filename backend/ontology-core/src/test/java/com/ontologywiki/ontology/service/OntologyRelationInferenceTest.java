package com.ontologywiki.ontology.service;

import org.junit.Assert;
import org.junit.Test;
import java.util.*;

public class OntologyRelationInferenceTest {
    @Test
    public void existingForeignKeyGenerationSmokeStillPasses() {
        OntologyGenerationEngineSmoke.main(new String[0]);
    }

    @Test
    public void infersUserRoleManyToManyFromCompositePrimaryKeyAndNames() {
        Map<String, Object> user = table("sys_user", Collections.singletonList("user_id"), column("user_id", "BIGINT"));
        Map<String, Object> role = table("sys_role", Collections.singletonList("role_id"), column("role_id", "BIGINT"));
        Map<String, Object> userRole = table("sys_user_role", Arrays.asList("user_id", "role_id"), column("user_id", "BIGINT"), column("role_id", "BIGINT"));
        Map<String, Object> result = generate(user, role, userRole);

        List<Map<String, Object>> relations = rows(result, "relations");
        Map<String, Object> manyToMany = relations.stream().filter(r -> "MANY_TO_MANY".equals(r.get("cardinality"))).findFirst().orElse(null);
        Assert.assertNotNull("direct user-role relation should be available", manyToMany);
        Assert.assertEquals("sys_user_role", manyToMany.get("viaTable"));
        Assert.assertEquals("COMPOSITE_PRIMARY_KEY_JOIN_TABLE", manyToMany.get("inferenceMethod"));
        Assert.assertEquals("NEEDS_REVIEW", manyToMany.get("status"));
        Assert.assertTrue(((Number) manyToMany.get("confidence")).doubleValue() >= 0.85);
        Assert.assertTrue(String.valueOf(manyToMany.get("evidence")).contains("user_id"));
        Assert.assertTrue(String.valueOf(manyToMany.get("evidence")).contains("role_id"));
        Assert.assertTrue(relations.stream().anyMatch(r -> "sys_user_role".equals(objectTable(result, String.valueOf(r.get("sourceObjectId")))) && "sys_user".equals(objectTable(result, String.valueOf(r.get("targetObjectId"))))));
        Assert.assertTrue(relations.stream().anyMatch(r -> "sys_user_role".equals(objectTable(result, String.valueOf(r.get("sourceObjectId")))) && "sys_role".equals(objectTable(result, String.valueOf(r.get("targetObjectId"))))));
    }

    @Test
    public void doesNotInferWhenMultipleTablesMatchFieldStem() {
        Map<String, Object> user = table("sys_user", Collections.singletonList("user_id"), column("user_id", "BIGINT"));
        Map<String, Object> auditUser = table("audit_user", Collections.singletonList("user_id"), column("user_id", "BIGINT"));
        Map<String, Object> event = table("login_event", Collections.singletonList("id"), column("id", "BIGINT"), column("user_id", "BIGINT"));
        Map<String, Object> result = generate(user, auditUser, event);
        Assert.assertTrue(rows(result, "relations").isEmpty());
        Assert.assertTrue(rows(result, "warnings").stream().anyMatch(w -> "AMBIGUOUS_RELATION_INFERENCE".equals(w.get("code"))));
    }

    @Test
    public void doesNotInferWhenKeyTypesDoNotMatch() {
        Map<String, Object> user = table("sys_user", Collections.singletonList("user_id"), column("user_id", "BIGINT"));
        Map<String, Object> event = table("login_event", Collections.singletonList("id"), column("id", "BIGINT"), column("user_id", "VARCHAR"));
        Assert.assertTrue(rows(generate(user, event), "relations").isEmpty());
    }

    private Map<String, Object> generate(Map<String, Object>... tables) {
        Map<String, Object> schema = new LinkedHashMap<>(); schema.put("tables", Arrays.asList(tables));
        return new OntologyGenerationEngine().generate(schema, Collections.<Map<String, Object>>emptyList(), Collections.<Map<String, Object>>emptyList(), Collections.<String>emptyList(), Collections.<Integer>emptySet());
    }

    private Map<String, Object> table(String name, List<String> keys, Map<String, Object>... columns) {
        Map<String, Object> table = new LinkedHashMap<>(); table.put("name", name); table.put("primaryKeys", keys); table.put("columns", Arrays.asList(columns)); table.put("foreignKeys", Collections.emptyList()); return table;
    }

    private Map<String, Object> column(String name, String type) {
        Map<String, Object> column = new LinkedHashMap<>(); column.put("name", name); column.put("type", type); column.put("nullable", false); return column;
    }

    @SuppressWarnings("unchecked") private List<Map<String, Object>> rows(Map<String, Object> result, String key) { return (List<Map<String, Object>>) result.get(key); }

    private String objectTable(Map<String, Object> result, String objectId) {
        for (Map<String, Object> object : rows(result, "objects")) if (objectId.equals(object.get("id"))) return String.valueOf(object.get("table"));
        return "";
    }
}

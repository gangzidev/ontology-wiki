package com.ontologywiki.ontology.service;

import org.junit.Assert;
import org.junit.Test;

import java.util.*;

public class ExternalOntologyConfigAdapterTest {
    @Test
    public void importsCommonExternalAliasesAndExportsSemanticCatalog() {
        Map<String, Object> external = row("entities", Collections.singletonList(row("entityId", "orders", "label", "订单", "physicalTable", "orders")),
                "attributes", Collections.singletonList(row("fieldId", "amount", "entityId", "orders", "physicalColumn", "amount")),
                "metrics", Collections.singletonList(row("id", "revenue", "label", "收入", "aggregate", "SUM")));
        Map<String, Object> imported = new ExternalOntologyConfigAdapter().importConfig(external);
        Map<String, Object> object = (Map<String, Object>) ((List<?>) imported.get("objects")).get(0);
        Map<String, Object> property = (Map<String, Object>) ((List<?>) imported.get("properties")).get(0);
        Map<String, Object> metric = (Map<String, Object>) ((List<?>) ((Map<?, ?>) imported.get("semanticCatalog")).get("metrics")).get(0);
        Assert.assertEquals("orders", object.get("id"));
        Assert.assertEquals("amount", property.get("column"));
        Assert.assertEquals("SUM", metric.get("aggregation"));
        Map<String, Object> exported = new ExternalOntologyConfigAdapter().exportConfig(imported);
        Assert.assertTrue(exported.containsKey("semanticCatalog"));
        Assert.assertEquals("revenue", ((Map<?, ?>) ((List<?>) ((Map<?, ?>) exported.get("semanticCatalog")).get("metrics")).get(0)).get("id"));
    }

    @Test
    public void importsExplicitManyToManyJoinColumns() {
        Map<String, Object> external = row("relations", Collections.singletonList(row(
                "id", "user-role", "source", "users", "target", "roles", "sourceField", "user_id", "targetField", "role_id",
                "sourceKeyField", "id", "targetKeyField", "id", "middleTable", "user_role",
                "middleSourceField", "user_id", "middleTargetField", "role_id")));
        Map<String, Object> imported = new ExternalOntologyConfigAdapter().importConfig(external);
        Map<?, ?> relation = (Map<?, ?>) ((List<?>) imported.get("relations")).get(0);
        Assert.assertEquals("id", relation.get("sourceKeyColumn"));
        Assert.assertEquals("user_id", relation.get("viaSourceColumn"));
        Assert.assertEquals("role_id", relation.get("viaTargetColumn"));
    }

    private static Map<String, Object> row(Object... values) { Map<String, Object> result = new LinkedHashMap<>(); for (int i = 0; i < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]); return result; }
}

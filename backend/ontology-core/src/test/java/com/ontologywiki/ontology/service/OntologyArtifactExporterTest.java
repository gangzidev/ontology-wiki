package com.ontologywiki.ontology.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.util.*;

public class OntologyArtifactExporterTest {
    private final OntologyArtifactExporter exporter = new OntologyArtifactExporter();

    @Test
    public void rdfContainsOntologyObjectsPropertiesAndRelations() {
        OntologyArtifactExporter.Artifact artifact = exporter.rdf(config(), "42", "订单本体", "business_db");
        String rdf = new String(artifact.getBytes(), java.nio.charset.StandardCharsets.UTF_8);
        Assert.assertTrue(artifact.getFilename().endsWith(".owl"));
        Assert.assertTrue(rdf.contains("a owl:Class"));
        Assert.assertTrue(rdf.contains("a owl:DatatypeProperty"));
        Assert.assertTrue(rdf.contains("a owl:ObjectProperty"));
        Assert.assertTrue(rdf.contains("ex:hasPrimaryKey"));
        Assert.assertTrue(rdf.contains("ex:domainAttribute"));
        Assert.assertTrue(rdf.contains("ex:rangeAttribute"));
        Assert.assertTrue(rdf.contains("ex:confidence 0.8"));
    }

    @Test
    public void engineeringTarContainsPlatformProjectFilesAndReviewCandidates() throws Exception {
        OntologyArtifactExporter.Artifact artifact = exporter.engineeringProject(config(), "42", "OrderOntology", "admin", "business_db", "public", "postgresql");
        Set<String> names = new HashSet<>();
        Map<String, Object> ontology = null;
        Map<String, Object> relation = null;
        try (TarArchiveInputStream tar = new TarArchiveInputStream(new ByteArrayInputStream(artifact.getBytes()), "UTF-8")) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextTarEntry()) != null) {
                names.add(entry.getName());
                if (entry.getName().endsWith("/ontology.json")) ontology = new ObjectMapper().readValue(tar, Map.class);
                if (entry.getName().matches(".*/relations/.*\\.json")) relation = new ObjectMapper().readValue(tar, Map.class);
            }
        }
        Assert.assertTrue(artifact.getFilename().endsWith(".tar"));
        Assert.assertNotNull(ontology);
        Assert.assertTrue(names.stream().anyMatch(name -> name.matches(".*/objects/.*\\.json")));
        Assert.assertTrue(names.stream().anyMatch(name -> name.matches(".*/relations/.*\\.json")));
        Assert.assertTrue(names.stream().anyMatch(name -> name.matches(".*/logic/.*\\.json")));
        Assert.assertTrue(names.stream().anyMatch(name -> name.endsWith("/review/candidate-review.json")));
        Assert.assertEquals("2", String.valueOf(((Map<?, ?>) ontology.get("stats")).get("objects")));
        Map<?, ?> tag = (Map<?, ?>) ((List<?>) relation.get("tags")).get(0);
        Assert.assertNotEquals(tag.get("id"), tag.get("link_type_tag_id"));
    }

    private Map<String, Object> config() {
        Map<String, Object> object = row("id", "orders", "name", "订单", "technicalName", "orders", "table", "orders", "schema", "public", "description", "订单主表");
        Map<String, Object> customer = row("id", "customers", "name", "客户", "technicalName", "customers", "table", "customers", "schema", "public");
        Map<String, Object> orderId = row("id", "order-id", "objectId", "orders", "name", "订单编号", "column", "id", "technicalName", "id", "dataType", "BIGINT", "isPrimaryKey", true);
        Map<String, Object> customerId = row("id", "customer-id", "objectId", "orders", "name", "客户编号", "column", "customer_id", "technicalName", "customer_id", "dataType", "BIGINT");
        Map<String, Object> customerPk = row("id", "customer-pk", "objectId", "customers", "name", "客户编号", "column", "id", "technicalName", "id", "dataType", "BIGINT", "isPrimaryKey", true);
        Map<String, Object> relation = row("id", "order-customer", "name", "订单属于客户", "sourceObjectId", "orders", "targetObjectId", "customers", "sourceColumn", "customer_id", "targetColumn", "id", "evidence", "字段映射候选", "confidence", 0.8);
        Map<String, Object> logic = row("id", "check-order", "name", "检查订单", "technicalName", "check_order", "objectId", "orders", "conditionText", "检查订单状态");
        Map<String, Object> action = row("id", "cancel-order", "name", "取消订单", "technicalName", "cancel_order", "objectId", "orders", "operation", "UPDATE", "transactional", true);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("objects", Arrays.asList(object, customer));
        config.put("properties", Arrays.asList(orderId, customerId, customerPk));
        config.put("identitySpecs", Arrays.asList(row("objectId", "orders", "columns", Collections.singletonList("id")), row("objectId", "customers", "columns", Collections.singletonList("id"))));
        config.put("relations", Collections.singletonList(relation));
        config.put("logics", Collections.singletonList(logic));
        config.put("actions", Collections.singletonList(action));
        return config;
    }

    private Map<String, Object> row(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]);
        return result;
    }
}

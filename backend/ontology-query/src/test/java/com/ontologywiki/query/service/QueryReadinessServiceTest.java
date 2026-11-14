package com.ontologywiki.query.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Assert;
import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class QueryReadinessServiceTest {
    @Test
    public void incompleteOrUnreviewedConfigCannotEnterEvaluation() {
        QueryConfigurationRepositoryStub repository = new QueryConfigurationRepositoryStub("DRAFT", "{}");
        QueryReadinessService.Readiness result = new QueryReadinessService(repository, new ObjectMapper()).check(1L);
        Assert.assertFalse(result.isEligibleForEvaluation());
        Assert.assertEquals(4, result.getReasons().size());
    }

    @Test
    public void reviewedConfigNeedsMetricsDimensionsAndRelations() {
        String config = completeConfig();
        QueryReadinessService.Readiness result = new QueryReadinessService(new QueryConfigurationRepositoryStub("COMPLETED", config), new ObjectMapper()).check(1L);
        Assert.assertTrue("语义完整的配置可以进入评测", result.isEligibleForEvaluation());
        Assert.assertFalse("配置文件里的自报评测结果不能打开查询", result.isQueryEnabled());
        Assert.assertTrue(result.getReasons().contains("尚未通过标准问答评测"));
    }

    @Test
    public void validatorOpensOnlyWhenServerSideEvaluationPassed() throws Exception {
        String config = completeConfig();
        QueryConfigurationValidator validator = new QueryConfigurationValidator();
        Assert.assertTrue(validator.validate("COMPLETED", new ObjectMapper().readTree(config), true).isEmpty());
    }

    @Test
    public void serverSidePassedEvaluationEnablesQuery() {
        String config = completeConfig();
        QueryConfigurationRepositoryStub repository = new QueryConfigurationRepositoryStub("COMPLETED", config);
        QueryReadinessService service = new QueryReadinessService(repository, new ObjectMapper(), (proposalId, hash) -> true);
        QueryReadinessService.Readiness result = service.check(1L);
        Assert.assertTrue(result.isEligibleForEvaluation());
        Assert.assertTrue(result.isQueryEnabled());
        Assert.assertTrue(result.getReasons().isEmpty());
    }

    @Test
    public void mergesNewCatalogWithLegacySemanticsDuringReadiness() throws Exception {
        String config = completeConfig().replace("\"querySemantics\":{", "\"semanticCatalog\":{\"metrics\":[{\"id\":\"m1\",\"name\":\"金额\",\"propertyId\":\"p1\",\"aggregation\":\"SUM\"}]},\"querySemantics\":{");
        QueryConfigurationValidator validator = new QueryConfigurationValidator();
        Assert.assertTrue(validator.validate("COMPLETED", new ObjectMapper().readTree(config), true).isEmpty());
    }

    @Test
    public void acceptsAggregateComputedMetricFormula() throws Exception {
        String config = completeConfig().replace("\"dimensions\":[", "\"metrics\":[{\"id\":\"m2\",\"name\":\"金额合计\",\"type\":\"COMPUTED\",\"formula\":{\"operator\":\"SUM\",\"left\":{\"propertyId\":\"p1\"}}}],\"dimensions\":[");
        QueryConfigurationValidator validator = new QueryConfigurationValidator();
        Assert.assertTrue(validator.validate("COMPLETED", new ObjectMapper().readTree(config), true).isEmpty());
    }

    @Test
    public void acceptsManyToManyRelationWithPhysicalJoinTableObject() throws Exception {
        String config = completeConfig().replace("\"relations\":[{\"id\":\"r1\",\"sourceObjectId\":\"o1\",\"targetObjectId\":\"o2\",\"sourceColumn\":\"region_id\",\"targetColumn\":\"id\"}]",
                "\"relations\":[{\"id\":\"r1\",\"sourceObjectId\":\"o1\",\"targetObjectId\":\"o2\",\"sourceKeyColumn\":\"region_id\",\"targetKeyColumn\":\"id\",\"viaObjectId\":\"join1\",\"viaTable\":\"join_table\",\"viaSourceColumn\":\"o1_id\",\"viaTargetColumn\":\"o2_id\"}]")
                .replace("{\"id\":\"o1\"},{\"id\":\"o2\"}", "{\"id\":\"o1\"},{\"id\":\"o2\"},{\"id\":\"join1\"}");
        QueryConfigurationValidator validator = new QueryConfigurationValidator();
        Assert.assertTrue(validator.validate("COMPLETED", new ObjectMapper().readTree(config), true).isEmpty());
    }

    private String completeConfig() {
        return "{\"objects\":[{\"id\":\"o1\"},{\"id\":\"o2\"}],"
                + "\"properties\":[{\"id\":\"p1\",\"objectId\":\"o1\",\"column\":\"amount\",\"dataType\":\"DECIMAL(12,2)\"},{\"id\":\"p2\",\"objectId\":\"o1\",\"column\":\"region_id\"},{\"id\":\"p3\",\"objectId\":\"o2\",\"column\":\"id\"}],"
                + "\"querySemantics\":{\"metrics\":[{\"id\":\"m1\",\"name\":\"金额\",\"propertyId\":\"p1\",\"aggregation\":\"SUM\"}],"
                + "\"dimensions\":[{\"id\":\"d1\",\"name\":\"地区\",\"propertyId\":\"p2\"}],"
                + "\"relations\":[{\"id\":\"r1\",\"sourceObjectId\":\"o1\",\"targetObjectId\":\"o2\",\"sourceColumn\":\"region_id\",\"targetColumn\":\"id\"}]}}";
    }

    private static class QueryConfigurationRepositoryStub extends com.ontologywiki.query.repository.QueryConfigurationRepository {
        private final Map<String, Object> row = new HashMap<>();
        QueryConfigurationRepositoryStub(String status, String config) {
            super(null);
            row.put("status", status); row.put("config_json", config);
        }
        @Override public Map<String, Object> findProposal(long proposalId) { return row; }
    }
}

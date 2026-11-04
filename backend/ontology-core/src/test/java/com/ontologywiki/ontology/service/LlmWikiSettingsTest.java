package com.ontologywiki.ontology.service;

import com.ontologywiki.ontology.config.OntologyIntegrationProperties;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

public class LlmWikiSettingsTest {
    @Test
    public void settingsResponseNeverIncludesApiKey() {
        OntologyIntegrationProperties properties = new OntologyIntegrationProperties();
        properties.getLlmWiki().setApiKey("secret-value");
        LlmWikiClient client = client(properties);

        Map<String,Object> settings = client.modelSettings();

        Assert.assertEquals(Boolean.TRUE, settings.get("apiKeyConfigured"));
        Assert.assertFalse(settings.containsKey("apiKey"));
        Assert.assertFalse(settings.containsValue("secret-value"));
    }

    @Test
    public void invalidApiAddressIsRejectedBeforeDatabaseAccess() {
        LlmWikiClient client = client(new OntologyIntegrationProperties());
        try {
            java.util.Map<String,Object> input = new java.util.HashMap<>(); input.put("apiBaseUrl", "file:///etc/passwd"); input.put("model", "test-model");
            client.saveModelSettings(input, "tester");
            Assert.fail("Invalid model API address should be rejected.");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("http 或 https"));
        }
    }

    @Test
    public void analysisContextKeepsCandidateFieldsAndTypesReadable() {
        LlmWikiClient client = client(new OntologyIntegrationProperties());
        String context = client.analysisCandidateContext("{\"objects\":[{\"name\":\"订单\",\"table\":\"orders\"}],\"properties\":[{\"name\":\"订单编码\",\"column\":\"code\",\"dataType\":\"BIGINT\",\"objectId\":\"object_orders\"}]}");

        Assert.assertTrue(context.contains("对象"));
        Assert.assertTrue(context.contains("字段"));
        Assert.assertTrue(context.contains("column=code"));
        Assert.assertTrue(context.contains("dataType=BIGINT"));
    }

    @Test
    public void analysisContextKeepsExplicitFieldEvenWhenLongListIsClipped() {
        LlmWikiClient client = client(new OntologyIntegrationProperties());
        StringBuilder config = new StringBuilder("{\"objects\":[{\"id\":\"object_sys_role\",\"name\":\"角色信息表\",\"table\":\"sys_role\"}],\"properties\":[");
        for (int i = 0; i < 1200; i++) {
            if (i > 0) config.append(',');
            config.append("{\"id\":\"field-").append(i).append("\",\"name\":\"字段").append(i)
                    .append("\",\"column\":\"column_").append(i).append("\",\"dataType\":\"VARCHAR\",\"objectId\":\"object_sys_role\"}");
        }
        config.append(",{\"id\":\"property-role-name\",\"name\":\"角色名称\",\"column\":\"role_name\",\"dataType\":\"VARCHAR\",\"objectId\":\"object_sys_role\"}]}");

        String context = client.analysisCandidateContext(config.toString(), "用户：角色信息表 role_name 字段是什么？\n本轮问题：把它改为 TEXT");

        Assert.assertTrue(context.contains("相关字段候选："));
        Assert.assertTrue(context.contains("id=property-role-name"));
        Assert.assertTrue(context.contains("table=sys_role"));
        Assert.assertTrue(context.contains("column=role_name"));
        Assert.assertFalse(context.contains("id=field-0"));
        Assert.assertTrue(context.length() < 1000);
    }

    @Test
    public void duplicateColumnNamesRequireTableToDisambiguate() {
        AnalysisCandidateResolver resolver = new AnalysisCandidateResolver();
        Map<String,Object> config = twoSameNameFields();

        AnalysisCandidateResolver.Result ambiguous = resolver.resolve(config, "name 字段", null);
        AnalysisCandidateResolver.Result exact = resolver.resolve(config, "sys_role.name", null);
        AnalysisCandidateResolver.Result adjacentChinese = resolver.resolve(config, "sys_role.name字段", null);
        AnalysisCandidateResolver.Result chinese = resolver.resolve(config, "角色信息表里的名称字段", null);

        Assert.assertTrue(ambiguous.ambiguous);
        Assert.assertNull(ambiguous.unique);
        Assert.assertEquals("role-name", exact.unique.id);
        Assert.assertEquals("role-name", adjacentChinese.unique.id);
        Assert.assertEquals("role-name", chinese.unique.id);
    }

    @Test
    public void followUpQuestionUsesOnlyValidatedSessionFocus() {
        AnalysisCandidateResolver resolver = new AnalysisCandidateResolver();
        Map<String,Object> config = twoSameNameFields();
        AnalysisCandidateResolver.Result first = resolver.resolve(config, "sys_role.name", null);
        Map<String,Object> focus = new java.util.LinkedHashMap<>(); focus.put("category", "properties"); focus.put("id", first.unique.id);

        AnalysisCandidateResolver.Result followUp = resolver.resolve(config, "把它改成 TEXT", focus);
        AnalysisCandidateResolver.Result unrelated = resolver.resolve(config, "介绍一下任务状态", focus);

        Assert.assertTrue(followUp.usedFocus);
        Assert.assertEquals("role-name", followUp.unique.id);
        Assert.assertNull(unrelated.unique);
    }

    @Test
    public void fuzzyCandidateRecallNeverAutoSelectsAField() {
        AnalysisCandidateResolver.Result fuzzy = new AnalysisCandidateResolver().resolve(similarFieldsConfig(), "角色的名称字段", null);

        Assert.assertTrue(fuzzy.ambiguous);
        Assert.assertNull(fuzzy.unique);
        Assert.assertEquals(1, fuzzy.matches.size());
        Assert.assertEquals("role-name", fuzzy.matches.get(0).id);
    }

    @Test
    public void explicitTypeRequestCreatesOneWhitelistedFieldChange() {
        LlmWikiClient client = client(new OntologyIntegrationProperties());
        AnalysisCandidateResolver.Candidate candidate = new AnalysisCandidateResolver().resolve(twoSameNameFields(), "sys_role.name", null).unique;

        java.util.List<Map<String,Object>> changes = client.analysisTypeChange("角色名称字段类型应该是 TEXT 类型", candidate);

        Assert.assertEquals(1, changes.size());
        Assert.assertEquals("role-name", changes.get(0).get("id"));
        Assert.assertEquals("dataType", changes.get(0).get("field"));
        Assert.assertEquals("TEXT", changes.get(0).get("value"));
    }

    @Test
    public void analysisCorrectionUpdatesOnlyAnExistingWhitelistedField() {
        LlmWikiClient client = client(new OntologyIntegrationProperties());
        Map<String,Object> property = new java.util.LinkedHashMap<>();
        property.put("id", "route-address"); property.put("name", "路由地址"); property.put("dataType", "BIGINT"); property.put("column", "route");
        Map<String,Object> base = new java.util.LinkedHashMap<>(); base.put("properties", java.util.Collections.singletonList(property));
        Map<String,Object> correction = new java.util.LinkedHashMap<>(); correction.put("category", "properties"); correction.put("id", "route-address"); correction.put("field", "dataType"); correction.put("value", "text");

        java.util.List<Map<String,Object>> changes = client.normalizeAnalysisChanges(base, java.util.Collections.singletonList(correction), true);
        Map<String,Object> updated = client.applyAnalysisChanges(base, changes);
        Map<?,?> updatedProperty = (Map<?,?>) ((java.util.List<?>) updated.get("properties")).get(0);

        Assert.assertEquals("text", updatedProperty.get("dataType"));
        Assert.assertEquals("route", updatedProperty.get("column"));
        correction.put("id", "unknown-property");
        try {
            client.normalizeAnalysisChanges(base, java.util.Collections.singletonList(correction), true);
            Assert.fail("Corrections must target an existing candidate.");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("现有结构"));
        }
    }

    private LlmWikiClient client(OntologyIntegrationProperties properties) {
        JdbcTemplate jdbc = new JdbcTemplate() {
            @Override public java.util.List<Map<String,Object>> queryForList(String sql, Object... args) {
                throw new AssertionError("Unexpected database access");
            }
        };
        return new LlmWikiClient(properties, new RestTemplate(), jdbc, new CredentialService("test-key"));
    }

    private Map<String,Object> twoSameNameFields() {
        Map<String,Object> role = new java.util.LinkedHashMap<>(); role.put("id", "role-object"); role.put("name", "角色信息表"); role.put("table", "sys_role");
        Map<String,Object> user = new java.util.LinkedHashMap<>(); user.put("id", "user-object"); user.put("name", "用户信息表"); user.put("table", "sys_user");
        Map<String,Object> roleName = new java.util.LinkedHashMap<>(); roleName.put("id", "role-name"); roleName.put("name", "名称"); roleName.put("column", "name"); roleName.put("objectId", "role-object"); roleName.put("dataType", "VARCHAR");
        Map<String,Object> userName = new java.util.LinkedHashMap<>(); userName.put("id", "user-name"); userName.put("name", "名称"); userName.put("column", "name"); userName.put("objectId", "user-object"); userName.put("dataType", "VARCHAR");
        Map<String,Object> config = new java.util.LinkedHashMap<>(); config.put("objects", java.util.Arrays.asList(role, user)); config.put("properties", java.util.Arrays.asList(roleName, userName));
        return config;
    }

    private Map<String,Object> similarFieldsConfig() {
        Map<String,Object> role = new java.util.LinkedHashMap<>(); role.put("id", "role-object"); role.put("name", "角色信息表"); role.put("table", "sys_role");
        Map<String,Object> user = new java.util.LinkedHashMap<>(); user.put("id", "user-object"); user.put("name", "用户信息表"); user.put("table", "sys_user");
        Map<String,Object> roleName = new java.util.LinkedHashMap<>(); roleName.put("id", "role-name"); roleName.put("name", "角色名称"); roleName.put("column", "role_name"); roleName.put("objectId", "role-object");
        Map<String,Object> userName = new java.util.LinkedHashMap<>(); userName.put("id", "user-name"); userName.put("name", "用户名称"); userName.put("column", "user_name"); userName.put("objectId", "user-object");
        Map<String,Object> config = new java.util.LinkedHashMap<>(); config.put("objects", java.util.Arrays.asList(role, user)); config.put("properties", java.util.Arrays.asList(roleName, userName));
        return config;
    }
}

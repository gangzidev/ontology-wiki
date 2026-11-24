package com.ontologywiki.query.planning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ontologywiki.ontology.service.llm.LlmClient;
import com.ontologywiki.query.catalog.QuerySemanticCatalogService;
import com.ontologywiki.query.compiler.QuerySqlCompiler;
import com.ontologywiki.query.policy.QueryReadinessService;
import com.ontologywiki.query.repository.QueryConfigurationRepository;
import com.ontologywiki.query.validation.QueryPlanValidator;
import org.junit.Assert;
import org.junit.Test;

import java.util.*;

public class QueryPlannerRetrievalTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    public void largeConfigurationOnlySuppliesRelevantPropertiesToTheModel() throws Exception {
        Map<String, Object> config = config();
        StubLlm model = new StubLlm("{\"filters\":[{\"propertyId\":\"external\",\"operator\":\"=\",\"value\":0}]}");
        Map<String, Object> result = planner(config, model).plan(1, "外链的菜单数量有多少");
        Map<String, Object> plan = (Map<String, Object>) result.get("plan");
        QuerySqlCompiler.Result compiled = new QuerySqlCompiler().compile(plan, config, "mysql");
        Assert.assertEquals(Collections.singletonList(0), compiled.getParameters());
        Assert.assertTrue(compiled.getSql().toLowerCase(Locale.ROOT).contains("count("));
        Assert.assertTrue(model.prompts.get(0).contains("0是 1否"));
        Assert.assertFalse(model.prompts.get(0).contains("unrelated_"));
    }

    @Test
    public void naturalBusinessWordingRetrievesTheReviewedCountMetric() throws Exception {
        StubLlm model = new StubLlm("{\"metricId\":\"menuCount\"}",
                "{\"filters\":[{\"propertyId\":\"external\",\"operator\":\"=\",\"value\":0}]}");
        Map<String, Object> result = planner(config(), model).plan(1, "外链菜单有多少个");
        Map<?, ?> plan = (Map<?, ?>) result.get("plan");
        Assert.assertEquals("menuCount", ((Map<?, ?>) ((List<?>) plan.get("metrics")).get(0)).get("id"));
        Assert.assertEquals(2, model.prompts.size());
        Assert.assertTrue(model.prompts.get(0).contains("菜单数量"));
    }

    @Test
    public void directAggregationOnLargeConfigurationDoesNotNeedAModel() throws Exception {
        StubLlm model = new StubLlm();
        Assert.assertTrue(planner(config(), model).plan(1, "统计菜单数量").containsKey("plan"));
        Assert.assertTrue(model.prompts.isEmpty());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsFilterOutsideRetrievedContextEvenWhenItExistsInTheConfiguration() throws Exception {
        StubLlm model = new StubLlm("{\"filters\":[{\"propertyId\":\"unrelated_499\",\"operator\":\"=\",\"value\":1}]}");
        planner(config(), model).plan(1, "外链的菜单数量有多少");
    }

    @Test
    public void wideObjectRetainsNamedFilterAndComputedMetricDependencies() {
        Map<String, Object> config = config();
        List<Map<String, Object>> properties = (List<Map<String, Object>>) config.get("properties");
        for (Map<String, Object> property : properties) property.put("objectId", "menus");
        properties.add(row("id", "lateStatus", "objectId", "menus", "column", "status", "name", "发布状态"));
        Map<String, Object> semantics = (Map<String, Object>) config.get("semanticCatalog");
        List<Map<String, Object>> metrics = new ArrayList<>((List<Map<String, Object>>) semantics.get("metrics"));
        metrics.add(row("id", "computed", "name", "菜单汇总", "type", "COMPUTED", "formula",
                row("operator", "ADD", "left", row("metricId", "menuCount"), "right", row("propertyId", "unrelated_499", "aggregation", "SUM"))));
        semantics.put("metrics", metrics);
        QuerySemanticCatalogService.Resolution resolution = new QuerySemanticCatalogService().resolve(config, "发布状态为已发布的菜单汇总");
        Map<String, Object> context = new QueryContextRetriever().context(config, "发布状态为已发布的菜单汇总", resolution);
        List<?> selected = (List<?>) context.get("properties");
        Assert.assertTrue(selected.size() <= 200);
        String serialized = json.valueToTree(selected).toString();
        Assert.assertTrue(serialized.contains("lateStatus"));
        Assert.assertTrue(serialized.contains("unrelated_499"));
        Assert.assertTrue(serialized.contains("menuId"));
    }

    @Test
    public void legacyFlagMetricIsNotOfferedOrExecutedAsABusinessMeasure() throws Exception {
        Map<String, Object> config = config();
        Map<String, Object> metric = row("id", "bad", "name", "是否为外链（0是 1否）数量", "propertyId", "external", "aggregation", "SUM");
        Map<String, Object> semantics = (Map<String, Object>) config.get("semanticCatalog");
        semantics.put("metrics", Arrays.asList(((List<?>) semantics.get("metrics")).get(0), metric));
        QuerySemanticCatalogService catalog = new QuerySemanticCatalogService();
        Assert.assertFalse(catalog.isBusinessMetric(metric, config));
        Map<String, Object> result = planner(config, new StubLlm()).plan(1, "是否为外链（0是 1否）数量");
        Assert.assertTrue(result.containsKey("clarification"));
        try {
            new QueryPlanValidator().validate(row("metrics", Collections.singletonList(row("id", "bad"))), config);
            Assert.fail("状态编码不能作为求和指标执行");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("状态编码"));
        }
    }

    @Test
    public void relatedFilterFieldsAndLocalTimeDimensionsAreRetrieved() {
        Map<String, Object> config = config();
        List<Map<String, Object>> properties = (List<Map<String, Object>>) config.get("properties");
        properties.add(row("id", "published", "objectId", "other", "column", "published", "name", "发布状态"));
        properties.add(row("id", "created", "objectId", "menus", "column", "created_at", "name", "创建时间", "dataType", "DATE"));
        Map<String, Object> semantics = (Map<String, Object>) config.get("semanticCatalog");
        semantics.put("relations", Collections.singletonList(row("sourceObjectId", "menus", "targetObjectId", "other", "sourceColumn", "menu_id", "targetColumn", "field_0")));
        semantics.put("timeDimensions", Arrays.asList(row("id", "localTime", "propertyId", "created", "name", "创建时间"),
                row("id", "otherTime", "propertyId", "unrelated_1", "name", "其他时间", "default", true)));
        String question = "今年发布状态为已发布的菜单数量";
        QuerySemanticCatalogService.Resolution resolution = new QuerySemanticCatalogService().resolve(config, question);
        Map<String, Object> context = new QueryContextRetriever().context(config, question, resolution);
        String selected = json.valueToTree(context.get("properties")).toString();
        Assert.assertTrue(selected.contains("published"));
        Assert.assertTrue(selected.contains("created"));
        Assert.assertFalse(selected.contains("unrelated_"));
        Assert.assertEquals(1, ((List<?>) context.get("relations")).size());
        Assert.assertEquals(1, ((List<?>) context.get("timeDimensions")).size());
        Assert.assertEquals("localTime", ((Map<?, ?>) ((List<?>) context.get("timeDimensions")).get(0)).get("id"));
    }

    @Test
    public void detailConditionsProjectionSortAndLimitAreParsedInsteadOfDiscarded() throws Exception {
        StubLlm model = new StubLlm("{\"fields\":[{\"id\":\"menuId\"}],\"filters\":[{\"propertyId\":\"external\",\"operator\":\"=\",\"value\":0}],\"orderBy\":[{\"field\":\"menuId\",\"direction\":\"DESC\"}],\"limit\":20}");
        Map<String, Object> config = config();
        Map<String, Object> result = planner(config, model).plan(1, "查看外链菜单的数据，只显示菜单ID，按ID倒序，前20条");
        Map<String, Object> plan = (Map<String, Object>) result.get("plan");
        Assert.assertEquals("menus", plan.get("detailObjectId"));
        Assert.assertEquals(20, plan.get("limit"));
        QuerySqlCompiler.Result sql = new QuerySqlCompiler().compile(plan, config, "mysql");
        Assert.assertEquals(Collections.singletonList(0), sql.getParameters());
        Assert.assertTrue(sql.getSql().contains("select t0.`menu_id` from"));
        Assert.assertTrue(sql.getSql().contains("order by t0.`menu_id` DESC limit 20 offset 0"));
        Assert.assertEquals(1, model.prompts.size());
        Assert.assertFalse(model.prompts.get(0).contains("unrelated_"));
    }

    @Test
    public void explicitCountRequestStillUsesMetricRetrieval() throws Exception {
        StubLlm model = new StubLlm("{\"metricId\":\"menuCount\"}", "{}");
        Map<?, ?> plan = (Map<?, ?>) planner(config(), model).plan(1, "查看菜单有多少条记录").get("plan");
        Assert.assertFalse(plan.containsKey("detailObjectId"));
        Assert.assertFalse(((List<?>) plan.get("metrics")).isEmpty());
    }

    @Test(expected = IllegalArgumentException.class)
    public void detailParserCannotSelectAnUnrelatedProperty() throws Exception {
        planner(config(), new StubLlm("{\"fields\":[{\"id\":\"unrelated_499\"}]}")).plan(1, "查看菜单的数据，只显示编号");
    }

    @Test
    public void simpleDetailAfterClarificationDoesNotCallModel() throws Exception {
        StubLlm model = new StubLlm();
        Assert.assertTrue(planner(config(), model).plan(1, "我想看菜单信息\n补充说明：菜单的数据").containsKey("plan"));
        Assert.assertTrue(model.prompts.isEmpty());
    }

    @Test
    public void followUpKeepsUnchangedFiltersAndReplacesOnlyNamedFilter() {
        QueryPlannerService planner = new QueryPlannerService(null, null, null, null, null, json);
        Map<String, Object> previous = row("metrics", Collections.singletonList(row("id", "menuCount")),
                "dimensions", Collections.emptyList(), "filters", Arrays.asList(
                        row("propertyId", "external", "operator", "=", "value", 0),
                        row("propertyId", "menuId", "operator", ">", "value", 10)), "timeRange", row("from", "2026-01-01", "to", "2026-01-31"), "limit", 20);
        Map<String, Object> changes = row("filters", Collections.singletonList(row("propertyId", "external", "operator", "=", "value", 1)));

        Map<String, Object> merged = planner.applyFollowUp(previous, changes);

        List<?> filters = (List<?>) merged.get("filters");
        Assert.assertEquals(2, filters.size());
        Assert.assertEquals(1, ((Map<?, ?>) filters.get(0)).get("value"));
        Assert.assertEquals(10, ((Map<?, ?>) filters.get(1)).get("value"));
        Assert.assertEquals(previous.get("timeRange"), merged.get("timeRange"));
        Assert.assertEquals(20, merged.get("limit"));
    }

    @Test
    public void followUpCanClearComparisonAndStartsFromFirstDetailPage() {
        QueryPlannerService planner = new QueryPlannerService(null, null, null, null, null, json);
        Map<String, Object> previous = row("detailObjectId", "menus", "offset", 100,
                "comparison", row("type", "MOM"), "filters", Collections.emptyList());

        Map<String, Object> merged = planner.applyFollowUp(previous, Collections.singletonMap("comparison", null));

        Assert.assertFalse(merged.containsKey("comparison"));
        Assert.assertFalse(merged.containsKey("offset"));
        Assert.assertEquals("menus", merged.get("detailObjectId"));
    }

    @Test
    public void clearingTimeRangeAlsoClearsOrphanedTimeDimensionAndComparison() {
        QueryPlannerService planner = new QueryPlannerService(null, null, null, null, null, json);
        Map<String, Object> previous = row("timeDimension", "createdAt", "timeRange", row("from", "2026-01-01", "to", "2026-01-31"),
                "comparison", row("type", "MOM"), "metrics", Collections.singletonList(row("id", "orderCount")));

        Map<String, Object> merged = planner.applyFollowUp(previous, Collections.singletonMap("timeRange", null));

        Assert.assertFalse(merged.containsKey("timeDimension"));
        Assert.assertFalse(merged.containsKey("comparison"));
        Assert.assertEquals(previous.get("metrics"), merged.get("metrics"));
    }

    private QueryPlannerService planner(Map<String, Object> config, StubLlm model) throws Exception {
        final Map<String, Object> proposal = row("status", "COMPLETED", "config_json", json.writeValueAsString(config));
        QueryConfigurationRepository repository = new QueryConfigurationRepository(null) {
            @Override public Map<String, Object> findProposal(long id) { return proposal; }
        };
        return new QueryPlannerService(repository, new QueryReadinessService(repository, json), new QueryPlanValidator(),
                new QuerySemanticCatalogService(), model, json);
    }

    private Map<String, Object> config() {
        List<Map<String, Object>> properties = new ArrayList<>();
        properties.add(row("id", "menuId", "objectId", "menus", "column", "menu_id", "name", "菜单ID", "dataType", "BIGINT"));
        properties.add(row("id", "external", "objectId", "menus", "column", "is_frame", "name", "是否为外链（0是 1否）", "dataType", "INT"));
        for (int index = 0; index < 500; index++) properties.add(row("id", "unrelated_" + index, "objectId", "other", "column", "field_" + index, "dataType", "INT"));
        return row("objects", Arrays.asList(row("id", "menus", "name", "菜单", "table", "sys_menu"), row("id", "other", "name", "其他", "table", "unrelated")),
                "properties", properties, "semanticCatalog", row("metrics", Collections.singletonList(row("id", "menuCount", "name", "菜单数量", "aggregation", "COUNT", "propertyId", "menuId")),
                        "dimensions", Collections.emptyList(), "relations", Collections.emptyList()));
    }

    private Map<String, Object> row(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) result.put(String.valueOf(values[index]), values[index + 1]);
        return result;
    }

    private static class StubLlm extends LlmClient {
        final List<String> prompts = new ArrayList<>();
        final Queue<String> responses;
        StubLlm(String... responses) { super(null, null, new ObjectMapper()); this.responses = new ArrayDeque<>(Arrays.asList(responses)); }
        @Override public String chat(String prompt, double temperature) {
            prompts.add(prompt);
            Assert.assertFalse("出现了预期外的模型调用", responses.isEmpty());
            return responses.remove();
        }
    }
}

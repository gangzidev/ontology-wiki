package com.ontologywiki.query.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ontologywiki.query.catalog.QuerySemanticCatalogService;
import com.ontologywiki.query.mapper.QueryConfigurationMapper;
import com.ontologywiki.query.policy.QueryReadinessService;
import com.ontologywiki.query.repository.QueryConfigurationRepository;
import com.ontologywiki.query.service.impl.DataQueryServiceImpl;
import com.ontologywiki.query.support.QueryConfigHash;
import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class DataQueryInitializationTest {
    private static final String CONFIG = "{\"objects\":[{\"id\":\"orders\"}],"
            + "\"properties\":[{\"id\":\"order_id\",\"objectId\":\"orders\",\"column\":\"id\"}],"
            + "\"querySemantics\":{\"metrics\":[{\"id\":\"count\",\"name\":\"订单数量\","
            + "\"propertyId\":\"order_id\",\"aggregation\":\"COUNT\"}],\"dimensions\":[]}}";

    @Test
    public void catalogAndReadinessShareOneReadAndReflectSubsequentEdits() throws Exception {
        CountingMapper mapper = new CountingMapper();
        QueryConfigurationRepository repository = new QueryConfigurationRepository(mapper);
        ObjectMapper json = new ObjectMapper();
        String originalHash = QueryConfigHash.of(json, json.readTree(CONFIG));
        QueryReadinessService readiness = new QueryReadinessService(repository, json,
                (proposalId, hash) -> proposalId == 6L && originalHash.equals(hash));
        DataQueryServiceImpl service = new DataQueryServiceImpl(readiness, repository,
                null, null, null, null, null, null, new QuerySemanticCatalogService(), null);

        Map<String, Object> result = service.catalog(6L);
        assertEquals("初始化只读取一次草案", 1, mapper.reads);
        QueryReadinessService.Readiness state = (QueryReadinessService.Readiness) result.get("readiness");
        assertTrue(state.isQueryEnabled());
        assertTrue(state.isEvaluationPassed());
        assertEquals(state.isQueryEnabled(), result.get("queryEnabled"));
        assertEquals(state.getReasons(), result.get("readinessReasons"));
        assertEquals("订单数量", ((List<Map<String, Object>>) result.get("metrics")).get(0).get("name"));
        assertFalse("响应不暴露完整配置", json.writeValueAsString(result).contains("config_json"));

        mapper.row.put("status", "DRAFT");
        mapper.row.put("config_json", CONFIG.replace("订单数量", "订单总数"));
        result = service.catalog(6L);
        assertEquals(2, mapper.reads);
        state = (QueryReadinessService.Readiness) result.get("readiness");
        assertFalse("审核变更立即生效", state.isQueryEnabled());
        assertFalse("配置变更不沿用旧评测", state.isEvaluationPassed());
        assertEquals("订单总数", ((List<Map<String, Object>>) result.get("metrics")).get(0).get("name"));
    }

    @Test
    public void deletedOrMalformedProposalDoesNotReturnAReadyWorkspace() {
        CountingMapper mapper = new CountingMapper();
        QueryConfigurationRepository repository = new QueryConfigurationRepository(mapper);
        QueryReadinessService readiness = new QueryReadinessService(repository, new ObjectMapper());
        DataQueryServiceImpl service = new DataQueryServiceImpl(readiness, repository,
                null, null, null, null, null, null, new QuerySemanticCatalogService(), null);
        mapper.row.put("config_json", "invalid JSON");
        try {
            service.catalog(6L);
            fail("无效配置应报错");
        } catch (IllegalArgumentException expected) {
            assertEquals("配置草案不是有效 JSON", expected.getMessage());
        }
        mapper.row = null;
        try {
            service.catalog(6L);
            fail("已删除草案应报错");
        } catch (IllegalArgumentException expected) {
            assertEquals("配置草案不存在", expected.getMessage());
        }
        assertEquals(2, mapper.reads);
    }

    private static class CountingMapper implements QueryConfigurationMapper {
        int reads;
        Map<String, Object> row = new LinkedHashMap<>();
        CountingMapper() {
            row.put("proposal_id", 6L);
            row.put("task_id", 1L);
            row.put("status", "COMPLETED");
            row.put("config_json", CONFIG);
        }
        @Override public Map<String, Object> selectProposal(long proposalId) {
            reads++;
            return row == null ? null : new LinkedHashMap<>(row);
        }
        @Override public Map<String, Object> selectTaskDatasource(long taskId) {
            return Collections.emptyMap();
        }
    }
}

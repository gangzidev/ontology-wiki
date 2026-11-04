package com.ontologywiki.ontology.controller;

import com.ontologywiki.common.core.domain.AjaxResult;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class OntologyAnalysisScopeTest {
    @Test
    public void analysisWithoutIndustryKnowledgeSpaceStopsBeforeDatabaseScan() {
        Map<String, Object> task = new HashMap<>();
        task.put("source_id", 10L);
        task.put("wiki_space_id", null);
        JdbcTemplate jdbc = new JdbcTemplate() {
            @Override
            public Map<String, Object> queryForMap(String sql, Object... args) {
                return task;
            }
        };
        OntologyController controller = new OntologyController(jdbc, null, null, null, null, null, null, null);

        AjaxResult result = controller.analyze(1L, Collections.<String, String>emptyMap());

        Assert.assertTrue(String.valueOf(result.get("msg")).contains("未绑定行业知识空间"));
    }

    @Test
    public void sqlLinesInTaskScopeBecomeNonExecutingQueryPatterns() {
        String scope = "业务重点\nSELECT * FROM orders\nUPDATE orders SET status='PAID'\n只读客户信息";
        List<String> patterns = OntologyController.queryPatternsFromScope(scope);

        Assert.assertEquals(2, patterns.size());
        Assert.assertEquals("SELECT * FROM orders", patterns.get(0));
        Assert.assertTrue(patterns.get(1).startsWith("UPDATE"));
        Assert.assertEquals("业务重点\n只读客户信息", OntologyController.scopeForPrompt(scope));
    }
}

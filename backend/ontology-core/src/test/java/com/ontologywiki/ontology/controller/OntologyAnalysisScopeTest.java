package com.ontologywiki.ontology.controller;

import com.ontologywiki.common.core.domain.AjaxResult;
import com.ontologywiki.ontology.mapper.OntologyAnalysisMapper;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 分析入口的范围校验测试。
 *
 * <p>重构后 SQL 已下沉到 Mapper，这里用接口桩替代真实数据访问，
 * 只验证「任务未绑定知识空间时提前返回、不触发后续扫描」这一业务分支。
 */
public class OntologyAnalysisScopeTest {

    @Test
    public void analysisWithoutIndustryKnowledgeSpaceStopsBeforeDatabaseScan() {
        Map<String, Object> task = new HashMap<>();
        task.put("source_id", 10L);
        task.put("wiki_space_id", null);

        OntologyController controller = new OntologyController(null, null, null, null, null, null, null,
                null, analysisMapperReturningTask(task), null, null, null, null);

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

    /** 构造只实现 selectTask 的 Mapper 桩，其余方法一旦被调用即失败。 */
    private OntologyAnalysisMapper analysisMapperReturningTask(Map<String, Object> task) {
        InvocationHandler handler = (proxy, method, args) -> {
            if ("selectTask".equals(method.getName())) {
                return task;
            }
            throw new AssertionError("不应调用 Mapper 方法：" + method.getName());
        };
        return (OntologyAnalysisMapper) Proxy.newProxyInstance(
                OntologyAnalysisMapper.class.getClassLoader(),
                new Class<?>[]{OntologyAnalysisMapper.class}, handler);
    }
}

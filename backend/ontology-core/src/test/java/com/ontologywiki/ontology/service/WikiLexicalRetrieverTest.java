package com.ontologywiki.ontology.service;

import org.junit.Assert;
import org.junit.Test;

import java.util.*;

public class WikiLexicalRetrieverTest {
    @Test
    public void returnsOnlyTopMatchingMetadataRows() {
        WikiLexicalRetriever retriever = new WikiLexicalRetriever();
        List<Map<String,Object>> rows = Arrays.asList(
                row(1, "角色表字段说明", "身份权限", "role_name 字段存储角色名称"),
                row(2, "用户登录流程", "账号", "用户认证与登录"),
                row(3, "角色权限配置", "权限", "角色与权限关系"),
                row(4, "订单状态说明", "订单", "订单生命周期"));

        List<Map<String,Object>> selected = retriever.rank(rows, "role_name 角色字段", 2);

        Assert.assertEquals(2, selected.size());
        Assert.assertEquals(1L, ((Number) selected.get(0).get("asset_id")).longValue());
        Assert.assertEquals(3L, ((Number) selected.get(1).get("asset_id")).longValue());
        Assert.assertTrue(retriever.rank(rows, "今天天气怎么样", 3).isEmpty());
    }

    @Test
    public void snippetPrefersMatchingParagraphAndRespectsLimit() {
        WikiLexicalRetriever retriever = new WikiLexicalRetriever();
        String content = "# 角色\n\n普通介绍内容。\n\nrole_name 用于保存角色名称。\n\n权限说明内容。";

        String snippet = retriever.evidenceSnippet(content, "role_name 角色名称", 30);

        Assert.assertTrue(snippet.contains("role_name"));
        Assert.assertTrue(snippet.length() <= 30);
    }

    private Map<String,Object> row(long id, String title, String domain, String description) {
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("asset_id", id); row.put("asset_name", title); row.put("domain_name", domain); row.put("description", description);
        return row;
    }
}

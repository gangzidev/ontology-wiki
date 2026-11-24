package com.ontologywiki.ontology.service.wiki;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ontologywiki.ontology.mapper.WikiRelationMapper;
import com.ontologywiki.ontology.repository.OntologyResourceRepository.PageResult;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.*;

public class WikiRelationServiceReadTest {
    @Test
    public void graphKeepsPagedNodeAndEdgeResponseShape() {
        List<Long> graphAssetIds = new ArrayList<>();
        WikiRelationMapper mapper = mapper((method, args) -> {
            if ("selectGraphNodes".equals(method)) {
                Assert.assertEquals(8L, args[0]);
                Assert.assertEquals(50, args[1]);
                Assert.assertEquals(50L, args[2]);
                return Arrays.asList(row("asset_id", 11L, "space_id", 8L, "asset_name", "订单", "asset_type", "WIKI_PAGE"),
                        row("asset_id", 12L, "space_id", 8L, "asset_name", "客户", "asset_type", "WIKI_PAGE"));
            }
            if ("selectGraphRelations".equals(method)) {
                graphAssetIds.addAll((List<Long>) args[1]);
                return Arrays.asList(
                        row("source_asset_id", 11L, "target_asset_id", 12L, "relation_type", "WIKI_LINK"),
                        row("source_asset_id", 11L, "target_asset_id", null, "target_name", "未创建页面", "relation_type", "MARKDOWN_LINK"));
            }
            if ("countGraphNodes".equals(method)) return 127;
            throw new AssertionError("Unexpected mapper call " + method);
        });

        Map<String, Object> graph = new WikiRelationService(mapper, new ObjectMapper()).graph(8L, 2, 50);

        Assert.assertEquals(Arrays.asList(11L, 12L), graphAssetIds);
        Assert.assertEquals(2, ((List<?>) graph.get("nodes")).size());
        Assert.assertEquals(1, ((List<?>) graph.get("edges")).size());
        Assert.assertEquals(1, ((List<?>) graph.get("unresolved")).size());
        Assert.assertEquals(2, graph.get("pageNum"));
        Assert.assertEquals(50, graph.get("pageSize"));
        Assert.assertEquals(127, graph.get("total"));
    }

    @Test
    public void relationPageNormalizesDirectionAndBoundsPagination() {
        WikiRelationMapper mapper = mapper((method, args) -> {
            if ("selectAssetRelations".equals(method)) {
                Assert.assertEquals(21L, args[0]);
                Assert.assertEquals("INCOMING", args[1]);
                Assert.assertEquals(40L, args[2]);
                Assert.assertEquals(20, args[3]);
                return Collections.singletonList(row("relation_type", "WIKI_LINK"));
            }
            if ("countAssetRelations".equals(method)) {
                Assert.assertEquals("INCOMING", args[1]);
                return 1;
            }
            throw new AssertionError("Unexpected mapper call " + method);
        });

        PageResult page = new WikiRelationService(mapper, new ObjectMapper())
                .relationPage(21L, "incoming", 3, 20);

        Assert.assertEquals(1, page.getRows().size());
        Assert.assertEquals(1, page.getTotal());
    }

    private WikiRelationMapper mapper(MapperCall handler) {
        return (WikiRelationMapper) Proxy.newProxyInstance(WikiRelationMapper.class.getClassLoader(),
                new Class<?>[]{WikiRelationMapper.class}, (proxy, method, args) -> handler.call(method.getName(), args));
    }

    private Map<String, Object> row(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }

    private interface MapperCall {
        Object call(String method, Object[] args);
    }
}

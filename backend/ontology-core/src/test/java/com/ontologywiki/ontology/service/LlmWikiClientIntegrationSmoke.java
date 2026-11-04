package com.ontologywiki.ontology.service;

import com.ontologywiki.ontology.config.OntologyIntegrationProperties;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.client.RestTemplate;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class LlmWikiClientIntegrationSmoke {
    @Test
    public void realMysqlAndModelCompleteWikiChain() {
        String key = System.getenv("LLM_API_KEY");
        Assert.assertNotNull("LLM_API_KEY is required", key);
        DriverManagerDataSource ds = new DriverManagerDataSource(System.getenv("ONTOLOGY_DB_URL"), System.getenv("ONTOLOGY_DB_USERNAME"), System.getenv("ONTOLOGY_DB_PASSWORD"));
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        OntologyIntegrationProperties props = new OntologyIntegrationProperties();
        OntologyIntegrationProperties.LlmWiki cfg = props.getLlmWiki();
        cfg.setEnabled(true); cfg.setApiBaseUrl("https://fast.sbbbbbbbbb.xyz/v1"); cfg.setApiKey(key); cfg.setModel("gpt-6-sol");
        LlmWikiClient client = new LlmWikiClient(props, new RestTemplate(), jdbc, new CredentialService("smoke-test-credential-key"));
        client.ensureChatSessionTable();
        String suffix = String.valueOf(System.currentTimeMillis());
        jdbc.update("insert into wiki_space(space_name,description,status,create_by,create_time,update_by,update_time) values(?,?,'0','smoke',now(),'smoke',now())", "链路测试空间-" + suffix, "自动化临时空间");
        Long spaceId = jdbc.queryForObject("select max(space_id) from wiki_space where space_name=? and create_by='smoke'", Long.class, "链路测试空间-" + suffix);
        jdbc.update("insert into wiki_asset(space_id,asset_name,asset_type,domain_name,description,content,status,create_by,create_time,update_by,update_time) values(?,?,'TEXT','测试域','临时脱敏测试','订单状态包括待支付、已支付、已取消。只有已支付订单可以发货。', '0','smoke',now(),'smoke',now())", spaceId, "链路测试订单规则-" + suffix);
        Long id = jdbc.queryForObject("select max(asset_id) from wiki_asset where space_id=? and create_by='smoke'", Long.class, spaceId);
        jdbc.update("insert into wiki_asset(space_id,asset_name,asset_type,domain_name,description,content,status,create_by,create_time,update_by,update_time) values(?,?,'TEXT','测试域','无关资料','管理员临时授权关键字仅用于资产范围隔离测试。', '0','smoke',now(),'smoke',now())", spaceId, "链路测试无关资料-" + suffix);
        Long decoyId = jdbc.queryForObject("select max(asset_id) from wiki_asset where space_id=? and create_by='smoke'", Long.class, spaceId);
        try {
            Map<String,Object> ingested = client.ingestAsset(id);
            Assert.assertNotNull(ingested.get("title"));
            Long pageSpace = jdbc.queryForObject("select space_id from wiki_asset where tags=?", Long.class, "SOURCE:" + id);
            Assert.assertEquals(spaceId, pageSpace);
            Map<String,Object> answer = client.queryResult("已支付订单可以做什么？", false, spaceId, Collections.<Map<String,Object>>emptyList());
            Assert.assertTrue(String.valueOf(answer.get("answer")).length() > 0);
            List<Map<String,Object>> citations = (List<Map<String,Object>>) answer.get("citedSources");
            Assert.assertNotNull(citations);
            Assert.assertFalse(citations.isEmpty());
            for (Map<String,Object> citation : citations) {
                Long citedSpace = jdbc.queryForObject("select space_id from wiki_asset where asset_id=?", Long.class, citation.get("assetId"));
                Assert.assertEquals(spaceId, citedSpace);
            }
            Map<String,Object> assetAnswer = client.queryResult("订单状态包括什么？", false, spaceId, Collections.<Map<String,Object>>emptyList(), "ASSET", id);
            List<Map<String,Object>> assetCitations = (List<Map<String,Object>>) assetAnswer.get("citedSources");
            Assert.assertFalse(assetCitations.isEmpty());
            Long generatedPageId = jdbc.queryForObject("select asset_id from wiki_asset where tags=?", Long.class, "SOURCE:" + id);
            for (Map<String,Object> citation : assetCitations) Assert.assertTrue("asset scope cited a different asset", java.util.Arrays.asList(id, generatedPageId).contains(((Number) citation.get("assetId")).longValue()));
            Map<String,Object> greeting = client.queryResult("你好", false, null, Collections.<Map<String,Object>>emptyList(), "ASSET", Long.MAX_VALUE);
            Assert.assertTrue("greeting should still reach the configured model without Wiki matches", String.valueOf(greeting.get("answer")).length() > 0);
            Assert.assertTrue(((List<?>) greeting.get("citedSources")).isEmpty());
            System.out.println("wiki_ingest_title=" + ingested.get("title"));
            System.out.println("wiki_answer_length=" + String.valueOf(answer.get("answer")).length());
            System.out.println("wiki_cited_count=" + citations.size());
            System.out.println("wiki_asset_scope_cited_count=" + assetCitations.size());
        } finally {
            jdbc.update("delete from wiki_asset where asset_id=?", id);
            jdbc.update("delete from wiki_asset where asset_id=?", decoyId);
            jdbc.update("delete from wiki_asset where tags=?", "SOURCE:" + id);
            jdbc.update("delete from wiki_space where space_id=?", spaceId);
            client.refreshIndex();
        }
    }
}

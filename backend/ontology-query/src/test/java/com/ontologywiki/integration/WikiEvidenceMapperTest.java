package com.ontologywiki.integration;

import com.ontologywiki.ontology.mapper.WikiAssetMapper;
import com.ontologywiki.ontology.mapper.WikiChatMapper;
import com.ontologywiki.ontology.mapper.WikiChunkMapper;
import com.ontologywiki.ontology.service.wiki.WikiChunkIndexService;
import com.ontologywiki.ontology.service.analysis.AnalysisKnowledgeLoader;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.Before;
import org.junit.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

public class WikiEvidenceMapperTest {
    private JdbcTemplate jdbc;
    private WikiAssetMapper assets;
    private WikiChatMapper chat;
    private WikiChunkMapper chunks;

    @Before
    public void setup() throws Exception {
        JdbcDataSource db = new JdbcDataSource();
        db.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(db);
        jdbc.execute("create table wiki_asset(asset_id bigint primary key, space_id bigint, asset_name varchar(50), asset_type varchar(20), domain_name varchar(50), description varchar(50), content varchar(3000), tags varchar(50), status char)");
        jdbc.execute("create table wiki_space(space_id bigint, status char)");
        jdbc.execute("create table wiki_asset_chunk(chunk_id bigint auto_increment primary key, space_id bigint, asset_id bigint, title varchar(200), source_ref varchar(500), source_version varchar(64), chunk_index int, paragraph_no int, start_offset int, content varchar(3000))");
        jdbc.execute("create table wiki_asset_chunk_index(asset_id bigint primary key, space_id bigint, source_version varchar(64), status varchar(20), chunk_count int, indexed_at timestamp)");
        jdbc.update("insert into wiki_space values(1,'0'), (2,'0'), (3,'1')");
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(db);
        factory.setMapperLocations(new ClassPathResource("mapper/ontology/WikiAssetMapper.xml"),
                new ClassPathResource("mapper/ontology/WikiChunkMapper.xml"),
                new ClassPathResource("mapper/ontology/WikiChatMapper.xml"));
        SqlSessionTemplate sessions = new SqlSessionTemplate(factory.getObject());
        assets = sessions.getMapper(WikiAssetMapper.class);
        chat = sessions.getMapper(WikiChatMapper.class);
        chunks = sessions.getMapper(WikiChunkMapper.class);
    }

    @Test
    public void bodyHitsSurviveNewerUnrelatedAssetsAndRespectScope() {
        asset(1, 1, "正文中的 USER_ID", null, "0");
        asset(2, 1, "关联 user_id", "SOURCE:1", "0");
        asset(3, 2, "user_id", "SOURCE:1", "0");
        asset(4, 3, "user_id", null, "0");
        asset(5, 1, "user_id", null, "1");
        asset(6, 1, "user_id", "SYSTEM_INDEX", "0");
        asset(7, 1, "userXid", null, "0");
        for (int i = 10; i < 250; i++) asset(i, 1, "无关资料", null, "0");

        assertEquals(Arrays.asList(2L, 1L), hits(false, null, 1L));
        assertEquals(Arrays.asList(2L, 1L), hits(true, 1L, null));
        assertEquals(Arrays.asList(3L, 2L, 1L), hits(false, null, null));
    }

    @Test
    public void historyLoadsEvidenceAndLegacyLinksAndFiltersMovedAssets() {
        jdbc.execute("create table wiki_chat_session(session_id varchar(10), created_by varchar(10), scope_type varchar(10), space_id bigint, scope_id bigint)");
        jdbc.execute("create table wiki_chat_message(message_id bigint, session_id varchar(10), created_by varchar(10))");
        jdbc.execute("create table wiki_chat_citation(message_id bigint, asset_id bigint, evidence_json varchar(2000))");
        jdbc.update("insert into wiki_chat_session values('session','u','SPACE',1,1)");
        jdbc.update("insert into wiki_chat_message values(10,'session','u')");
        asset(1, 1, "原文", null, "0");
        asset(2, 1, "其他资料", null, "0");
        String evidence = "{\"paragraph\":2,\"excerpt\":\"原文\"}";
        chat.insertCitation(10L, 1L, evidence);
        chat.insertCitation(10L, 2L, null);
        List<Map<String,Object>> history = chat.selectCitations(Collections.singletonList(10L));
        assertEquals(2, history.size());
        assertTrue(history.stream().anyMatch(row -> evidence.equals(row.get("evidence_json"))));
        jdbc.update("update wiki_asset set space_id=2 where asset_id=1");
        assertEquals(1, chat.selectCitations(Collections.singletonList(10L)).size());
    }

    @Test
    public void chunkIndexRefreshesContentAndSearchRespectsSpaceScope() {
        asset(1, 1, "第一段 user_id\n\n第二段订单状态", null, "0");
        asset(2, 2, "user_id 仅在另一个空间", null, "0");
        WikiChunkIndexService index = new WikiChunkIndexService(chunks);
        index.refreshAsset(1);
        index.refreshAsset(2);
        assertEquals("INDEXED", jdbc.queryForObject("select status from wiki_asset_chunk_index where asset_id=1", String.class));
        assertEquals("INDEXED", jdbc.queryForObject("select status from wiki_asset_chunk_index where asset_id=2", String.class));

        List<Map<String,Object>> scoped = index.search(Collections.singletonList("user_id"), false, null, 1L, 5);
        assertEquals(1, scoped.size());
        assertEquals(1L, ((Number) scoped.get(0).get("assetId")).longValue());
        assertEquals("第一段 user_id", scoped.get(0).get("excerpt"));

        jdbc.update("update wiki_asset set content='更新后的订单状态' where asset_id=1");
        index.refreshAsset(1);
        assertEquals(1, jdbc.queryForObject("select chunk_count from wiki_asset_chunk_index where asset_id=1", Integer.class).intValue());
        assertTrue(index.search(Collections.singletonList("user_id"), false, null, 1L, 5).isEmpty());
        assertEquals("更新后的订单状态", index.search(Collections.singletonList("订单"), false, null, 1L, 5).get(0).get("excerpt"));
    }

    @Test
    public void analysisKnowledgeUsesRelevantIndexedWikiEvidence() {
        asset(1, 1, "用户标识 user_id 用于关联账户", null, "0");
        asset(2, 1, "库存仓库与补货规则", null, "0");
        asset(3, 2, "用户标识 user_id 另一个空间", null, "0");
        jdbc.update("update wiki_asset set asset_name='用户账户标识说明' where asset_id=1");
        jdbc.update("update wiki_asset set asset_name='库存补货规则' where asset_id=2");
        jdbc.update("update wiki_asset set asset_name='其他空间用户字段' where asset_id=3");
        WikiChunkIndexService index = new WikiChunkIndexService(chunks);
        index.refreshAsset(1);
        index.refreshAsset(2);
        index.refreshAsset(3);

        AnalysisKnowledgeLoader loader = new AnalysisKnowledgeLoader(assets, index);
        List<Map<String,Object>> documents = loader.loadWikiDocuments(1L, "用户账户 user_id");
        assertEquals(1, documents.size());
        assertEquals("用户账户标识说明", documents.get(0).get("title"));
        assertTrue(String.valueOf(documents.get(0).get("content")).contains("user_id"));
    }

    private void asset(long id, long space, String content, String tag, String status) {
        jdbc.update("insert into wiki_asset values(?,?,'普通标题','WIKI_PAGE',null,null,?,?,?)",
                id, space, content, tag, status);
    }

    private List<Long> hits(boolean assetScope, Long scopeId, Long spaceId) {
        return assets.selectEvidenceCandidates(Collections.singletonList("user_id"), assetScope, scopeId, spaceId, 12)
                .stream().map(row -> ((Number) row.get("asset_id")).longValue()).collect(Collectors.toList());
    }
}

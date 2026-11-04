package com.ontologywiki.ontology.controller;

import com.ontologywiki.common.core.page.TableDataInfo;
import com.ontologywiki.ontology.service.CredentialService;
import com.ontologywiki.ontology.service.DataSourceScanner;
import com.ontologywiki.ontology.service.LlmWikiClient;
import com.ontologywiki.ontology.service.OntologyArtifactExporter;
import com.ontologywiki.ontology.service.OntologyGenerationEngine;
import com.ontologywiki.ontology.config.OntologyIntegrationProperties;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class OntologyProposalListFilterTest {
    @Test
    public void taskFilterIsAppliedOnlyToProposalListQueries() {
        List<String> sql = new ArrayList<>();
        List<Object[]> args = new ArrayList<>();
        JdbcTemplate jdbc = new JdbcTemplate() {
            @Override
            public <T> T queryForObject(String query, Class<T> type, Object... values) {
                sql.add(query); args.add(values); return type.cast(Long.valueOf(0));
            }

            @Override
            public List<java.util.Map<String, Object>> queryForList(String query, Object... values) {
                sql.add(query); args.add(values); return Collections.emptyList();
            }
        };
        OntologyController controller = new OntologyController(jdbc, (CredentialService) null, (DataSourceScanner) null,
                (OntologyIntegrationProperties) null, (RestTemplate) null, (LlmWikiClient) null,
                (OntologyGenerationEngine) null, (OntologyArtifactExporter) null);

        TableDataInfo result = controller.list("proposals", 1, 20, null, null, 42L);

        Assert.assertEquals(0L, result.getTotal());
        Assert.assertTrue(sql.get(0).contains("where task_id=?"));
        Assert.assertTrue(sql.get(1).contains("where task_id=?"));
        Assert.assertEquals(42L, args.get(0)[0]);
        Assert.assertEquals(42L, args.get(1)[0]);
    }

    @Test
    public void assetListHidesTheSystemWikiIndex() {
        List<String> sql = new ArrayList<>();
        JdbcTemplate jdbc = new JdbcTemplate() {
            @Override public <T> T queryForObject(String query, Class<T> type, Object... values) { sql.add(query); return type.cast(Long.valueOf(0)); }
            @Override public List<java.util.Map<String,Object>> queryForList(String query, Object... values) { sql.add(query); return Collections.emptyList(); }
        };
        OntologyController controller = new OntologyController(jdbc, (CredentialService) null, (DataSourceScanner) null,
                (OntologyIntegrationProperties) null, (RestTemplate) null, (LlmWikiClient) null,
                (OntologyGenerationEngine) null, (OntologyArtifactExporter) null);

        controller.list("assets", 1, 20, null, null, null);

        Assert.assertTrue(sql.get(0).contains("coalesce(tags,'')<>'SYSTEM_INDEX'"));
        Assert.assertTrue(sql.get(1).contains("coalesce(tags,'')<>'SYSTEM_INDEX'"));
    }
}

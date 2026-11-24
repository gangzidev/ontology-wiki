package com.ontologywiki.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ontologywiki.ontology.mapper.*;
import com.ontologywiki.ontology.config.OntologyIntegrationProperties;
import com.ontologywiki.ontology.service.*;
import com.ontologywiki.ontology.service.analysis.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.Before;
import org.junit.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import static org.junit.Assert.*;

public class AnalysisServicesIntegrationTest {
    private JdbcTemplate jdbc;
    private AnalysisJobStore store;
    private AnalysisJobServiceImpl service;
    private SqlSessionTemplate sessions;
    private LlmWikiClient wiki;
    private DataSourceScanner scanner;
    private AnalysisKnowledgeLoader knowledge;
    private WikiChatMapper spaces;
    private DataSourceTransactionManager transactions;
    private final ObjectMapper json = new ObjectMapper();
    private final QueueExecutor executor = new QueueExecutor();
    private final AtomicInteger scans = new AtomicInteger();
    private Map<String,Object> lastScanOptions = Collections.emptyMap();
    private final AtomicInteger descriptions = new AtomicInteger();
    private boolean failNames;
    private boolean failAnswerSave;
    private boolean rejectExecution;
    private boolean cancelDuringNaming;
    private boolean failModelCall;

    @Before
    public void setup() throws Exception {
        JdbcDataSource database = new JdbcDataSource();
        database.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(database);
        jdbc.execute("create table analysis_task(task_id bigint primary key, task_name varchar(200), source_id bigint, wiki_space_id bigint, scope text, status varchar(30), summary text, update_by varchar(64), update_time timestamp)");
        jdbc.execute("create table wiki_space(space_id bigint primary key, space_name varchar(100))");
        jdbc.update("insert into wiki_space values (3, '零售行业')");
        jdbc.execute("create table ontology_datasource(datasource_id bigint, name varchar(100), db_type varchar(30), host varchar(50), port int, database_name varchar(50), schema_name varchar(50), jdbc_url varchar(100), username varchar(50), password_enc varchar(100), sample_limit int)");
        jdbc.execute("create table ontology_proposal(proposal_id bigint auto_increment primary key, proposal_name varchar(200), proposal_type varchar(100), task_id bigint, config_json varchar(1000000), status varchar(30), review_by varchar(64), review_time timestamp, review_comment varchar(100), create_by varchar(64), create_time timestamp, update_by varchar(64), update_time timestamp)");
        jdbc.execute("create table ontology_analysis_job(task_id bigint primary key, run_token varchar(64), status varchar(20), stage varchar(30), request_json clob, checkpoint_json clob, username varchar(64), message varchar(1000), lease_until bigint, model_calls int, proposal_id bigint, created_at timestamp, updated_at timestamp)");
        jdbc.execute("create table ontology_analysis_run(run_id bigint auto_increment primary key, task_id bigint, run_token varchar(64) unique, run_kind varchar(10), source_id bigint, wiki_space_id bigint, selection_json clob, username varchar(64), started_at bigint, finished_at bigint, status varchar(20), stage varchar(30), model_calls int default 0, proposal_id bigint)");
        jdbc.execute("create table ontology_analysis_model_call(call_id bigint auto_increment primary key, task_id bigint, run_token varchar(64), call_number int, stage varchar(30), started_at bigint, finished_at bigint, duration_ms bigint, status varchar(20), failure_type varchar(100), unique(run_token, call_number))");
        jdbc.execute("create table ontology_analysis_stage_timing(timing_id bigint auto_increment primary key, task_id bigint, run_token varchar(64), stage varchar(40), started_at bigint, finished_at bigint, duration_ms bigint, status varchar(20), failure_type varchar(100))");
        jdbc.update("insert into analysis_task(task_id, task_name, source_id, wiki_space_id, scope, status) values (1, '订单分析', 2, 3, '订单业务', 'DRAFT')");
        jdbc.update("insert into ontology_datasource(datasource_id, name, db_type, username, password_enc, sample_limit) values (2, '订单库', 'mysql', 'reader', 'secret-credential', 4)");
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(database);
        factory.setMapperLocations(new ClassPathResource("mapper/ontology/AnalysisJobMapper.xml"), new ClassPathResource("mapper/ontology/OntologyAnalysisMapper.xml"), new ClassPathResource("mapper/ontology/ProposalRevisionMapper.xml"));
        sessions = new SqlSessionTemplate(factory.getObject());
        transactions = new DataSourceTransactionManager(database);
        wiki = new LlmWikiClient(new OntologyIntegrationProperties(), null, null, null, null, null, null) {
            @Override public boolean enabled() { return true; }
            @Override public String confirmedKnowledgeContext(Long taskId, Long spaceId, String question) { return "已确认订单业务"; }
            @Override public String analysisCompletion(String prompt) {
                if (failModelCall) throw new IllegalStateException("模型调用失败");
                return "订单业务分析说明";
            }
            @Override public Map<String,Object> saveAnalysisAnswer(long taskId, String answer, boolean confirm, String username) {
                if (failAnswerSave) throw new IllegalStateException("保存答案失败");
                return Collections.emptyMap();
            }
        };
        store = transactional(new AnalysisJobStore(sessions.getMapper(AnalysisJobMapper.class), sessions.getMapper(OntologyAnalysisMapper.class), wiki, json));
        scanner = new DataSourceScanner(null) {
            @Override public Map<String,Object> scan(Map<String,Object> source, Map<String,Object> options) {
                scans.incrementAndGet();
                lastScanOptions = new LinkedHashMap<>(options);
                return row("tableCount", 1, "tables", Collections.singletonList(row("name", "orders", "columns", Collections.singletonList(row("name", "id", "type", "BIGINT", "nullable", false)), "primaryKeys", Collections.singletonList("id"))));
            }
        };
        knowledge = new AnalysisKnowledgeLoader(null) {
            @Override public List<Map<String,Object>> loadWikiDocuments(Long spaceId) { return Collections.emptyList(); }
            @Override public List<Map<String,Object>> loadBusinessRules(Long spaceId) { return Collections.emptyList(); }
        };
        SemanticEnrichmentService enrichment = new SemanticEnrichmentService() {
            @Override public void describe(Map<String,Object> config, Map<String,Object> schema, String context, Function<String,String> completion) {
                descriptions.incrementAndGet();
                config.put("llmAnswer", completion.apply("describe"));
            }
            @Override public void completeNames(Map<String,Object> config, Map<String,Object> schema, String context, Function<String,String> completion, Consumer<String> progress) {
                progress.accept("3/4 补全中文名称");
                if (cancelDuringNaming) store.cancel(1, "alice");
                if (failNames) throw new IllegalStateException("命名失败");
                completion.apply("name");
            }
        };
        spaces = (WikiChatMapper) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{WikiChatMapper.class}, (proxy, method, arguments) -> {
            if ("countEnabledSpace".equals(method.getName())) return 1;
            throw new AssertionError(method.getName());
        });
        service = new AnalysisJobServiceImpl(store, knowledge, enrichment, scanner, new OntologyGenerationEngine(), sessions.getMapper(OntologyAnalysisMapper.class), wiki, json, executor, spaces, new ProposalChangeService());
    }

    @Test
    public void contextIncludesTaskDatasourceAndIndustryForBothEntryPoints() {
        jdbc.update("insert into ontology_proposal(proposal_id, task_id, config_json) values (9, 1, '{}')");
        OntologyAnalysisMapper mapper = sessions.getMapper(OntologyAnalysisMapper.class);
        for (Map<String, Object> value : Arrays.asList(mapper.selectTaskContext(1L).get(0), mapper.selectTaskContextByProposal(1L, 9L).get(0))) {
            assertEquals("订单分析", value.get("task_name"));
            assertEquals("订单库", value.get("datasource_name"));
            assertEquals("零售行业", value.get("space_name"));
        }
        assertTrue(mapper.selectTaskContextByProposal(1L, 99L).isEmpty());
    }

    @Test
    public void successfulExecutionPublishesExactlyOnceAndHidesCheckpointInternals() {
        service.start(1, Collections.emptyMap(), "alice");
        try { service.start(1, Collections.emptyMap(), "bob"); fail(); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("正在执行")); }
        executor.runNext();
        assertEquals("DONE", service.status(1).get("status"));
        assertEquals(1, jdbc.queryForObject("select count(*) from ontology_proposal", Integer.class).intValue());
        assertEquals(1, scans.get());
        assertFalse(service.status(1).containsKey("checkpointJson"));
        assertFalse(service.status(1).containsKey("requestJson"));
        assertFalse(service.status(1).containsKey("runToken"));
        String checkpoint = jdbc.queryForObject("select checkpoint_json from ontology_analysis_job", String.class);
        assertFalse(checkpoint.contains("secret-credential"));
    }

    @Test
    public void selectedTablesAreTheOnlyScannedObjectsAndDatasourceSampleLimitIsApplied() {
        Map<String,String> request = new LinkedHashMap<>();
        request.put("tables", "orders");
        service.start(1, request, "alice");
        executor.runNext();
        assertEquals(Collections.singleton("orders"), lastScanOptions.get("tables"));
        assertEquals(4, lastScanOptions.get("sampleLimit"));
        assertEquals("DONE", service.status(1).get("status"));
        List<Map<String,Object>> history = (List<Map<String,Object>>) service.history(1, null, 20).get("rows");
        assertTrue(String.valueOf(history.get(0).get("selectionJson")).contains("orders"));
    }

    @Test
    public void modelCallFailuresAndLatencyArePersistedWithoutPromptContent() {
        failModelCall = true;
        service.start(1, Collections.emptyMap(), "alice");
        executor.runNext();
        failModelCall = false;
        service.resume(1, "alice");
        executor.runNext();
        List<Map<String,Object>> runs = (List<Map<String,Object>>) service.history(1, null, 20).get("rows");
        List<Map<String,Object>> failedCalls = service.modelCalls(1, ((Number) runs.get(1).get("runId")).longValue());
        List<Map<String,Object>> successfulCalls = service.modelCalls(1, ((Number) runs.get(0).get("runId")).longValue());
        assertEquals("FAILED", failedCalls.get(0).get("status"));
        assertEquals("IllegalStateException", failedCalls.get(0).get("failureType"));
        assertEquals("SUCCEEDED", successfulCalls.get(0).get("status"));
        assertTrue(((Number) successfulCalls.get(0).get("durationMillis")).longValue() >= 0);
        assertFalse(failedCalls.get(0).containsKey("prompt"));
        List<Map<String,Object>> stageTimings = service.stageTimings(1, ((Number) runs.get(1).get("runId")).longValue());
        assertEquals(Arrays.asList("SCAN", "WIKI_DOCUMENTS", "BUSINESS_RULES", "GENERATION", "MODEL_ENRICHMENT"),
                stageTimings.stream().map(row -> row.get("stage")).collect(java.util.stream.Collectors.toList()));
        assertEquals("FAILED", stageTimings.get(4).get("status"));
        assertEquals("IllegalStateException", stageTimings.get(4).get("failureType"));
        long resumedRunId = ((Number) runs.get(0).get("runId")).longValue();
        List<Map<String,Object>> resumed = service.stageTimings(1, resumedRunId);
        assertEquals(Arrays.asList("MODEL_ENRICHMENT", "NAME_COMPLETION"),
                resumed.stream().map(row -> row.get("stage")).collect(java.util.stream.Collectors.toList()));
        for (Map<String,Object> timing : resumed) {
            assertEquals("SUCCEEDED", timing.get("status"));
            assertTrue(((Number) timing.get("durationMillis")).longValue() >= 0);
        }
        assertTrue(service.stageTimings(2, resumedRunId).isEmpty());
    }

    @Test
    public void failedNamingResumesWithoutRepeatingScanOrDescription() {
        failNames = true;
        service.start(1, Collections.emptyMap(), "alice");
        executor.runNext();
        assertEquals("FAILED", service.status(1).get("status"));
        assertEquals(true, service.status(1).get("resumable"));
        failNames = false;
        service.resume(1, "alice");
        executor.runNext();
        assertEquals("DONE", service.status(1).get("status"));
        assertEquals(1, scans.get());
        assertEquals(1, descriptions.get());
        Map<String, Object> metrics = service.runtimeMetrics();
        assertEquals(2L, ((Map<?,?>) metrics.get("queueWait")).get("count"));
        for (String stage : Arrays.asList("scan", "generation", "wikiDocuments", "businessRules")) {
            assertEquals(1L, ((Map<?,?>) metrics.get(stage)).get("count"));
        }
        assertEquals(2L, ((Map<?,?>) metrics.get("model")).get("count"));
        Map<String,Object> history = service.history(1, null, 20);
        List<Map<String,Object>> runs = (List<Map<String,Object>>) history.get("rows");
        assertEquals(2, runs.size());
        assertEquals("RESUME", runs.get(0).get("runKind"));
        assertEquals("DONE", runs.get(0).get("status"));
        assertEquals(1L, ((Number) runs.get(0).get("proposalId")).longValue());
        assertEquals("START", runs.get(1).get("runKind"));
        assertEquals("FAILED", runs.get(1).get("status"));
        assertEquals(3L, ((Number) runs.get(0).get("wikiSpaceId")).longValue());
        assertFalse(runs.get(0).containsKey("runToken"));
        Map<String,Object> firstPage = service.history(1, null, 1);
        assertEquals(1, ((List<?>) firstPage.get("rows")).size());
        assertNotNull(firstPage.get("nextCursor"));
        Map<String,Object> secondPage = service.history(1, ((Number) firstPage.get("nextCursor")).longValue(), 1);
        assertEquals(1, ((List<?>) secondPage.get("rows")).size());
        assertNull(secondPage.get("nextCursor"));
    }

    @Test
    public void cancelDuringModelWorkPreventsPublicationAndAllowsResume() {
        cancelDuringNaming = true;
        service.start(1, Collections.emptyMap(), "alice");
        executor.runNext();
        assertEquals("CANCELLED", service.status(1).get("status"));
        assertEquals(0, jdbc.queryForObject("select count(*) from ontology_proposal", Integer.class).intValue());
        cancelDuringNaming = false;
        service.resume(1, "alice");
        executor.runNext();
        assertEquals("DONE", service.status(1).get("status"));
    }

    @Test
    public void expiredLeaseCanBeReclaimedButOldWorkerCannotPublish() {
        service.start(1, Collections.emptyMap(), "alice");
        String oldToken = String.valueOf(store.read(1).get("runToken"));
        jdbc.update("update ontology_analysis_job set lease_until = 0");
        assertEquals("INTERRUPTED", service.status(1).get("status"));
        service.resume(1, "alice");
        try { store.requireActive(1, oldToken); fail(); } catch (CancellationException expected) { }
        executor.runNext();
        assertEquals("RUNNING", service.status(1).get("status"));
        executor.runNext();
        assertEquals(1, jdbc.queryForObject("select count(*) from ontology_proposal", Integer.class).intValue());
        List<Map<String,Object>> history = (List<Map<String,Object>>) service.history(1, null, 20).get("rows");
        assertEquals("DONE", history.get(0).get("status"));
        assertEquals("INTERRUPTED", history.get(1).get("status"));
    }

    @Test
    public void changedScopeRefusesCheckpointAndDispatchFailureDoesNotLeaveRunningTask() {
        failNames = true;
        service.start(1, Collections.emptyMap(), "alice");
        executor.runNext();
        jdbc.update("update analysis_task set scope = '另一业务范围'");
        try { service.resume(1, "alice"); fail(); } catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("变化")); }
        rejectExecution = true;
        try { service.start(1, Collections.emptyMap(), "alice"); fail(); } catch (IllegalStateException expected) { }
        assertEquals("FAILED", jdbc.queryForObject("select status from analysis_task", String.class));
        assertEquals(1L, service.runtimeMetrics().get("dispatchFailures"));
    }

    @Test
    public void changedDatasourceLocationRefusesOldCheckpoint() {
        failNames = true;
        service.start(1, Collections.emptyMap(), "alice");
        executor.runNext();
        jdbc.update("update ontology_datasource set database_name = 'different_database'");
        try { service.resume(1, "alice"); fail(); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("变化")); }
        assertEquals(0, jdbc.queryForObject("select count(*) from ontology_proposal", Integer.class).intValue());
    }

    @Test
    public void publicationFailureRollsBackProposalThenResumePublishesOnce() {
        failAnswerSave = true;
        service.start(1, Collections.emptyMap(), "alice");
        executor.runNext();
        assertEquals("FAILED", service.status(1).get("status"));
        assertEquals(0, jdbc.queryForObject("select count(*) from ontology_proposal", Integer.class).intValue());
        failAnswerSave = false;
        service.resume(1, "alice");
        executor.runNext();
        assertEquals(1, jdbc.queryForObject("select count(*) from ontology_proposal", Integer.class).intValue());
        assertEquals(1, scans.get());
    }

    @Test
    public void springWiresAnalysisBoundariesAndTransactionProxies() {
        try (org.springframework.context.annotation.AnnotationConfigApplicationContext context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            org.springframework.beans.factory.config.ConfigurableListableBeanFactory beans = context.getBeanFactory();
            beans.registerSingleton("analysisJobMapper", sessions.getMapper(AnalysisJobMapper.class));
            beans.registerSingleton("ontologyAnalysisMapper", sessions.getMapper(OntologyAnalysisMapper.class));
            beans.registerSingleton("proposalRevisionMapper", sessions.getMapper(ProposalRevisionMapper.class));
            beans.registerSingleton("wiki", wiki);
            beans.registerSingleton("scanner", scanner);
            beans.registerSingleton("knowledge", knowledge);
            beans.registerSingleton("spaces", spaces);
            beans.registerSingleton("generation", new OntologyGenerationEngine());
            beans.registerSingleton("json", json);
            beans.registerSingleton("transactionManager", transactions);
            beans.registerSingleton("ontologyAnalysisExecutor", executor);
            context.register(TransactionConfiguration.class, AnalysisJobServiceImpl.class, AnalysisJobStore.class,
                    SemanticEnrichmentService.class, ProposalChangeService.class, ProposalRevisionService.class);
            context.refresh();
            context.getAutowireCapableBeanFactory().autowireBean(wiki);
            assertNotNull(context.getBean(IAnalysisJobService.class));
            assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(context.getBean(AnalysisJobStore.class)));
            assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(context.getBean(ProposalRevisionService.class)));
        }
    }

    @Test
    public void modelCallBudgetStopsRunAndResumeStartsANewBudget() throws Exception {
        java.lang.reflect.Field budget = AnalysisJobServiceImpl.class.getDeclaredField("maxModelCalls");
        budget.setAccessible(true);
        budget.set(service, 1);
        service.start(1, Collections.emptyMap(), "alice");
        executor.runNext();
        assertEquals("FAILED", service.status(1).get("status"));
        assertTrue(String.valueOf(service.status(1).get("message")).contains("预算"));
        assertEquals(0, jdbc.queryForObject("select count(*) from ontology_proposal", Integer.class).intValue());
        service.resume(1, "alice");
        executor.runNext();
        assertEquals("DONE", service.status(1).get("status"));
        assertEquals(1, descriptions.get());
        assertEquals(1, scans.get());
    }

    @Test
    public void invalidParametersDoNotClaimJobOrChangeTaskStatus() {
        Map<String,String> invalid = new LinkedHashMap<>();
        invalid.put("sampleLimit", "not-a-number");
        try { service.start(1, invalid, "alice"); fail(); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("整数")); }
        assertEquals("DRAFT", jdbc.queryForObject("select status from analysis_task", String.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from ontology_analysis_job", Integer.class).intValue());
    }

    @Test
    public void concurrentClaimsAllowOnlyOneRun() throws Exception {
        java.util.concurrent.ExecutorService workers = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Callable<Boolean> claim = () -> {
                start.await();
                try { store.claim(1, Collections.emptyMap(), "alice", false); return true; }
                catch (IllegalStateException expected) { return false; }
            };
            java.util.concurrent.Future<Boolean> first = workers.submit(claim);
            java.util.concurrent.Future<Boolean> second = workers.submit(claim);
            start.countDown();
            assertNotEquals(first.get(5, java.util.concurrent.TimeUnit.SECONDS), second.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, jdbc.queryForObject("select count(*) from ontology_analysis_job", Integer.class).intValue());
        } finally { workers.shutdownNow(); }
    }

    @Test
    public void addedThenRenamedFieldKeepsItsAddMarkerForReanalysis() throws Exception {
        Map<String,Object> config = row("objects", Collections.singletonList(row("id", "orders", "name", "订单", "table", "orders")), "properties", Collections.emptyList());
        jdbc.update("insert into ontology_proposal(task_id, config_json, status) values (1, ?, 'DRAFT')", json.writeValueAsString(config));
        long proposalId = jdbc.queryForObject("select proposal_id from ontology_proposal", Long.class);
        ProposalChangeService changes = new ProposalChangeService();
        ProposalRevisionService revisions = transactional(new ProposalRevisionService(sessions.getMapper(ProposalRevisionMapper.class), changes, json));
        Map<String,Object> first = revisions.save(1, proposalId, Collections.singletonList(row("op", "ADD", "category", "properties", "value", row("id", "note", "name", "备注", "objectId", "orders", "column", "note", "dataType", "TEXT"))), "alice");
        long firstVersionId = ((Number) first.get("proposalId")).longValue();
        Map<String,Object> updated = revisions.save(1, firstVersionId, Collections.singletonList(row("op", "UPDATE", "category", "properties", "id", "note", "field", "name", "value", "客户备注")), "alice");
        long secondVersionId = ((Number) updated.get("proposalId")).longValue();
        Map<String,Object> saved = json.readValue(jdbc.queryForObject("select config_json from ontology_proposal where proposal_id = ?", String.class, secondVersionId), Map.class);
        assertEquals(3, jdbc.queryForObject("select count(*) from ontology_proposal", Integer.class).intValue());
        assertEquals(2, json.valueToTree(saved).path("analysisCorrections").size());
        assertEquals("ADD", json.valueToTree(saved).path("analysisCorrections").get(0).path("op").asText());
        assertEquals("UPDATE", json.valueToTree(saved).path("analysisCorrections").get(1).path("op").asText());
        Map<String,Object> replayed = changes.preserveCorrections(config, saved);
        assertEquals(1, json.valueToTree(replayed).path("properties").size());
        assertEquals("客户备注", json.valueToTree(replayed).path("properties").get(0).path("name").asText());
        assertEquals("note", json.valueToTree(replayed).path("properties").get(0).path("column").asText());
        assertEquals(0, json.valueToTree(replayed).path("analysisCorrectionConflicts").size());
    }

    @Test
    public void revisionLocksSavesProjectionAndResetsReview() throws Exception {
        Map<String,Object> config = row("objects", Collections.singletonList(row("id", "orders", "name", "订单", "table", "orders")), "properties", Collections.emptyList(), "targetConfig", row("objects", Collections.emptyList()));
        jdbc.update("insert into ontology_proposal(task_id, config_json, status, review_by) values (1, ?, 'COMPLETED', 'reviewer')", json.writeValueAsString(config));
        long proposalId = jdbc.queryForObject("select proposal_id from ontology_proposal", Long.class);
        ProposalRevisionService revisions = transactional(new ProposalRevisionService(sessions.getMapper(ProposalRevisionMapper.class), new ProposalChangeService(), json));
        Map<String,Object> revised = revisions.save(1, proposalId, Collections.singletonList(row("op", "UPDATE", "category", "objects", "id", "orders", "field", "name", "value", "客户订单", "before", "订单")), "alice");
        long revisedId = ((Number) revised.get("proposalId")).longValue();
        assertEquals("COMPLETED", jdbc.queryForObject("select status from ontology_proposal where proposal_id = ?", String.class, proposalId));
        assertEquals("reviewer", jdbc.queryForObject("select review_by from ontology_proposal where proposal_id = ?", String.class, proposalId));
        assertEquals("DRAFT", jdbc.queryForObject("select status from ontology_proposal where proposal_id = ?", String.class, revisedId));
        assertNull(jdbc.queryForObject("select review_by from ontology_proposal where proposal_id = ?", String.class, revisedId));
        String saved = jdbc.queryForObject("select config_json from ontology_proposal where proposal_id = ?", String.class, revisedId);
        assertEquals("客户订单", json.readTree(saved).path("targetConfig").path("objects").get(0).path("name").asText());
        try { revisions.save(9, proposalId, Collections.emptyList(), "alice"); fail(); } catch (IllegalArgumentException expected) { }
        assertEquals(saved, jdbc.queryForObject("select config_json from ontology_proposal where proposal_id = ?", String.class, revisedId));
    }

    @SuppressWarnings("unchecked") private <Target> Target transactional(Target target) {
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (Target) proxy.getProxy();
    }
    private Map<String,Object> row(Object... values) { Map<String,Object> row = new LinkedHashMap<>(); for (int index = 0; index < values.length; index += 2) row.put(String.valueOf(values[index]), values[index + 1]); return row; }
    private class QueueExecutor extends ThreadPoolTaskExecutor {
        private final Queue<Runnable> queue = new ArrayDeque<>();
        @Override public void execute(Runnable task) { if (rejectExecution) throw new IllegalStateException("队列已满"); queue.add(task); }
        void runNext() { queue.remove().run(); }
    }

    @org.springframework.context.annotation.Configuration
    @org.springframework.transaction.annotation.EnableTransactionManagement
    public static class TransactionConfiguration { }
}

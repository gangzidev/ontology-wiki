package com.ontologywiki.ontology.service.analysis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ontologywiki.ontology.mapper.OntologyAnalysisMapper;
import com.ontologywiki.ontology.mapper.WikiChatMapper;
import com.ontologywiki.common.exception.ServiceException;
import com.ontologywiki.ontology.service.DataSourceScanner;
import com.ontologywiki.ontology.service.LlmWikiClient;
import com.ontologywiki.ontology.service.OntologyGenerationEngine;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

@Service
public class AnalysisJobServiceImpl implements IAnalysisJobService {
    private static final String RUNNING = "RUNNING";
    private static final String DONE = "DONE";
    private static final String FAILED = "FAILED";
    private final AnalysisJobStore jobs;
    private final AnalysisKnowledgeLoader knowledge;
    private final SemanticEnrichmentService enrichment;
    private final DataSourceScanner scanner;
    private final OntologyGenerationEngine generation;
    private final OntologyAnalysisMapper analysisMapper;
    private final LlmWikiClient llmWiki;
    private final ObjectMapper json;
    private final ThreadPoolTaskExecutor executor;
    private final WikiChatMapper spaces;
    private final ProposalChangeService corrections;
    @Value("${ontology.analysis.max-model-calls:200}")
    private int maxModelCalls = 200;
    @Value("${ontology.analysis.max-duration-minutes:30}")
    private int maxDurationMinutes = 30;

    public AnalysisJobServiceImpl(AnalysisJobStore jobs, AnalysisKnowledgeLoader knowledge,
                                  SemanticEnrichmentService enrichment, DataSourceScanner scanner,
                                  OntologyGenerationEngine generation, OntologyAnalysisMapper analysisMapper,
                                  LlmWikiClient llmWiki, ObjectMapper json,
                                  @Qualifier("ontologyAnalysisExecutor") ThreadPoolTaskExecutor executor,
                                  WikiChatMapper spaces, ProposalChangeService corrections) {
        this.jobs = jobs;
        this.knowledge = knowledge;
        this.enrichment = enrichment;
        this.scanner = scanner;
        this.generation = generation;
        this.analysisMapper = analysisMapper;
        this.llmWiki = llmWiki;
        this.json = json;
        this.executor = executor;
        this.spaces = spaces;
        this.corrections = corrections;
    }

    @Override
    public Map<String, Object> start(long taskId, Map<String, String> request, String username) {
        Map<String,String> accepted = new LinkedHashMap<>();
        if (request != null) for (String key : Arrays.asList("mode", "question", "sampleLimit", "queryPatterns", "stages", "tables")) if (request.get(key) != null) accepted.put(key, request.get(key));
        return submit(taskId, accepted, username, false);
    }

    @Override
    public Map<String, Object> resume(long taskId, String username) {
        return submit(taskId, Collections.<String, String>emptyMap(), username, true);
    }

    @Override
    public Map<String, Object> cancel(long taskId, String username) {
        jobs.cancel(taskId, username);
        return status(taskId);
    }

    @Override
    public Map<String, Object> status(long taskId) {
        Map<String, Object> job = jobs.read(taskId);
        if (job != null) {
            Map<String, Object> result = new LinkedHashMap<>(job);
            result.remove("runToken");
            result.remove("requestJson");
            result.remove("checkpointJson");
            boolean expired = RUNNING.equals(job.get("status")) && number(job.get("leaseUntil")) <= System.currentTimeMillis();
            if (expired) result.put("status", "INTERRUPTED");
            if (expired) result.put("message", "分析执行已中断或超时，可从检查点继续；也可取消后重新分析。");
            result.put("resumable", !DONE.equals(job.get("status")) && (!RUNNING.equals(job.get("status")) || expired));
            return result;
        }
        Map<String, Object> task = analysisMapper.selectTask(taskId);
        if (task == null) throw new IllegalArgumentException("分析任务不存在");
        return new LinkedHashMap<>(task);
    }

    private Map<String, Object> submit(long taskId, Map<String, String> request, String username, boolean resume) {
        Map<String,Object> task = requireTask(taskId);
        if (!llmWiki.enabled()) throw new ServiceException("尚未配置可用的大模型，请先在模型配置中启用模型", 603);
        if (!resume) validateRequest(taskId, request);
        else {
            Map<String,Object> prior = jobs.read(taskId);
            if (prior != null) {
                Map<String,Object> checkpoint = parseMap(prior.get("checkpointJson"));
                if (checkpoint.containsKey("taskSignature") && !Objects.equals(checkpoint.get("taskSignature"), signature(task))) throw new IllegalArgumentException("任务数据源、知识空间或业务范围已变化，请重新分析，不可恢复旧检查点");
            }
        }
        Map<String, Object> job = jobs.claim(taskId, request, username, resume);
        try {
            executor.execute(() -> run(job));
        } catch (RuntimeException rejected) {
            jobs.stop(taskId, String.valueOf(job.get("runToken")), FAILED, "分析任务调度失败，请稍后重试。", username);
            throw rejected;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("async", true);
        result.put("taskId", taskId);
        result.put("status", RUNNING);
        result.put("message", job.get("message"));
        return result;
    }

    private void run(Map<String, Object> job) {
        long taskId = number(job.get("taskId"));
        String token = String.valueOf(job.get("runToken"));
        String username = String.valueOf(job.get("username"));
        Map<String,Object> checkpoint = new LinkedHashMap<>();
        AtomicInteger calls = new AtomicInteger();
        long deadline = System.currentTimeMillis() + Math.max(1, maxDurationMinutes) * 60000L;
        try {
            checkpoint.putAll(parseMap(job.get("checkpointJson")));
            Map<String, String> request = parseRequest(job.get("requestJson"));
            Map<String, Object> task = requireTask(taskId);
            if (checkpoint.containsKey("taskSignature") && !Objects.equals(checkpoint.get("taskSignature"), signature(task))) throw new IllegalArgumentException("任务范围已变化，请重新分析");
            checkpoint.put("taskSignature", signature(task));
            long wikiSpaceId = number(task.get("wiki_space_id"));
            jobs.requireActive(taskId, token);
            if (!(checkpoint.get("config") instanceof Map)) {
                Map<String,Object> previous = latestConfig(taskId);
                boolean incremental = "INCREMENTAL".equalsIgnoreCase(text(request.get("mode")));
                Set<String> changedTables = AnalysisInput.requestedTableNames(request.get("tables"));
                Map<String, Object> source = analysisMapper.selectDatasource(number(task.get("source_id")));
                Map<String,Object> options = new LinkedHashMap<>();
                if (!changedTables.isEmpty()) options.put("tables", changedTables);
                if (!incremental && !changedTables.isEmpty()) options.put("sampleLimit", boundedInt(request.get("sampleLimit"), 20));
                Map<String,Object> schema = scanner.scan(source, options);
                if (incremental) schema = generation.mergeSchemaSnapshot(map(previous.get("schemaSnapshot")), schema, changedTables);
                jobs.requireActive(taskId, token);
                List<String> patterns = request.get("queryPatterns") == null ? AnalysisInput.queryPatternsFromScope(task.get("scope")) : Arrays.asList(request.get("queryPatterns").split("\\r?\\n"));
                Map<String,Object> generated = generation.generate(schema, knowledge.loadWikiDocuments(wikiSpaceId), knowledge.loadBusinessRules(wikiSpaceId), patterns, knowledge.parseStages(request.get("stages")));
                generated.put("schemaSnapshot", generation.schemaMetadataSnapshot(schema));
                checkpoint.put("config", corrections.preserveCorrections(generated, previous));
                checkpoint.put("previousConfig", previous);
                checkpoint.put("schema", generation.schemaMetadataSnapshot(schema));
                checkpoint.put("context", "业务范围：" + AnalysisInput.scopeForPrompt(task.get("scope")) + "\n分析问题：" + text(request.get("question"))
                        + "\n已确认业务知识：" + clip(llmWiki.confirmedKnowledgeContext(taskId, wikiSpaceId, request.get("question"))));
                checkpoint.put("stage", "GENERATED");
                jobs.checkpoint(taskId, token, checkpoint, "3/4 已保存结构检查点，正在补充业务语义…", calls.get());
            }
            Map<String,Object> config = map(checkpoint.get("config"));
            Function<String,String> model = prompt -> {
                jobs.requireActive(taskId, token);
                if (calls.get() >= maxModelCalls || System.currentTimeMillis() >= deadline) throw new CancellationException("本次分析已达到时间或模型调用预算，可从检查点恢复");
                calls.incrementAndGet();
                jobs.checkpoint(taskId, token, checkpoint, "3/4 正在调用模型，第 " + calls.get() + " 次…", calls.get());
                String result = llmWiki.analysisCompletion(prompt);
                jobs.requireActive(taskId, token);
                return result;
            };
            if ("GENERATED".equals(checkpoint.get("stage"))) {
                enrichment.describe(config, map(checkpoint.get("schema")), text(checkpoint.get("context")), model);
                checkpoint.put("stage", "DESCRIBED");
                jobs.checkpoint(taskId, token, checkpoint, "3/4 已保存模型分析检查点，正在补全中文名称…", calls.get());
            }
            if (!"READY".equals(checkpoint.get("stage"))) {
                checkpoint.put("stage", "NAMING");
                enrichment.completeNames(config, map(checkpoint.get("schema")), text(checkpoint.get("context")), model,
                        message -> jobs.checkpoint(taskId, token, checkpoint, message, calls.get()));
                checkpoint.put("stage", "READY");
                jobs.checkpoint(taskId, token, checkpoint, "4/4 中文语义分析完成，正在保存待审核配置草案…", calls.get());
            }
            jobs.requireActive(taskId, token);
            jobs.complete(taskId, token, config);
        } catch (CancellationException cancelled) {
            jobs.stop(taskId, token, FAILED, cancelled.getMessage(), username);
        } catch (Exception failure) {
            try { jobs.stop(taskId, token, FAILED, clip(failure.getMessage() == null ? "分析失败，请检查数据源和模型服务。" : failure.getMessage()), username); }
            catch (Exception ignored) { }
        }
    }

    private Map<String,Object> latestConfig(long taskId) {
        List<Map<String, Object>> previous = analysisMapper.selectLatestProposalWithConfig(taskId);
        return previous.isEmpty() ? Collections.emptyMap() : parseMap(previous.get(0).get("config_json"));
    }

    private void validateRequest(long taskId, Map<String,String> request) {
        String mode = text(request.get("mode")).toUpperCase(Locale.ROOT);
        if (!mode.isEmpty() && !Arrays.asList("FULL", "INCREMENTAL").contains(mode)) throw new IllegalArgumentException("分析模式无效");
        Set<String> tables = AnalysisInput.requestedTableNames(request.get("tables"));
        if (tables.size() > 20) throw new IllegalArgumentException("单次最多选择20张表");
        if ("INCREMENTAL".equals(mode) && (tables.isEmpty() || !(latestConfig(taskId).get("schemaSnapshot") instanceof Map))) throw new IllegalArgumentException("增量更新需要变化表和已有结构快照，请先执行全量分析");
        if (request.get("sampleLimit") != null) try { Integer.parseInt(request.get("sampleLimit")); } catch (NumberFormatException failure) { throw new IllegalArgumentException("样例条数必须是整数"); }
    }

    private Map<String,Object> requireTask(long taskId) {
        Map<String,Object> task = analysisMapper.selectTask(taskId);
        if (task == null) throw new IllegalArgumentException("分析任务不存在");
        if (!(task.get("source_id") instanceof Number)) throw new IllegalArgumentException("分析任务未配置数据源");
        if (!(task.get("wiki_space_id") instanceof Number)) throw new IllegalArgumentException("该任务未绑定行业知识空间，请先编辑任务并选择行业");
        if (spaces.countEnabledSpace(number(task.get("wiki_space_id")), "0") == 0) throw new IllegalArgumentException("分析任务关联的行业知识空间不存在或已停用");
        if (analysisMapper.selectDatasource(number(task.get("source_id"))) == null) throw new IllegalArgumentException("数据源不存在");
        return task;
    }

    private Map<String,Object> signature(Map<String,Object> task) {
        Map<String,Object> signature = new LinkedHashMap<>();
        for (String key : Arrays.asList("source_id", "wiki_space_id", "scope")) signature.put(key, text(task.get(key)));
        Map<String,Object> source = analysisMapper.selectDatasource(number(task.get("source_id")));
        Map<String,Object> location = new LinkedHashMap<>();
        for (String key : Arrays.asList("db_type", "host", "port", "database_name", "schema_name", "jdbc_url", "username")) location.put(key, text(source.get(key)));
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(location));
            signature.put("sourceFingerprint", Base64.getEncoder().encodeToString(digest));
        } catch (Exception failure) { throw new IllegalStateException("无法校验分析数据源版本", failure); }
        return signature;
    }

    @SuppressWarnings("unchecked")
    private Map<String,Object> map(Object raw) { return raw instanceof Map ? (Map<String,Object>) raw : new LinkedHashMap<>(); }
    private Map<String,Object> parseMap(Object raw) {
        try { return raw == null ? new LinkedHashMap<>() : json.readValue(String.valueOf(raw), new TypeReference<Map<String,Object>>() {}); }
        catch (Exception failure) { throw new IllegalArgumentException("分析检查点或历史草案无法解析", failure); }
    }

    private Map<String, String> parseRequest(Object raw) {
        try { return raw == null ? new LinkedHashMap<>() : json.readValue(String.valueOf(raw), new TypeReference<Map<String, String>>() {}); }
        catch (Exception failure) { throw new IllegalArgumentException("分析请求无法恢复", failure); }
    }

    private int boundedInt(Object value, int fallback) { try { return Math.max(0, Math.min(value == null ? fallback : Integer.parseInt(String.valueOf(value)), 20)); } catch (Exception ignored) { return fallback; } }
    private long number(Object value) { return value instanceof Number ? ((Number) value).longValue() : Long.parseLong(String.valueOf(value)); }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private String clip(String value) { return value == null ? "" : value.substring(0, Math.min(value.length(), 1000)); }
}

package com.ontologywiki.ontology.service.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ontologywiki.ontology.mapper.AnalysisJobMapper;
import com.ontologywiki.ontology.mapper.OntologyAnalysisMapper;
import com.ontologywiki.ontology.service.LlmWikiClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.concurrent.CancellationException;

@Service
public class AnalysisJobStore {
    private static final long LEASE_MILLIS = 15 * 60 * 1000;
    private final AnalysisJobMapper mapper;
    private final OntologyAnalysisMapper proposals;
    private final LlmWikiClient wiki;
    private final ObjectMapper json;

    public AnalysisJobStore(AnalysisJobMapper mapper, OntologyAnalysisMapper proposals, LlmWikiClient wiki, ObjectMapper json) {
        this.mapper = mapper;
        this.proposals = proposals;
        this.wiki = wiki;
        this.json = json;
    }

    public Map<String,Object> read(long taskId) {
        try { return mapper.selectJob(taskId); }
        catch (org.springframework.jdbc.BadSqlGrammarException failure) { throw new IllegalStateException("分析任务存储未就绪，请先执行 backend/sql/migrate-analysis-job.sql", failure); }
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String,Object> claim(long taskId, Map<String,String> request, String username, boolean resume) {
        Map<String,Object> task = mapper.lockTask(taskId);
        if (task == null) throw new IllegalArgumentException("分析任务不存在");
        Map<String,Object> prior = read(taskId);
        if (prior != null && "RUNNING".equals(prior.get("status")) && number(prior.get("leaseUntil")) > System.currentTimeMillis()) throw new IllegalStateException("该分析任务正在执行");
        if (resume && (prior == null || "DONE".equals(prior.get("status")))) throw new IllegalArgumentException("没有可恢复的分析任务，请重新执行分析");
        if (!resume && "RUNNING".equals(task.get("status"))) throw new IllegalStateException("任务仍处于执行状态，请先取消或恢复任务");
        Map<String,Object> job = new LinkedHashMap<>();
        job.put("taskId", taskId);
        job.put("runToken", UUID.randomUUID().toString());
        job.put("stage", resume ? prior.get("stage") : "QUEUED");
        job.put("requestJson", resume ? prior.get("requestJson") : write(request));
        job.put("checkpointJson", resume ? prior.get("checkpointJson") : "{}");
        job.put("username", username);
        job.put("message", resume ? "1/4 正在恢复已保存的分析检查点…" : "1/4 正在连接数据源，读取表结构和字段…");
        job.put("leaseUntil", System.currentTimeMillis() + LEASE_MILLIS);
        if (prior == null) mapper.insertJob(job); else mapper.claimJob(job);
        mapper.updateTask(taskId, "RUNNING", String.valueOf(job.get("message")), username);
        return mapper.selectJob(taskId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void cancel(long taskId, String username) {
        Map<String,Object> task = mapper.lockTask(taskId);
        if (task == null) throw new IllegalArgumentException("分析任务不存在");
        Map<String,Object> job = mapper.lockJob(taskId);
        if (job == null) {
            if ("RUNNING".equals(task.get("status"))) mapper.updateTask(taskId, "CANCELLED", "旧分析任务已取消，请重新执行分析。", username);
            return;
        }
        if (!"RUNNING".equals(job.get("status"))) return;
        job.put("status", "CANCELLED");
        job.put("message", "分析已取消，可恢复检查点后继续。");
        job.put("proposalId", null);
        mapper.finish(job);
        mapper.updateTask(taskId, "CANCELLED", String.valueOf(job.get("message")), username);
    }

    public void requireActive(long taskId, String token) {
        Map<String,Object> job = read(taskId);
        if (!active(job, token) || number(job.get("leaseUntil")) <= System.currentTimeMillis()) throw new CancellationException("分析已取消、租约已过期或已被新的执行接管");
    }

    @Transactional(rollbackFor = Exception.class)
    public void checkpoint(long taskId, String token, Map<String,Object> checkpoint, String message, int calls) {
        mapper.lockTask(taskId);
        Map<String,Object> job = mapper.lockJob(taskId);
        if (!active(job, token)) throw new CancellationException("分析已取消或被新的执行接管");
        job.put("stage", checkpoint.getOrDefault("stage", "QUEUED"));
        job.put("checkpointJson", write(checkpoint));
        job.put("message", clip(message));
        job.put("now", System.currentTimeMillis());
        job.put("leaseUntil", System.currentTimeMillis() + LEASE_MILLIS);
        job.put("modelCalls", calls);
        if (mapper.checkpoint(job) != 1) throw new CancellationException("分析执行租约已失效");
        mapper.updateTask(taskId, "RUNNING", clip(message), String.valueOf(job.get("username")));
    }

    @Transactional(rollbackFor = Exception.class)
    public void stop(long taskId, String token, String status, String message, String username) {
        mapper.lockTask(taskId);
        Map<String,Object> job = mapper.lockJob(taskId);
        if (!active(job, token)) return;
        job.put("status", status);
        job.put("message", clip(message));
        job.put("proposalId", null);
        if (mapper.finish(job) == 1) mapper.updateTask(taskId, status, clip(message), username);
    }

    @Transactional(rollbackFor = Exception.class)
    public long complete(long taskId, String token, Map<String,Object> config) {
        Map<String,Object> task = mapper.lockTask(taskId);
        Map<String,Object> job = mapper.lockJob(taskId);
        if (!active(job, token) || number(job.get("leaseUntil")) <= System.currentTimeMillis()) throw new CancellationException("分析已取消或租约已失效，结果不会发布");
        String username = String.valueOf(job.get("username"));
        String answer = String.valueOf(config.getOrDefault("llmAnswer", "分析完成"));
        Map<String,Object> proposal = new LinkedHashMap<>();
        proposal.put("proposalName", task.get("taskName") + " 配置草案");
        proposal.put("taskId", taskId);
        proposal.put("configJson", write(config));
        proposal.put("username", username);
        proposal.put("generatedId", null);
        proposals.insertProposal(proposal);
        long proposalId = ((Number) proposal.get("generatedId")).longValue();
        wiki.saveAnalysisAnswer(taskId, answer, false, username);
        job.put("status", "DONE");
        job.put("message", "4/4 分析完成，配置草案已保存");
        job.put("proposalId", proposalId);
        mapper.finish(job);
        mapper.updateTask(taskId, "DONE", answer.length() > 60000 ? answer.substring(0, 60000) : answer, username);
        return proposalId;
    }

    private boolean active(Map<String,Object> job, String token) { return job != null && "RUNNING".equals(job.get("status")) && Objects.equals(token, job.get("runToken")); }
    private long number(Object value) { return value instanceof Number ? ((Number) value).longValue() : 0; }
    private String clip(String value) { return value == null ? "" : value.substring(0, Math.min(value.length(), 1000)); }
    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception failure) { throw new IllegalArgumentException("无法保存分析检查点", failure); }
    }
}

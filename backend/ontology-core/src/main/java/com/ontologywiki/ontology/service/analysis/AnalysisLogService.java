package com.ontologywiki.ontology.service.analysis;

import com.ontologywiki.ontology.mapper.AnalysisLogMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class AnalysisLogService {
    private final AnalysisLogMapper mapper;

    public AnalysisLogService(AnalysisLogMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 记录分析日志
     */
    public void log(long taskId, String runToken, String level, String category, String stage, String message, Map<String, Object> detail) {
        Map<String, Object> log = new LinkedHashMap<>();
        log.put("taskId", taskId);
        log.put("runToken", runToken);
        log.put("logLevel", level);
        log.put("logCategory", category);
        log.put("stage", stage);
        log.put("message", message);
        log.put("detailJson", detail == null ? null : toJson(detail));
        log.put("createdAt", System.currentTimeMillis());
        try {
            mapper.insertLog(log);
        } catch (Exception e) {
            // 日志记录失败不应影响主流程
            System.err.println("Failed to log analysis message: " + message + ", error: " + e.getMessage());
        }
    }

    /**
     * 记录INFO级别日志
     */
    public void info(long taskId, String runToken, String category, String stage, String message) {
        log(taskId, runToken, "INFO", category, stage, message, null);
    }

    /**
     * 记录INFO级别日志（带详情）
     */
    public void info(long taskId, String runToken, String category, String stage, String message, Map<String, Object> detail) {
        log(taskId, runToken, "INFO", category, stage, message, detail);
    }

    /**
     * 记录WARN级别日志
     */
    public void warn(long taskId, String runToken, String category, String stage, String message) {
        log(taskId, runToken, "WARN", category, stage, message, null);
    }

    /**
     * 记录ERROR级别日志
     */
    public void error(long taskId, String runToken, String category, String stage, String message) {
        log(taskId, runToken, "ERROR", category, stage, message, null);
    }

    /**
     * 获取执行批次的所有日志
     */
    public List<Map<String, Object>> getLogs(long taskId, String runToken) {
        try {
            return mapper.selectLogs(taskId, runToken);
        } catch (org.springframework.jdbc.BadSqlGrammarException e) {
            throw new IllegalStateException("请先执行数据库迁移脚本：migrate-analysis-log.sql");
        }
    }

    private String toJson(Map<String, Object> detail) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(detail);
        } catch (Exception e) {
            return "{}";
        }
    }
}

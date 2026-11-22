package com.ontologywiki.ontology.service.analysis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ontologywiki.ontology.mapper.ProposalRevisionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class ProposalRevisionService {
    private final ProposalRevisionMapper mapper;
    private final ProposalChangeService changes;
    private final ObjectMapper json;

    public ProposalRevisionService(ProposalRevisionMapper mapper, ProposalChangeService changes, ObjectMapper json) {
        this.mapper = mapper;
        this.changes = changes;
        this.json = json;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> save(long taskId, long proposalId, List<?> requested, String username) {
        Map<String, Object> proposal = mapper.selectForUpdate(taskId, proposalId);
        if (proposal == null) throw new IllegalArgumentException("配置草案不存在或不属于当前分析任务");
        try {
            Map<String, Object> base = json.readValue(String.valueOf(proposal.get("configJson")), new TypeReference<Map<String, Object>>() {});
            List<Map<String, Object>> normalized = changes.normalizeAnalysisChanges(base, requested, true);
            Map<String, Object> updated = changes.applyAnalysisChanges(base, normalized);
            updated.put("analysisCorrections", changes.mergeAnalysisChangeMarkers(base, changes.recordedChanges(base), normalized));
            String name = String.valueOf(proposal.getOrDefault("proposalName", "配置草案"));
            Map<String, Object> version = new HashMap<>();
            version.put("taskId", taskId);
            version.put("proposalName", name + " · 问答修订");
            version.put("configJson", json.writeValueAsString(updated));
            version.put("username", username);
            mapper.insertVersion(version);
            updated.put("proposalId", version.get("generatedId"));
            return updated;
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalArgumentException("分析草案结构无法解析或保存", failure);
        }
    }
}

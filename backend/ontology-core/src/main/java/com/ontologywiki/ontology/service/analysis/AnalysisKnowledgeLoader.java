package com.ontologywiki.ontology.service.analysis;

import com.ontologywiki.ontology.mapper.WikiAssetMapper;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class AnalysisKnowledgeLoader {
    private static final String STATUS_ACTIVE = "0";
    private static final int ANALYZE_DOCUMENT_LIMIT = 100;
    private static final List<String> BUSINESS_RULE_ASSET_TYPES = Arrays.asList("RULE", "BUSINESS_RULE", "LOGIC", "业务规则");
    private final WikiAssetMapper assetMapper;
    public AnalysisKnowledgeLoader(WikiAssetMapper assetMapper) { this.assetMapper = assetMapper; }
    /** 只把启用且有内容的 Wiki 资产送入生成上下文，避免一次分析把整个 Wiki 塞进模型。 */
    public List<Map<String, Object>> loadWikiDocuments(Long spaceId) {
        if (spaceId == null) return Collections.emptyList();
        List<Map<String,Object>> rows = assetMapper.selectAnalyzeDocuments(STATUS_ACTIVE, spaceId, ANALYZE_DOCUMENT_LIMIT);
        return rows.stream().map(row -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sourceId", row.get("sourceId"));
            item.put("title", clip(String.valueOf(row.get("title")), 200));
            item.put("domain", row.get("domain"));
            item.put("content", clip(String.valueOf(row.get("content")), 12000));
            return item;
        }).collect(java.util.stream.Collectors.toList());
    }

    public List<Map<String, Object>> loadBusinessRules(Long spaceId) {
        if (spaceId == null) return Collections.emptyList();
        List<Map<String,Object>> rows = assetMapper.selectAnalyzeRules(STATUS_ACTIVE, spaceId,
                BUSINESS_RULE_ASSET_TYPES, ANALYZE_DOCUMENT_LIMIT);
        return rows.stream().map(row -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sourceId", row.get("sourceId"));
            item.put("title", clip(String.valueOf(row.get("title")), 200));
            item.put("content", clip(String.valueOf(row.get("content")), 12000));
            return item;
        }).collect(java.util.stream.Collectors.toList());
    }

    public Set<Integer> parseStages(String value) {
        if (value == null || value.trim().isEmpty()) return new LinkedHashSet<>();
        Set<Integer> stages = new LinkedHashSet<>();
        for (String part : value.split(",")) {
            try {
                int stage = Integer.parseInt(part.trim());
                if (stage >= 1 && stage <= 8) stages.add(stage);
            } catch (NumberFormatException ignored) { }
        }
        return stages;
    }
    private String clip(String value, int max) { return value == null ? "" : value.substring(0, Math.min(value.length(), max)); }
}

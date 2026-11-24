package com.ontologywiki.ontology.service.analysis;

import com.ontologywiki.ontology.mapper.WikiAssetMapper;
import com.ontologywiki.ontology.service.WikiLexicalRetriever;
import com.ontologywiki.ontology.service.wiki.WikiChunkIndexService;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class AnalysisKnowledgeLoader {
    private static final String STATUS_ACTIVE = "0";
    private static final int ANALYZE_DOCUMENT_LIMIT = 24;
    private static final List<String> BUSINESS_RULE_ASSET_TYPES = Arrays.asList("RULE", "BUSINESS_RULE", "LOGIC", "业务规则");
    private final WikiAssetMapper assetMapper;
    private final WikiChunkIndexService chunkIndexService;
    private final WikiLexicalRetriever retriever = new WikiLexicalRetriever();
    public AnalysisKnowledgeLoader(WikiAssetMapper assetMapper) {
        this(assetMapper, null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public AnalysisKnowledgeLoader(WikiAssetMapper assetMapper, WikiChunkIndexService chunkIndexService) {
        this.assetMapper = assetMapper;
        this.chunkIndexService = chunkIndexService;
    }
    public List<Map<String, Object>> loadWikiDocuments(Long spaceId) {
        if (chunkIndexService != null) return loadWikiDocuments(spaceId, "");
        if (spaceId == null) return Collections.emptyList();
        return assetMapper.selectAnalyzeDocuments(STATUS_ACTIVE, spaceId, ANALYZE_DOCUMENT_LIMIT);
    }
    /** 只返回与任务范围和已选对象相关的证据块。 */
    public List<Map<String, Object>> loadWikiDocuments(Long spaceId, String query) {
        if (spaceId == null) return Collections.emptyList();
        if (chunkIndexService == null) return loadWikiDocuments(spaceId);
        return toDocuments(relevantHits(spaceId, query, null));
    }

    public List<Map<String, Object>> loadBusinessRules(Long spaceId) {
        if (chunkIndexService != null) return loadBusinessRules(spaceId, "");
        if (spaceId == null) return Collections.emptyList();
        return assetMapper.selectAnalyzeRules(STATUS_ACTIVE, spaceId, BUSINESS_RULE_ASSET_TYPES, ANALYZE_DOCUMENT_LIMIT);
    }
    public List<Map<String, Object>> loadBusinessRules(Long spaceId, String query) {
        if (spaceId == null) return Collections.emptyList();
        if (chunkIndexService == null) return loadBusinessRules(spaceId);
        return toDocuments(relevantHits(spaceId, query, BUSINESS_RULE_ASSET_TYPES));
    }

    private List<Map<String,Object>> relevantHits(Long spaceId, String query, List<String> assetTypes) {
        List<Map<String,Object>> indexed = chunkIndexService.searchForAnalysis(query, spaceId,
                assetTypes, ANALYZE_DOCUMENT_LIMIT);
        if (!indexed.isEmpty()) return indexed;
        List<String> terms = retriever.searchTerms(query);
        if (terms.isEmpty()) return Collections.emptyList();
        List<Map<String,Object>> candidates = assetMapper.selectEvidenceCandidates(terms, false, null,
                spaceId, 100);
        if (assetTypes != null) candidates.removeIf(row -> !assetTypes.contains(String.valueOf(row.get("asset_type"))));
        return retriever.evidence(candidates, terms, ANALYZE_DOCUMENT_LIMIT);
    }

    private List<Map<String,Object>> toDocuments(List<Map<String,Object>> hits) {
        return hits.stream().map(row -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sourceId", row.get("assetId"));
            item.put("title", clip(String.valueOf(row.get("title")), 200));
            item.put("domain", row.get("domain"));
            item.put("content", clip(String.valueOf(row.get("excerpt")), 1600));
            item.put("paragraph", row.get("paragraph"));
            item.put("version", row.get("version"));
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

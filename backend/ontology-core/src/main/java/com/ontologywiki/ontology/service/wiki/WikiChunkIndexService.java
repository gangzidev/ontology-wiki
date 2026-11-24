package com.ontologywiki.ontology.service.wiki;

import com.ontologywiki.ontology.mapper.WikiChunkMapper;
import com.ontologywiki.ontology.service.WikiLexicalRetriever;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class WikiChunkIndexService {
    private static final int CHUNK_LENGTH = 1000;
    private static final int CHUNK_OVERLAP = 80;
    private static final Pattern PARAGRAPH_BREAK = Pattern.compile("\\r?\\n[ \\t]*\\r?\\n");

    private final WikiChunkMapper mapper;

    public WikiChunkIndexService(WikiChunkMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public void refreshAsset(long assetId) {
        Map<String, Object> asset = mapper.selectAsset(assetId);
        mapper.deleteAssetChunks(assetId);
        Long spaceId = asset != null && asset.get("space_id") instanceof Number
                ? ((Number) asset.get("space_id")).longValue() : null;
        if (asset == null) {
            mapper.replaceIndexState(assetId, null, null, "DELETED", 0);
            return;
        }
        if (!"0".equals(String.valueOf(asset.get("status")))) {
            mapper.replaceIndexState(assetId, spaceId, null, "INACTIVE", 0);
            return;
        }
        if ("SYSTEM_INDEX".equals(asset.get("tags"))) {
            mapper.replaceIndexState(assetId, spaceId, null, "SKIPPED", 0);
            return;
        }
        String content = value(asset.get("content"));
        if (content.trim().isEmpty()) {
            mapper.replaceIndexState(assetId, spaceId, null, "EMPTY", 0);
            return;
        }
        String version = sha256(content);
        String title = value(asset.get("asset_name"));
        String sourceRef = value(asset.get("tags"));
        int chunkIndex = 0;
        int paragraphNo = 0;
        Matcher breaks = PARAGRAPH_BREAK.matcher(content);
        int start = 0;
        while (start < content.length()) {
            boolean hasBreak = breaks.find();
            int end = hasBreak ? breaks.start() : content.length();
            while (start < end && Character.isWhitespace(content.charAt(start))) start++;
            while (end > start && Character.isWhitespace(content.charAt(end - 1))) end--;
            if (end > start) paragraphNo++;
            for (int pos = start; pos < end;) {
                int next = Math.min(pos + CHUNK_LENGTH, end);
                if (next < end && Character.isLowSurrogate(content.charAt(next))) next--;
                mapper.insertChunk(spaceId, assetId, title, sourceRef, version, chunkIndex++, paragraphNo, pos,
                        content.substring(pos, next));
                if (next == end) break;
                pos = next - CHUNK_OVERLAP;
                if (Character.isLowSurrogate(content.charAt(pos))) pos--;
            }
            start = hasBreak ? breaks.end() : content.length();
        }
        mapper.replaceIndexState(assetId, spaceId, version, "INDEXED", chunkIndex);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> rebuildBatch(long afterAssetId, int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 200));
        List<Map<String, Object>> assets = mapper.selectAssetsAfter(afterAssetId, limit);
        long cursor = afterAssetId;
        for (Map<String, Object> asset : assets) {
            cursor = ((Number) asset.get("asset_id")).longValue();
            refreshAsset(cursor);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("processed", assets.size());
        result.put("afterAssetId", cursor);
        result.put("complete", assets.size() < limit);
        return result;
    }

    public List<Map<String, Object>> search(List<String> terms, boolean assetScope, Long scopeId,
                                            Long spaceId, int limit) {
        return search(terms, assetScope, scopeId, spaceId, null, limit);
    }

    public List<Map<String, Object>> searchForAnalysis(String query, Long spaceId,
                                                       List<String> assetTypes, int limit) {
        List<String> terms = new WikiLexicalRetriever().searchTerms(query);
        return search(terms, false, null, spaceId, assetTypes, limit);
    }

    private List<Map<String, Object>> search(List<String> terms, boolean assetScope, Long scopeId,
                                             Long spaceId, List<String> assetTypes, int limit) {
        if (terms == null || terms.isEmpty()) return new ArrayList<>();
        List<Map<String, Object>> rows = mapper.search(terms, assetScope, scopeId, spaceId, assetTypes,
                Math.max(limit * 4, limit));
        Map<Long, Map<String, Object>> bestByAsset = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            long assetId = ((Number) row.get("asset_id")).longValue();
            if (!bestByAsset.containsKey(assetId)) {
                Map<String, Object> hit = new LinkedHashMap<>();
                hit.put("assetId", assetId);
                hit.put("title", row.get("asset_name"));
                hit.put("assetType", row.get("asset_type"));
                hit.put("paragraph", row.get("paragraph_no"));
                hit.put("offset", row.get("start_offset"));
                hit.put("excerpt", row.get("content"));
                hit.put("version", row.get("source_version"));
                hit.put("score", row.get("match_score"));
                bestByAsset.put(assetId, hit);
            }
        }
        List<Map<String, Object>> hits = new ArrayList<>(bestByAsset.values());
        return hits.size() > limit ? hits.subList(0, limit) : hits;
    }

    private String sha256(String content) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(hash.length * 2);
            for (byte value : hash) result.append(String.format("%02x", value & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private String value(Object value) { return value == null ? "" : String.valueOf(value); }
}

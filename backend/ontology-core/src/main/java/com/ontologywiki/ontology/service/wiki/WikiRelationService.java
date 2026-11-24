package com.ontologywiki.ontology.service.wiki;

import com.ontologywiki.ontology.repository.RowIds;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ontologywiki.ontology.mapper.WikiRelationMapper;
import com.ontologywiki.ontology.repository.OntologyResourceRepository.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Maintains the persistent, rebuildable relationship directory for Wiki assets. */
@Service
public class WikiRelationService {
    private static final int MAX_GRAPH_RELATIONS = 10000;
    private static final Pattern WIKI_LINK = Pattern.compile("\\[\\[([^\\]]{1,500})\\]\\]");
    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[[^\\]]{1,500}\\]\\(([^)\\s]{1,1000})\\)");

    private final WikiRelationMapper mapper;
    private final ObjectMapper json;

    public WikiRelationService(WikiRelationMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    @Transactional(rollbackFor = Exception.class)
    public void refreshPage(Long assetId) {
        refreshPageInternal(assetId, null, false);
    }

    @Transactional(rollbackFor = Exception.class)
    public void refreshPage(Long assetId, Long previousSpaceId) {
        refreshPageInternal(assetId, previousSpaceId, true);
    }

    public Long pageSpaceId(Long assetId) {
        List<Map<String, Object>> pages = mapper.selectPage(assetId);
        if (pages.isEmpty() || pages.get(0).get("space_id") == null) return null;
        return RowIds.first(pages, "space_id");
    }

    private void refreshPageInternal(Long assetId, Long previousSpaceId, boolean includePreviousSpace) {
        List<Map<String, Object>> pages = mapper.selectPage(assetId);
        LinkedHashSet<Long> affectedSpaces = new LinkedHashSet<>();
        addSpaces(affectedSpaces, mapper.selectAffectedSpaces(assetId));
        if (includePreviousSpace) addSpace(affectedSpaces, previousSpaceId);
        if (!pages.isEmpty()) addSpace(affectedSpaces, pages.get(0).get("space_id"));
        if (!pages.isEmpty()) {
            String pageName = string(pages.get(0).get("asset_name"));
            addSpaces(affectedSpaces, mapper.selectUnresolvedTargetSpaces(pageName));
        }
        replacePageRelations(assetId, pages);
        for (Long spaceId : affectedSpaces) refreshMarkdownProjection(spaceId);
    }

    @Transactional(rollbackFor = Exception.class)
    public int rebuildAll() {
        List<Map<String, Object>> pages = mapper.selectActivePages();
        LinkedHashSet<Long> affectedSpaces = new LinkedHashSet<>();
        addSpaces(affectedSpaces, mapper.selectProjectionSpaces());
        for (Map<String, Object> page : pages) {
            Long assetId = ((Number) page.get("asset_id")).longValue();
            List<Map<String, Object>> oldRelations = mapper.selectAffectedSpaces(assetId);
            addSpaces(affectedSpaces, oldRelations);
            addSpaces(affectedSpaces, mapper.selectUnresolvedTargetSpaces(string(page.get("asset_name"))));
            addSpace(affectedSpaces, page.get("space_id"));
            replacePageRelations(assetId, Collections.singletonList(page));
        }
        for (Long spaceId : affectedSpaces) refreshMarkdownProjection(spaceId);
        return pages.size();
    }

    public Map<String, Object> graph(Long spaceId, int pageNum, int pageSize) {
        int safePage = Math.max(1, pageNum);
        int safeSize = Math.max(1, Math.min(200, pageSize));
        long offset = ((long) safePage - 1) * safeSize;
        List<Map<String, Object>> rows = mapper.selectGraphNodes(spaceId, safeSize, offset);
        Set<Long> visibleIds = new java.util.HashSet<>();
        List<Long> pageAssetIds = new ArrayList<>();
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            long id = ((Number) row.get("asset_id")).longValue();
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", String.valueOf(id));
            node.put("assetId", id);
            node.put("name", row.get("asset_name"));
            node.put("assetType", row.get("asset_type"));
            node.put("spaceId", row.get("space_id"));
            visibleIds.add(id);
            pageAssetIds.add(id);
            nodes.add(node);
        }

        List<Map<String, Object>> edges = new ArrayList<>();
        List<Map<String, Object>> unresolved = new ArrayList<>();
        if (!pageAssetIds.isEmpty()) {
            for (Map<String, Object> relation : mapper.selectGraphRelations(spaceId, pageAssetIds, MAX_GRAPH_RELATIONS)) {
                Object targetId = relation.get("target_asset_id");
                long source = ((Number) relation.get("source_asset_id")).longValue();
                if (!visibleIds.contains(source)) continue;
                String type = String.valueOf(relation.get("relation_type"));
                if (targetId == null) {
                    Map<String, Object> missing = new LinkedHashMap<>();
                    missing.put("source", String.valueOf(source));
                    missing.put("targetName", relation.get("target_name"));
                    missing.put("relationType", type);
                    unresolved.add(missing);
                    continue;
                }
                long target = ((Number) targetId).longValue();
                if (!visibleIds.contains(target)) continue;
                Map<String, Object> edge = new LinkedHashMap<>();
                edge.put("source", String.valueOf(source));
                edge.put("target", String.valueOf(target));
                edge.put("name", relationLabel(type));
                edge.put("relationType", type);
                edges.add(edge);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodes", nodes);
        result.put("edges", edges);
        result.put("unresolved", unresolved);
        result.put("pageNum", safePage);
        result.put("pageSize", safeSize);
        result.put("total", mapper.countGraphNodes(spaceId));
        return result;
    }

    public PageResult relationPage(Long assetId, String direction, int pageNum, int pageSize) {
        String normalizedDirection = direction == null ? "BOTH" : direction.trim().toUpperCase(Locale.ROOT);
        if (!normalizedDirection.equals("INCOMING") && !normalizedDirection.equals("OUTGOING")
                && !normalizedDirection.equals("BOTH")) {
            normalizedDirection = "BOTH";
        }
        int safePage = Math.max(1, pageNum);
        int safeSize = Math.max(1, Math.min(200, pageSize));
        long offset = ((long) safePage - 1) * safeSize;
        List<Map<String, Object>> rows = mapper.selectAssetRelations(assetId, normalizedDirection, offset, safeSize);
        return new PageResult(mapper.countAssetRelations(assetId, normalizedDirection), rows);
    }

    public Map<String, Object> markdownProjection(Long spaceId) {
        String scopeKey = spaceId == null ? "GLOBAL" : "SPACE:" + spaceId;
        List<Map<String, Object>> rows = mapper.selectMarkdownProjection(scopeKey);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("spaceId", spaceId);
        if (rows.isEmpty()) {
            result.put("content", "");
            result.put("version", null);
            result.put("generatedAt", null);
            return result;
        }
        Map<String, Object> row = rows.get(0);
        result.put("spaceId", row.get("space_id"));
        result.put("content", row.get("markdown_content"));
        result.put("version", row.get("index_version"));
        result.put("generatedAt", row.get("generated_at"));
        return result;
    }

    private String relationLabel(String type) {
        switch (type) {
            case "SOURCE": return "来源资料";
            case "ANALYSIS_CITATION": return "分析依据";
            case "MARKDOWN_LINK": return "Markdown 引用";
            default: return "页面引用";
        }
    }

    private void replacePageRelations(Long assetId, List<Map<String, Object>> pages) {
        mapper.deletePageRelations(assetId);
        if (pages.isEmpty()) return;

        Map<String, Object> page = pages.get(0);
        mapper.resolveUnresolvedTarget(string(page.get("asset_name")), assetId);
        String content = string(page.get("content"));
        String tags = string(page.get("tags"));
        List<Relation> relations = extractPageLinks(content);
        addTaggedSource(relations, tags);
        addAnalysisCitations(relations, page.get("source_json"));

        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (Relation relation : relations) {
            if (relation.targetId == null && relation.targetName != null && !relation.targetName.isEmpty()) {
                names.add(relation.targetName);
            }
        }
        Map<String, Long> targets = resolveTargets(names);
        Long spaceId = page.get("space_id") == null ? null : ((Number) page.get("space_id")).longValue();
        String version = digest(string(page.get("asset_name")) + "\n" + tags + "\n" + content);
        for (Relation relation : relations) {
            Long targetId = relation.targetId;
            if (targetId == null && relation.targetName != null) {
                targetId = targets.get(relation.targetName.toLowerCase(Locale.ROOT));
            }
            Long sourceId = relation.sourceId == null ? assetId : relation.sourceId;
            if (relation.type.equals("SOURCE") || relation.type.equals("ANALYSIS_CITATION")) targetId = assetId;
            if (targetId != null && targetId.equals(sourceId)) continue;
            mapper.insertRelation(spaceId, sourceId, targetId, relation.targetName,
                    relation.type, relation.position, version);
        }
    }

    private void refreshMarkdownProjection(Long spaceId) {
        List<Map<String, Object>> pages = mapper.selectProjectionPages(spaceId);
        List<Map<String, Object>> relations = mapper.selectProjectionRelations(spaceId);
        Map<Long, List<Map<String, Object>>> outgoing = new HashMap<>();
        Map<Long, List<Map<String, Object>>> incoming = new HashMap<>();
        for (Map<String, Object> relation : relations) {
            long sourceId = ((Number) relation.get("source_asset_id")).longValue();
            Object targetValue = relation.get("target_asset_id");
            Long targetId = targetValue == null ? null : ((Number) targetValue).longValue();
            String type = string(relation.get("relation_type"));
            if (isPageLink(type)) {
                addRelation(outgoing, sourceId, relation);
                if (targetId != null) addRelation(incoming, targetId, relation);
            } else if (targetId != null) {
                addRelation(incoming, targetId, relation);
            }
        }

        StringBuilder markdown = new StringBuilder("# Wiki 关系导航\n\n");
        markdown.append(spaceId == null ? "> 默认知识空间\n\n" : "> 知识空间 #" + spaceId + "\n\n");
        for (Map<String, Object> page : pages) {
            long pageId = ((Number) page.get("asset_id")).longValue();
            String title = string(page.get("asset_name"));
            markdown.append("## ").append(markdownLink(title, pageId)).append("\n\n");
            String description = string(page.get("description"));
            if (!description.isEmpty()) markdown.append(description).append("\n\n");
            appendRelationSection(markdown, "页面链接", outgoing.get(pageId), false, "WIKI_LINK", "MARKDOWN_LINK");
            appendRelationSection(markdown, "反向链接", incoming.get(pageId), true, "WIKI_LINK", "MARKDOWN_LINK");
            appendRelationSection(markdown, "来源资料", incoming.get(pageId), true, "SOURCE");
            appendRelationSection(markdown, "分析依据", incoming.get(pageId), true, "ANALYSIS_CITATION");
        }
        String content = markdown.toString();
        String scopeKey = spaceId == null ? "GLOBAL" : "SPACE:" + spaceId;
        mapper.saveMarkdownProjection(scopeKey, spaceId, digest(content), content);
    }

    private boolean isPageLink(String type) {
        return "WIKI_LINK".equals(type) || "MARKDOWN_LINK".equals(type);
    }

    private void addRelation(Map<Long, List<Map<String, Object>>> grouped, long pageId,
                             Map<String, Object> relation) {
        grouped.computeIfAbsent(pageId, key -> new ArrayList<>()).add(relation);
    }

    private void appendRelationSection(StringBuilder markdown, String title,
                                       List<Map<String, Object>> relations, boolean incoming, String... types) {
        List<Map<String, Object>> matching = new ArrayList<>();
        if (relations != null) {
            for (Map<String, Object> relation : relations) {
                String relationType = string(relation.get("relation_type"));
                for (String type : types) {
                    if (type.equals(relationType)) {
                        matching.add(relation);
                        break;
                    }
                }
            }
        }
        if (matching.isEmpty()) return;
        markdown.append("### ").append(title).append("\n\n");
        for (Map<String, Object> relation : matching) {
            String type = string(relation.get("relation_type"));
            boolean pageLink = isPageLink(type);
            boolean useSource = incoming || !pageLink;
            Object relatedId = useSource ? relation.get("source_asset_id") : relation.get("target_asset_id");
            String relatedName = useSource ? string(relation.get("source_name")) : string(relation.get("target_name"));
            markdown.append("- ");
            if (relatedId instanceof Number) {
                markdown.append(markdownLink(relatedName, ((Number) relatedId).longValue()));
            } else {
                markdown.append("未解析：").append(relatedName);
            }
            if ("MARKDOWN_LINK".equals(type)) markdown.append("（Markdown 链接）");
            markdown.append('\n');
        }
        markdown.append('\n');
    }

    private String markdownLink(String title, long assetId) {
        String safeTitle = title.replace("[", "\\[").replace("]", "\\]");
        return "[" + safeTitle + "](wiki://asset/" + assetId + ")";
    }

    private void addSpaces(LinkedHashSet<Long> spaces, List<Map<String, Object>> rows) {
        for (Map<String, Object> row : rows) addSpace(spaces, row.get("space_id"));
    }

    private void addSpace(LinkedHashSet<Long> spaces, Object rawSpaceId) {
        spaces.add(rawSpaceId == null ? null : ((Number) rawSpaceId).longValue());
    }

    private List<Relation> extractPageLinks(String content) {
        List<Relation> relations = new ArrayList<>();
        Matcher wikiLinks = WIKI_LINK.matcher(content);
        while (wikiLinks.find()) {
            String name = wikiLinks.group(1).split("\\|", 2)[0].trim();
            if (!name.isEmpty()) relations.add(Relation.named(name, "WIKI_LINK", wikiLinks.start()));
        }
        Matcher markdownLinks = MARKDOWN_LINK.matcher(content);
        while (markdownLinks.find()) {
            String name = normalizeTarget(markdownLinks.group(1));
            if (!name.isEmpty()) relations.add(Relation.named(name, "MARKDOWN_LINK", markdownLinks.start()));
        }
        return relations;
    }

    private void addTaggedSource(List<Relation> relations, String tags) {
        if (!tags.startsWith("SOURCE:")) return;
        try {
            Long sourceId = Long.valueOf(tags.substring("SOURCE:".length()).trim());
            relations.add(Relation.incoming(sourceId, "SOURCE", 0));
        } catch (NumberFormatException ignored) {
            // Keep malformed legacy tags out of the relationship catalog.
        }
    }

    private void addAnalysisCitations(List<Relation> relations, Object rawJson) {
        if (rawJson == null) return;
        try {
            JsonNode citations = json.readTree(String.valueOf(rawJson)).path("citedSources");
            if (!citations.isArray()) return;
            for (JsonNode citation : citations) {
                if (citation.path("assetId").canConvertToLong()) {
                    relations.add(Relation.incoming(citation.path("assetId").asLong(), "ANALYSIS_CITATION", relations.size()));
                }
            }
        } catch (Exception ignored) {
            // The Wiki page remains readable when an old memory payload is malformed.
        }
    }

    private Map<String, Long> resolveTargets(LinkedHashSet<String> names) {
        if (names.isEmpty()) return Collections.emptyMap();
        Map<String, Long> resolved = new HashMap<>();
        for (Map<String, Object> target : mapper.resolveTargets(new ArrayList<>(names))) {
            resolved.put(String.valueOf(target.get("asset_name")).toLowerCase(Locale.ROOT),
                    ((Number) target.get("asset_id")).longValue());
        }
        return resolved;
    }

    private String normalizeTarget(String raw) {
        String target = raw.trim();
        if (target.startsWith("http://") || target.startsWith("https://") || target.startsWith("mailto:")) return "";
        int query = target.indexOf('?');
        if (query >= 0) target = target.substring(0, query);
        int fragment = target.indexOf('#');
        if (fragment >= 0) target = target.substring(0, fragment);
        int slash = target.lastIndexOf('/');
        if (slash >= 0) target = target.substring(slash + 1);
        if (target.toLowerCase(Locale.ROOT).endsWith(".md")) target = target.substring(0, target.length() - 3);
        try {
            return URLDecoder.decode(target, StandardCharsets.UTF_8.name()).trim();
        } catch (Exception ignored) {
            return target.trim();
        }
    }

    private String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(hash.length * 2);
            for (byte b : hash) result.append(String.format("%02x", b));
            return result.toString();
        } catch (Exception e) {
            throw new IllegalStateException("无法计算 Wiki 关系索引版本", e);
        }
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static final class Relation {
        private final Long sourceId;
        private final Long targetId;
        private final String targetName;
        private final String type;
        private final Integer position;

        private Relation(Long sourceId, Long targetId, String targetName, String type, Integer position) {
            this.sourceId = sourceId;
            this.targetId = targetId;
            this.targetName = targetName;
            this.type = type;
            this.position = position;
        }

        private static Relation named(String name, String type, Integer position) {
            return new Relation(null, null, name, type, position);
        }

        private static Relation incoming(Long sourceId, String type, Integer position) {
            return new Relation(sourceId, null, null, type, position);
        }
    }
}

package com.ontologywiki.ontology.service;

import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 可运行的本体生成内核。确定性规则先产出候选，LLM 只负责后续语义补充。
 * shortcut: 当前不加载本地 Embedding 模型，概念合并先使用标准化名称和术语表；需要语义相似度时接入独立服务。
 */
@Service
public class OntologyGenerationEngine {
    private static final Pattern WORD = Pattern.compile("[^a-zA-Z0-9\\u4e00-\\u9fa5]+");
    private static final Pattern SQL_KIND = Pattern.compile("(?i)\\b(select|insert|update|delete)\\b");
    private static final Pattern SQL_TABLE = Pattern.compile("(?i)\\b(?:from|join|into|update)\\s+[`\\\"]?([a-zA-Z0-9_$.-]+)");
    private static final Pattern SQL_VALUE = Pattern.compile("'(?:''|[^'])*'|\\b\\d+(?:\\.\\d+)?\\b");
    private static final Pattern RULE_CLAUSE = Pattern.compile("(?i)([a-zA-Z0-9_\\u4e00-\\u9fa5.]+)\\s*(=|!=|>=|<=|>|<|等于|不等于|大于等于|小于等于|大于|小于)\\s*([^且并且或或andor,;，；]+)");
    private static final Pattern DERIVED_FORMULA = Pattern.compile("(?i)(?:计算公式|派生公式|公式|formula)\\s*[:：=]\\s*(.+)");
    private static final Pattern ALIAS_LINE = Pattern.compile("(?i)^(?:[-*]\\s*)?(?:别名|同义词|简称|英文名|英文名称|aliases?)\\s*[:：=]\\s*(.{1,300})$");
    private static final Pattern GOVERNANCE_MARKER = Pattern.compile("必须|不得|禁止|仅允许|应当");

    public Map<String,Object> mergeSchemaSnapshot(Map<String,Object> previous, Map<String,Object> scanned, Set<String> changedTables) {
        Map<String,Map<String,Object>> mergedTables = new LinkedHashMap<>();
        Set<String> foundNames = new HashSet<>();
        for (Map<String,Object> table : list(previous == null ? null : previous.get("tables"))) {
            String name = text(table.get("name")).toLowerCase(Locale.ROOT); foundNames.add(name);
            if (!changedTables.contains(name)) mergedTables.put(schemaTableKey(table), table);
        }
        for (Map<String,Object> table : list(scanned == null ? null : scanned.get("tables"))) {
            foundNames.add(text(table.get("name")).toLowerCase(Locale.ROOT)); mergedTables.put(schemaTableKey(table), table);
        }
        for (String name : changedTables) if (!foundNames.contains(name)) throw new IllegalArgumentException("变化表未出现在历史快照或当前数据源中：" + name);
        Map<String,Object> result = new LinkedHashMap<>(previous == null ? Collections.<String,Object>emptyMap() : previous);
        for (String key : Arrays.asList("databaseProduct", "databaseVersion")) if (scanned != null && scanned.containsKey(key)) result.put(key, scanned.get(key));
        result.put("tables", new ArrayList<>(mergedTables.values())); result.put("tableCount", mergedTables.size());
        return result;
    }

    public Map<String,Object> schemaMetadataSnapshot(Map<String,Object> schema) {
        Map<String,Object> snapshot = new LinkedHashMap<>();
        for (String key : Arrays.asList("databaseProduct", "databaseVersion")) if (schema.containsKey(key)) snapshot.put(key, schema.get(key));
        List<Map<String,Object>> tables = new ArrayList<>();
        for (Map<String,Object> table : list(schema.get("tables"))) {
            Map<String,Object> metadata = new LinkedHashMap<>();
            for (String key : Arrays.asList("schema", "name", "type", "comment", "primaryKeys", "foreignKeys")) if (table.containsKey(key)) metadata.put(key, table.get(key));
            List<Map<String,Object>> columns = new ArrayList<>();
            for (Map<String,Object> column : list(table.get("columns"))) {
                Map<String,Object> columnMetadata = new LinkedHashMap<>();
                for (String key : Arrays.asList("name", "type", "comment", "nullable")) if (column.containsKey(key)) columnMetadata.put(key, column.get(key));
                columns.add(columnMetadata);
            }
            metadata.put("columns", columns); tables.add(metadata);
        }
        snapshot.put("tables", tables); snapshot.put("tableCount", tables.size()); return snapshot;
    }

    private String schemaTableKey(Map<String,Object> table) {
        return (text(table.get("schema")) + "." + text(table.get("name"))).toLowerCase(Locale.ROOT);
    }

    public Map<String, Object> generate(Map<String, Object> schema,
                                        List<Map<String, Object>> documents,
                                        List<Map<String, Object>> businessRules,
                                        List<String> queryPatterns,
                                        Set<Integer> requestedStages) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> tables = list(schema == null ? null : schema.get("tables"));
        List<Map<String, Object>> evidence = new ArrayList<>();
        List<Map<String, Object>> warnings = new ArrayList<>();
        List<Map<String, Object>> stageReports = new ArrayList<>();

        Map<String, Object> stage1 = stage1(tables, queryPatterns, evidence, warnings);
        stageReports.add(report(1, "数据准备", stage1));
        List<Map<String,Object>> processableTables = processableTables(tables, stage1, warnings);

        Map<String, Object> stage2 = stage2(processableTables, evidence);
        stageReports.add(report(2, "概念抽取", stage2));

        Map<String, Object> stage3 = stage3(processableTables, stage2, documents, evidence, warnings);
        stageReports.add(report(3, "概念层次", stage3));

        Map<String, Object> stage4 = stage4(processableTables, stage2, evidence, warnings);
        stageReports.add(report(4, "关系与属性", stage4));

        Map<String, Object> stage5 = stage5(stage4, stage3, stage4, businessRules, evidence, warnings);
        stageReports.add(report(5, "公理与逻辑", stage5));

        Map<String, Object> stage6 = stage6(documents, list(stage2.get("objects")), evidence);
        stageReports.add(report(6, "语义丰富化", stage6));

        Map<String, Object> stage7 = stage7(processableTables, queryPatterns, stage4, evidence);
        warnings.addAll(list(stage7.get("warnings")));
        stageReports.add(report(7, "流程与权限", stage7));

        result.put("version", "1.0");
        result.put("stages", requestedStages == null || requestedStages.isEmpty() ? Arrays.asList(1,2,3,4,5,6,7) : new ArrayList<>(requestedStages));
        result.put("objects", stage4.get("objects"));
        result.put("properties", stage2.get("properties"));
        result.put("identitySpecs", stage2.get("identitySpecs"));
        result.put("dataProfiles", stage1.get("dataProfiles"));
        result.put("taxonomy", stage3.get("taxonomy"));
        result.put("relations", stage4.get("relations"));
        result.put("mappings", stage4.get("mappings"));
        result.put("derivedProperties", stage4.get("derivedProperties"));
        result.put("validationRules", stage4.get("validationRules"));
        result.put("axioms", stage5.get("axioms"));
        result.put("logics", stage5.get("logics"));
        result.put("consistencyReport", stage5.get("consistencyReport"));
        result.put("terms", stage6.get("terms"));
        result.put("semanticMappings", stage6.get("semanticMappings"));
        result.put("governanceRules", stage6.get("governanceRules"));
        result.put("actions", stage7.get("actions"));
        result.put("serviceFlows", stage7.get("serviceFlows"));
        result.put("permissionCandidates", stage7.get("permissionCandidates"));
        result.put("queryPatterns", stage7.get("queryPatterns"));
        result.put("targetConfig", targetConfig(result));
        result.put("evidence", evidence);
        result.put("warnings", warnings);
        result.put("stageReports", stageReports);
        return result;
    }

    private Map<String, Object> stage1(List<Map<String, Object>> tables, List<String> queries,
                                       List<Map<String, Object>> evidence, List<Map<String, Object>> warnings) {
        List<Map<String, Object>> scores = new ArrayList<>(), dataProfiles = new ArrayList<>();
        for (Map<String, Object> table : tables) {
            String name = text(table.get("name"));
            List<Map<String, Object>> columns = list(table.get("columns"));
            List<Map<String, Object>> samples = list(table.get("samples"));
            int score = 0;
            if (name != null) score += 2;
            if (text(table.get("comment")) != null) score += 2;
            if (!columns.isEmpty()) score += 2;
            if (!list(table.get("primaryKeys")).isEmpty()) score += 2;
            if (!list(table.get("foreignKeys")).isEmpty()) score += 1;
            if (score < 4) warnings.add(warning("LOW_QUALITY_TABLE", name, "表注释、字段或主键证据不足"));
            Map<String, Object> item = new LinkedHashMap<>(); item.put("table", name); item.put("key", tableKey(table)); item.put("score", score); item.put("processable", score >= 4); item.put("sampleCount", samples.size()); scores.add(item);
            if (!samples.isEmpty()) {
                Map<String, Object> profile = new LinkedHashMap<>(); profile.put("table", name); profile.put("sampleCount", samples.size());
                List<Map<String, Object>> columnProfiles = new ArrayList<>();
                for (Map<String, Object> column : columns) {
                    String columnName = text(column.get("name")); if (columnName == null) continue;
                    Set<String> distinct = new HashSet<>(); int nonNull = 0;
                    for (Map<String, Object> sample : samples) {
                        Object value = findIgnoreCase(sample, columnName);
                        if (value != null && !String.valueOf(value).trim().isEmpty()) { nonNull++; distinct.add(String.valueOf(value)); }
                    }
                    Map<String, Object> columnProfile = new LinkedHashMap<>();
                    columnProfile.put("column", columnName); columnProfile.put("nonNullCount", nonNull); columnProfile.put("distinctCount", distinct.size());
                    columnProfile.put("cardinality", distinct.size() <= 1 ? "CONSTANT" : distinct.size() <= 8 ? "LOW" : "VARIED");
                    columnProfiles.add(columnProfile);
                }
                profile.put("columns", columnProfiles); dataProfiles.add(profile);
            }
            evidence.add(ev("schema.table", name, "数据库表结构和质量评分"));
        }
        Map<String, Object> out = new LinkedHashMap<>(); out.put("qualityScores", scores); out.put("dataProfiles", dataProfiles); out.put("tableCount", tables.size()); out.put("processableTableCount", scores.stream().filter(score -> Boolean.TRUE.equals(score.get("processable"))).count()); out.put("queryCount", queries == null ? 0 : queries.size()); return out;
    }

    private List<Map<String,Object>> processableTables(List<Map<String,Object>> tables, Map<String,Object> stage1, List<Map<String,Object>> warnings) {
        Set<String> accepted = new HashSet<>();
        for (Map<String,Object> score : list(stage1.get("qualityScores"))) if (Boolean.TRUE.equals(score.get("processable"))) accepted.add(text(score.get("key")));
        List<Map<String,Object>> result = new ArrayList<>();
        for (Map<String,Object> table : tables) if (accepted.contains(tableKey(table))) result.add(table);
        if (result.isEmpty() && !tables.isEmpty()) warnings.add(warning("NO_PROCESSABLE_TABLE", "schema", "所有表的结构证据均低于最低质量阈值，未生成本体候选"));
        return result;
    }

    private Map<String, Object> stage2(List<Map<String, Object>> tables, List<Map<String, Object>> evidence) {
        List<Map<String, Object>> objects = new ArrayList<>(), properties = new ArrayList<>(), identities = new ArrayList<>();
        for (Map<String, Object> table : tables) {
            String tableName = text(table.get("name")); if (tableName == null) continue;
            String tableKey = tableKey(table);
            String comment = text(table.get("comment"));
            String objectId = id("object", tableKey);
            Map<String, Object> object = item(objectId, comment == null || comment.length() > 80 ? tableName : comment, "rdb", comment == null ? 0.55 : 0.72);
            object.put("table", tableName); object.put("schema", table.get("schema")); object.put("technicalName", tableName);
            object.put("description", comment); object.put("layer", "data"); objects.add(object);
            for (Map<String, Object> column : list(table.get("columns"))) {
                String columnName = text(column.get("name")); if (columnName == null) continue;
                String columnComment = text(column.get("comment"));
                Map<String, Object> property = item(id("property", tableKey + "." + columnName), columnComment == null || columnComment.length() > 120 ? columnName : columnComment, "rdb", columnComment == null ? 0.50 : 0.70);
                property.put("objectId", objectId); property.put("column", columnName); property.put("technicalName", columnName);
                property.put("description", columnComment); property.put("dataType", column.get("type"));
                property.put("nullable", column.get("nullable")); properties.add(property);
            }
            List<String> pks = strings(table.get("primaryKeys")); if (!pks.isEmpty()) { Map<String, Object> identity = new LinkedHashMap<>(); identity.put("objectId", objectId); identity.put("columns", pks); identity.put("source", "primaryKey"); identities.add(identity); }
        }
        Map<String, Object> out = new LinkedHashMap<>(); out.put("objects", objects); out.put("properties", properties); out.put("identitySpecs", identities); return out;
    }

    private Map<String, Object> stage3(List<Map<String, Object>> tables, Map<String, Object> stage2,
                                       List<Map<String, Object>> documents, List<Map<String, Object>> evidence,
                                       List<Map<String, Object>> warnings) {
        Map<String, Object> taxonomy = new LinkedHashMap<>(); List<Map<String, Object>> nodes = new ArrayList<>();
        for (Map<String, Object> object : list(stage2.get("objects"))) { Map<String, Object> node = new LinkedHashMap<>(); node.put("id", object.get("id")); node.put("name", object.get("name")); node.put("parentId", null); nodes.add(node); }
        taxonomy.put("nodes", nodes); taxonomy.put("rootIds", ids(nodes)); taxonomy.put("edges", new ArrayList<>());
        List<Map<String, Object>> edges = (List<Map<String, Object>>) taxonomy.get("edges");
        Map<String, String> objectByName = new HashMap<>();
        Map<String,Map<String,Object>> nodesById = new HashMap<>();
        for (Map<String,Object> node : nodes) nodesById.put(text(node.get("id")), node);
        for (Map<String, Object> object : list(stage2.get("objects"))) objectByName.put(text(object.get("name")).toLowerCase(Locale.ROOT), text(object.get("id")));
        if (documents != null) for (Map<String, Object> doc : documents) {
            String content = text(doc.get("content")); if (content == null) continue;
            String normalizedContent = content.toLowerCase(Locale.ROOT);
            for (Map.Entry<String, String> parent : objectByName.entrySet()) for (Map.Entry<String, String> child : objectByName.entrySet()) {
                if (parent.getKey().equals(child.getKey()) || !normalizedContent.matches("(?s).*" + Pattern.quote(child.getKey()) + ".{0,30}(属于|是一种|类型为|包括).{0,30}" + Pattern.quote(parent.getKey()) + ".*")) continue;
                String childId = child.getValue(), parentId = parent.getValue();
                if (edges.stream().anyMatch(edge -> childId.equals(edge.get("sourceId")) && parentId.equals(edge.get("targetId")))) continue;
                if (wouldCreateTaxonomyCycle(edges, childId, parentId)) { warnings.add(warning("TAXONOMY_CYCLE", childId, "文档层级候选会形成环，已排除该关系：" + child.getKey() + " → " + parent.getKey())); continue; }
                Map<String, Object> edge = new LinkedHashMap<>(); edge.put("sourceId", childId); edge.put("targetId", parentId); edge.put("type", "IS_A"); edge.put("source", "document"); edge.put("status", "NEEDS_REVIEW"); edges.add(edge);
                if (nodesById.get(childId).get("parentId") == null) nodesById.get(childId).put("parentId", parentId);
            }
        }
        if (!edges.isEmpty()) taxonomy.put("rootIds", ids(nodes).stream().filter(id -> edges.stream().noneMatch(edge -> id.equals(edge.get("sourceId")))).collect(java.util.stream.Collectors.toList()));
        if (documents == null || documents.isEmpty()) warnings.add(warning("NO_BUSINESS_DOCUMENT", "taxonomy", "没有提供业务文档，层级关系只保留数据库证据"));
        Map<String, Object> out = new LinkedHashMap<>(); out.put("taxonomy", taxonomy); return out;
    }

    private boolean wouldCreateTaxonomyCycle(List<Map<String,Object>> edges, String childId, String parentId) {
        Deque<String> pending = new ArrayDeque<>(); Set<String> visited = new HashSet<>(); pending.add(parentId);
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            if (!visited.add(current)) continue;
            if (childId.equals(current)) return true;
            for (Map<String,Object> edge : edges) if (current.equals(edge.get("sourceId"))) pending.addLast(text(edge.get("targetId")));
        }
        return false;
    }

    private Map<String, Object> stage4(List<Map<String, Object>> tables, Map<String, Object> stage2,
                                       List<Map<String, Object>> evidence, List<Map<String, Object>> warnings) {
        List<Map<String, Object>> relations = new ArrayList<>(), mappings = new ArrayList<>(), derived = new ArrayList<>(), validations = new ArrayList<>();
        List<Map<String, Object>> objects = list(stage2.get("objects"));
        Map<String, String> objectIds = new HashMap<>();
        Map<String, Map<String, Object>> objectsById = new HashMap<>();
        Map<String, List<String>> idsByTable = new HashMap<>();
        for (Map<String, Object> o : objects) {
            String tableKey = tableKey(o); objectIds.put(tableKey, text(o.get("id"))); objectsById.put(text(o.get("id")), o);
            idsByTable.computeIfAbsent(text(o.get("table")).toLowerCase(Locale.ROOT), key -> new ArrayList<>()).add(text(o.get("id")));
        }
        for (Map<String, Object> table : tables) {
            String tableName = text(table.get("name")); String key = tableKey(table); String sourceId = objectIds.get(key); if (sourceId == null) continue;
            for (Map<String, Object> column : list(table.get("columns"))) {
                String columnName = text(column.get("name")); if (columnName == null) continue;
                Map<String, Object> mapping = new LinkedHashMap<>(); mapping.put("objectId", sourceId); mapping.put("column", columnName); mapping.put("table", tableName); mapping.put("schema", table.get("schema")); mapping.put("propertyId", id("property", key + "." + columnName)); mappings.add(mapping);
                if (Boolean.FALSE.equals(column.get("nullable"))) validations.add(rule("NOT_NULL", sourceId, columnName));
                String comment = text(column.get("comment")); Matcher formula = DERIVED_FORMULA.matcher(comment == null ? "" : comment);
                if (formula.find() && text(formula.group(1)) != null) {
                    Map<String, Object> property = item(id("derived-property", key + "." + columnName), humanize(columnName), "explicitColumnFormula", 0.75);
                    property.put("objectId", sourceId); property.put("column", columnName); property.put("formula", formula.group(1).trim()); property.put("reviewRequired", true); derived.add(property);
                    evidence.add(ev("columnFormula", tableName + "." + columnName, "数据库字段注释中显式记录计算公式"));
                }
            }
            List<Map<String, Object>> references = new ArrayList<>();
            Set<String> declaredColumns = new HashSet<>();
            for (Map<String, Object> fk : list(table.get("foreignKeys"))) {
                String target = text(fk.get("referencedTable")); String column = text(fk.get("column")); if (target == null || column == null) continue;
                declaredColumns.add(column.toLowerCase(Locale.ROOT));
                String targetId = objectIds.get(key(text(table.get("schema")), target));
                if (targetId == null && idsByTable.getOrDefault(target.toLowerCase(Locale.ROOT), Collections.<String>emptyList()).size() == 1) targetId = idsByTable.get(target.toLowerCase(Locale.ROOT)).get(0);
                if (targetId == null) continue;
                String targetColumn = text(fk.get("referencedColumn"));
                Map<String, Object> rel = item(id("relation", key + "." + column), humanize(tableName) + "关联" + humanize(target), "foreignKey", 0.78); rel.put("sourceObjectId", sourceId); rel.put("targetObjectId", targetId); rel.put("sourceColumn", column); rel.put("targetColumn", targetColumn); rel.put("cardinality", "MANY_TO_ONE"); relations.add(rel); evidence.add(ev("foreignKey", tableName, "数据库声明的主外键关系"));
                references.add(reference(column, target, targetColumn, targetId, false, 0.99, "数据库声明了外键约束"));
            }
            List<Map<String, Object>> inferred = inferNamedReferences(table, tables, declaredColumns, warnings);
            for (Map<String, Object> ref : inferred) {
                String target = text(ref.get("referencedTable"));
                String targetId = objectIdForTable(objectIds, ref.get("targetTable"));
                if (targetId == null) continue;
                String column = text(ref.get("column")); String targetColumn = text(ref.get("referencedColumn"));
                String detail = String.valueOf(ref.get("evidence"));
                Map<String, Object> rel = item(id("relation", key + ".inferred." + column + "." + target), objectName(objectsById.get(targetId), humanize(target)) + "关联", "columnNameInference", ((Number) ref.get("confidence")).doubleValue());
                rel.put("sourceObjectId", sourceId); rel.put("targetObjectId", targetId); rel.put("sourceColumn", column); rel.put("targetColumn", targetColumn); rel.put("cardinality", "MANY_TO_ONE"); rel.put("inferenceMethod", "TABLE_COLUMN_NAME_AND_PRIMARY_KEY"); rel.put("evidence", detail); rel.put("reviewRequired", true); relations.add(rel);
                references.add(reference(column, target, targetColumn, targetId, true, ((Number) ref.get("confidence")).doubleValue(), detail));
                evidence.add(ev("columnNameInference", tableName + "." + column, detail));
            }
            List<Map<String, Object>> foreignKeys = list(table.get("foreignKeys"));
            List<String> primaryKeys = strings(table.get("primaryKeys"));
            if (foreignKeys.size() >= 2 && !primaryKeys.isEmpty() && primaryKeys.stream().allMatch(pk -> foreignKeys.stream().anyMatch(fk -> pk.equalsIgnoreCase(text(fk.get("column"))))) && !objectIds.containsKey(key + "#m2m")) {
                String associationId = id("object", key + "_association");
                Map<String, Object> association = item(associationId, humanize(tableName) + "关联", "m2mReification", 0.74);
                association.put("table", tableName); association.put("schema", table.get("schema")); association.put("technicalName", tableName); association.put("kind", "ASSOCIATION"); association.put("status", "NEEDS_REVIEW"); objects.add(association);
                for (Map<String, Object> fk : foreignKeys) {
                    String target = text(fk.get("referencedTable")); String targetId = objectIds.get(key(text(table.get("schema")), target));
                    if (targetId == null && idsByTable.getOrDefault(target.toLowerCase(Locale.ROOT), Collections.<String>emptyList()).size() == 1) targetId = idsByTable.get(target.toLowerCase(Locale.ROOT)).get(0);
                    if (targetId == null) continue;
                    Map<String, Object> rel = item(id("relation", tableName + ".association." + text(fk.get("column"))), humanize(tableName) + "关联" + humanize(target), "m2mReification", 0.70);
                    rel.put("sourceObjectId", associationId); rel.put("targetObjectId", targetId); rel.put("sourceColumn", fk.get("column")); rel.put("targetColumn", fk.get("referencedColumn")); rel.put("cardinality", "MANY_TO_ONE"); relations.add(rel);
                }
                evidence.add(ev("m2mReification", tableName, "复合主键外键中间表已再化为关联对象"));
            }
            if (completeJoinTable(primaryKeys, references)) {
                List<Map<String, Object>> ordered = new ArrayList<>();
                for (String pk : primaryKeys) for (Map<String, Object> ref : references) if (pk.equalsIgnoreCase(text(ref.get("column")))) ordered.add(ref);
                Map<String, Object> first = ordered.get(0), second = ordered.get(1);
                String firstTarget = text(first.get("targetObjectId")), secondTarget = text(second.get("targetObjectId"));
                if (!firstTarget.equals(secondTarget)) {
                    boolean inferredJoin = Boolean.TRUE.equals(first.get("inferred")) || Boolean.TRUE.equals(second.get("inferred"));
                    String detail = "关联表 " + tableName + " 的联合主键字段 " + text(first.get("column")) + "、" + text(second.get("column")) + " 分别匹配对象主键 " + text(first.get("referencedTable")) + "." + text(first.get("referencedColumn")) + " 和 " + text(second.get("referencedTable")) + "." + text(second.get("referencedColumn")) + "，字段类型兼容" + (inferredJoin ? "；部分关系由表名和字段名推断" : "；关系由数据库外键确认");
                    Map<String, Object> rel = item(id("relation", key + ".many_to_many." + text(first.get("column")) + "." + text(second.get("column"))), objectName(objectsById.get(firstTarget), text(first.get("referencedTable"))) + "关联" + objectName(objectsById.get(secondTarget), text(second.get("referencedTable"))), "joinTableInference", inferredJoin ? 0.90 : 0.99);
                    rel.put("sourceObjectId", firstTarget); rel.put("targetObjectId", secondTarget); rel.put("sourceColumn", first.get("column")); rel.put("targetColumn", second.get("column")); rel.put("viaTable", tableName); rel.put("viaObjectId", sourceId); rel.put("viaSourceColumn", first.get("column")); rel.put("viaTargetColumn", second.get("column")); rel.put("cardinality", "MANY_TO_MANY"); rel.put("inferenceMethod", "COMPOSITE_PRIMARY_KEY_JOIN_TABLE"); rel.put("evidence", detail); rel.put("reviewRequired", inferredJoin); relations.add(rel);
                    evidence.add(ev("joinTableInference", tableName, detail));
                    if (inferredJoin) { Map<String, Object> association = objectsById.get(sourceId); association.put("kind", "ASSOCIATION"); association.put("associationEvidence", detail); association.put("status", "NEEDS_REVIEW"); }
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>(); out.put("objects", objects); out.put("relations", relations); out.put("mappings", mappings); out.put("derivedProperties", derived); out.put("validationRules", validations); return out;
    }

    private List<Map<String, Object>> inferNamedReferences(Map<String, Object> child, List<Map<String, Object>> tables,
                                                           Set<String> declaredColumns, List<Map<String, Object>> warnings) {
        List<Map<String, Object>> result = new ArrayList<>(); String childName = text(child.get("name"));
        for (Map<String, Object> column : list(child.get("columns"))) {
            String columnName = text(column.get("name")); if (columnName == null || declaredColumns.contains(columnName.toLowerCase(Locale.ROOT))) continue;
            String stem = referenceStem(columnName); if (stem == null) continue;
            List<String> childKeys = strings(child.get("primaryKeys"));
            if (childKeys.size() == 1 && childKeys.get(0).equalsIgnoreCase(columnName) && tableMatchesStem(childName, stem)) continue;
            List<Map<String, Object>> matches = new ArrayList<>();
            for (Map<String, Object> parent : tables) {
                if (tableKey(child).equals(tableKey(parent))) continue;
                String childSchema = text(child.get("schema")), parentSchema = text(parent.get("schema"));
                if (childSchema != null && parentSchema != null && !childSchema.equalsIgnoreCase(parentSchema)) continue;
                String parentName = text(parent.get("name")); if (parentName == null || !tableMatchesStem(parentName, stem)) continue;
                List<String> primaryKeys = strings(parent.get("primaryKeys"));
                if (primaryKeys.size() != 1) continue;
                String targetColumn = primaryKeys.get(0);
                if (!targetColumn.equalsIgnoreCase(columnName) && !"id".equalsIgnoreCase(targetColumn)) continue;
                String childType = columnType(child, columnName), parentType = columnType(parent, targetColumn);
                if (childType == null || parentType == null || !compatibleTypes(childType, parentType)) continue;
                Map<String, Object> candidate = new LinkedHashMap<>(); candidate.put("column", columnName); candidate.put("referencedTable", parentName); candidate.put("referencedColumn", targetColumn); candidate.put("targetTable", parent); candidate.put("confidence", targetColumn.equalsIgnoreCase(columnName) ? 0.88 : 0.82); candidate.put("evidence", "字段名 " + columnName + " 匹配目标表 " + parentName + "，目标列 " + targetColumn + " 是单列主键且数据类型兼容"); matches.add(candidate);
            }
            if (matches.size() == 1) result.add(matches.get(0));
            else if (matches.size() > 1) warnings.add(warning("AMBIGUOUS_RELATION_INFERENCE", childName + "." + columnName, "字段名可匹配多个目标表，未自动生成关系：" + matches.stream().map(x -> String.valueOf(x.get("referencedTable"))).collect(java.util.stream.Collectors.joining(", "))));
        }
        if (!result.isEmpty()) warnings.add(warning("INFERRED_RELATION_REVIEW", childName, "部分关系由表名、字段名、主键和字段类型推断，需人工审核"));
        return result;
    }

    private Map<String, Object> reference(String column, String table, String targetColumn, String targetId,
                                          boolean inferred, double confidence, String evidence) {
        Map<String, Object> ref = new LinkedHashMap<>(); ref.put("column", column); ref.put("referencedTable", table); ref.put("referencedColumn", targetColumn); ref.put("targetObjectId", targetId); ref.put("inferred", inferred); ref.put("confidence", confidence); ref.put("evidence", evidence); return ref;
    }

    private boolean completeJoinTable(List<String> primaryKeys, List<Map<String, Object>> references) {
        if (primaryKeys.size() != 2 || references.size() != 2) return false;
        Set<String> keyColumns = new HashSet<>(), referenceColumns = new HashSet<>();
        for (String key : primaryKeys) keyColumns.add(key.toLowerCase(Locale.ROOT));
        for (Map<String, Object> ref : references) referenceColumns.add(text(ref.get("column")).toLowerCase(Locale.ROOT));
        return keyColumns.equals(referenceColumns);
    }

    private String referenceStem(String column) {
        String snake = column.trim().replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
        return snake.endsWith("_id") && snake.length() > 3 ? snake.substring(0, snake.length() - 3) : null;
    }

    private boolean tableMatchesStem(String table, String stem) {
        String normalized = table.trim().replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        String[] parts = normalized.split("_"); String last = parts[parts.length - 1];
        return normalized.equals(stem) || last.equals(stem) || singular(last).equals(stem);
    }

    private String singular(String value) {
        if (value.endsWith("ies") && value.length() > 3) return value.substring(0, value.length() - 3) + "y";
        if (value.endsWith("s") && !value.endsWith("ss") && value.length() > 1) return value.substring(0, value.length() - 1);
        return value;
    }

    private String columnType(Map<String, Object> table, String name) {
        for (Map<String, Object> column : list(table.get("columns"))) if (name.equalsIgnoreCase(text(column.get("name")))) return text(column.get("type"));
        return null;
    }

    private boolean compatibleTypes(String left, String right) {
        return typeFamily(left).equals(typeFamily(right));
    }

    private String typeFamily(String value) {
        String type = value.toUpperCase(Locale.ROOT).replaceAll("\\(.*\\)", "").replace(" UNSIGNED", "").trim();
        if (Arrays.asList("TINYINT", "SMALLINT", "MEDIUMINT", "INT", "INTEGER", "BIGINT", "INT2", "INT4", "INT8", "SERIAL", "BIGSERIAL").contains(type)) return "INTEGER";
        if (Arrays.asList("DECIMAL", "NUMERIC", "NUMBER").contains(type)) return "DECIMAL";
        if (Arrays.asList("CHAR", "VARCHAR", "NCHAR", "NVARCHAR", "TEXT", "TINYTEXT", "MEDIUMTEXT", "LONGTEXT", "STRING", "CLOB").contains(type)) return "STRING";
        if (Arrays.asList("BINARY", "VARBINARY", "BLOB", "BYTEA").contains(type)) return "BINARY";
        return type;
    }

    private String objectName(Map<String, Object> object, String fallback) {
        String name = object == null ? null : text(object.get("name")); return name == null ? humanize(fallback) : name;
    }

    private String objectIdForTable(Map<String, String> objectIds, Object value) {
        if (!(value instanceof Map)) return null;
        Map<?, ?> table = (Map<?, ?>) value; String name = text(table.get("table"));
        if (name == null) name = text(table.get("name"));
        return name == null ? null : objectIds.get(key(text(table.get("schema")), name));
    }

    private Map<String, Object> stage5(Map<String, Object> stage2, Map<String, Object> stage3, Map<String, Object> stage4,
                                       List<Map<String, Object>> businessRules, List<Map<String, Object>> evidence,
                                       List<Map<String, Object>> warnings) {
        List<Map<String, Object>> logics = new ArrayList<>(), axioms = new ArrayList<>();
        for (Map<String, Object> relation : list(stage4.get("relations"))) {
            Map<String, Object> axiom = new LinkedHashMap<>(); axiom.put("type", "DOMAIN_RANGE"); axiom.put("relationId", relation.get("id")); axiom.put("domain", relation.get("sourceObjectId")); axiom.put("range", relation.get("targetObjectId")); axiom.put("status", "NEEDS_REVIEW"); axioms.add(axiom);
        }
        for (Map<String, Object> rule : businessRules == null ? Collections.<Map<String, Object>>emptyList() : businessRules) {
            String content = text(rule.get("content")); if (content == null) continue;
            Map<String, Object> logic = item(id("logic", content), text(rule.get("title")) == null ? "业务规则" : text(rule.get("title")), "businessRule", 0.55);
            logic.put("conditionText", content); logic.put("expression", ruleExpression(content)); logic.put("executable", !((List<?>) ((Map<?, ?>) logic.get("expression")).get("clauses")).isEmpty()); logic.put("risk", "MEDIUM");
            logics.add(logic); evidence.add(ev("businessRule", text(rule.get("sourceId")), "人工维护业务规则"));
        }
        Map<String, Object> consistency = new LinkedHashMap<>(); List<Map<String, Object>> violations = new ArrayList<>(); Set<String> objectIds = new HashSet<>(); for (Map<String, Object> o : list(stage2.get("objects"))) if (!objectIds.add(text(o.get("id")))) violations.add(warning("DUPLICATE_OBJECT", text(o.get("id")), "对象ID重复"));
        Map<String, String> known = new HashMap<>(); for (Map<String, Object> o : list(stage2.get("objects"))) known.put(text(o.get("id")), "object"); for (Map<String, Object> r : list(stage4.get("relations"))) { if (!known.containsKey(text(r.get("sourceObjectId"))) || !known.containsKey(text(r.get("targetObjectId")))) violations.add(warning("BROKEN_RELATION", text(r.get("id")), "关系引用不存在的对象")); }
        consistency.put("isConsistent", violations.isEmpty()); consistency.put("violations", violations); consistency.put("warnings", warnings); Map<String, Object> out = new LinkedHashMap<>(); out.put("axioms", axioms); out.put("logics", logics); out.put("consistencyReport", consistency); return out;
    }

    private Map<String, Object> stage6(List<Map<String, Object>> documents, List<Map<String,Object>> objects,
                                       List<Map<String, Object>> evidence) {
        List<Map<String, Object>> terms = new ArrayList<>(), mappings = new ArrayList<>(), governance = new ArrayList<>(); Set<String> seen = new HashSet<>(), mapped = new HashSet<>(), governed = new HashSet<>();
        Pattern heading = Pattern.compile("(?m)^#{1,6}\\s+(.{1,120})$");
        if (documents != null) for (Map<String, Object> doc : documents) {
            String content = text(doc.get("content")); String sourceId = text(doc.get("sourceId")); Matcher matcher = heading.matcher(content == null ? "" : content);
            while (matcher.find()) { String title = matcher.group(1).trim(); String key = normalize(title); if (title.isEmpty() || !seen.add(key)) continue;
                Map<String, Object> term = item(id("term", title), title, "documentHeading", 0.55); term.put("sourceId", doc.get("sourceId")); terms.add(term); evidence.add(ev("document", sourceId, "Markdown 标题术语"));
            }
            for (String rawLine : (content == null ? "" : content).split("\\r?\\n")) {
                String line = rawLine.replaceAll("^\\s*#+\\s*", "").trim();
                Matcher alias = ALIAS_LINE.matcher(line);
                if (alias.matches()) for (String value : alias.group(1).split("[,，、;；|]")) {
                    String candidate = value.trim(); if (candidate.isEmpty()) continue;
                    for (Map<String,Object> object : objects) {
                        String technicalName = text(object.get("technicalName")), objectName = text(object.get("name"));
                        if (!candidate.equalsIgnoreCase(technicalName == null ? "" : technicalName) && !normalize(candidate).equals(normalize(objectName))) continue;
                        String key = normalize(sourceId + "." + candidate + "." + text(object.get("id"))); if (!mapped.add(key)) continue;
                        Map<String,Object> mapping = new LinkedHashMap<>(); mapping.put("alias", candidate); mapping.put("targetObjectId", object.get("id")); mapping.put("targetObjectName", objectName); mapping.put("sourceId", doc.get("sourceId")); mapping.put("evidence", rawLine.trim()); mapping.put("status", "NEEDS_REVIEW"); mapping.put("reviewRequired", true); mappings.add(mapping);
                        evidence.add(ev("semanticAlias", sourceId, "Wiki 资料显式别名匹配到本体对象候选"));
                    }
                }
                if (GOVERNANCE_MARKER.matcher(line).find() && line.length() <= 300) {
                    String key = normalize(line); if (governed.add(key)) {
                        Map<String,Object> candidate = item(id("governance", line), clip(line, 120), "wikiPolicyText", 0.55);
                        candidate.put("sourceId", doc.get("sourceId")); candidate.put("conditionText", line); candidate.put("evidence", rawLine.trim()); candidate.put("reviewRequired", true); governance.add(candidate);
                        evidence.add(ev("governanceRule", sourceId, "Wiki 资料中的规范性语句，待治理审核"));
                    }
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>(); out.put("terms", terms); out.put("semanticMappings", mappings); out.put("governanceRules", governance); return out;
    }

    private Map<String, Object> stage7(List<Map<String, Object>> tables, List<String> queries,
                                       Map<String, Object> stage2, List<Map<String, Object>> evidence) {
        List<Map<String, Object>> actions = new ArrayList<>(), patterns = new ArrayList<>();
        List<Map<String, Object>> flows = new ArrayList<>(), permissions = new ArrayList<>();
        List<Map<String, Object>> warnings = new ArrayList<>();
        Map<String, Map<String, Object>> objectsByTable = new HashMap<>();
        for (Map<String, Object> object : list(stage2.get("objects"))) objectsByTable.put(text(object.get("table")).toLowerCase(Locale.ROOT), object);
        Set<String> actionKeys = new HashSet<>();
        if (queries != null) for (String query : queries) {
            Matcher kindMatcher = SQL_KIND.matcher(query == null ? "" : query);
            if (!kindMatcher.find()) continue;
            String kind = kindMatcher.group(1).toUpperCase(Locale.ROOT);
            Set<String> referencedTables = new LinkedHashSet<>();
            Matcher tableMatcher = SQL_TABLE.matcher(query);
            while (tableMatcher.find()) {
                String qualifiedName = tableMatcher.group(1);
                String tableName = qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
                if (objectsByTable.containsKey(tableName)) referencedTables.add(tableName);
            }
            Map<String, Object> pattern = new LinkedHashMap<>();
            pattern.put("type", kind); pattern.put("tables", new ArrayList<>(referencedTables));
            pattern.put("sample", clip(SQL_VALUE.matcher(query).replaceAll("?"), 500)); patterns.add(pattern);
            if (referencedTables.isEmpty()) {
                warnings.add(warning("UNMAPPED_QUERY_PATTERN", kind, "SQL 模式未能映射到扫描范围内的表，不生成动作候选"));
                continue;
            }
            for (String table : referencedTables) {
                Map<String, Object> object = objectsByTable.get(table);
                String objectId = text(object.get("id"));
                String actionType = "SELECT".equals(kind) ? "READ" : kind;
                String key = objectId + "." + actionType;
                if (!actionKeys.add(key)) continue;
                Map<String, Object> action = item(id("action", key), actionType + " " + text(object.get("name")), "queryPattern", "SELECT".equals(kind) ? 0.62 : 0.42);
                action.put("objectId", objectId); action.put("operation", actionType);
                action.put("transactional", !"SELECT".equals(kind));
                action.put("risk", "SELECT".equals(kind) ? "LOW" : "HIGH");
                action.put("evidence", "queryPattern"); actions.add(action);
                Map<String, Object> flow = new LinkedHashMap<>(); flow.put("id", id("flow", key)); flow.put("name", action.get("name")); flow.put("steps", Collections.singletonList(action.get("id"))); flow.put("trigger", "MCP_REQUEST"); flow.put("status", "NEEDS_REVIEW"); flows.add(flow);
                Map<String, Object> permission = new LinkedHashMap<>(); permission.put("id", id("permission", key)); permission.put("actionId", action.get("id")); permission.put("resourceId", objectId); permission.put("operation", actionType); permission.put("subjectType", "ROLE"); permission.put("suggestedSubjects", Collections.singletonList("ontology_operator")); permission.put("rowFilterRequired", !"READ".equals(actionType)); permission.put("status", "NEEDS_REVIEW"); permissions.add(permission);
                evidence.add(ev("queryPattern", table, "SQL 类型推导的动作候选，需要工程师审核"));
            }
        }
        if (actions.isEmpty()) {
            for (Map<String, Object> object : list(stage2.get("objects"))) {
                String objectId = text(object.get("id"));
                Map<String, Object> action = item(id("action", objectId + ".READ"), "READ " + text(object.get("name")), "schema", 0.50);
                action.put("objectId", objectId); action.put("operation", "READ");
                action.put("transactional", false); action.put("risk", "LOW");
                actions.add(action);
                Map<String, Object> flow = new LinkedHashMap<>(); flow.put("id", id("flow", objectId + ".READ")); flow.put("name", action.get("name")); flow.put("steps", Collections.singletonList(action.get("id"))); flow.put("trigger", "MCP_REQUEST"); flow.put("status", "NEEDS_REVIEW"); flows.add(flow);
                Map<String, Object> permission = new LinkedHashMap<>(); permission.put("id", id("permission", objectId + ".READ")); permission.put("actionId", action.get("id")); permission.put("resourceId", objectId); permission.put("operation", "READ"); permission.put("subjectType", "ROLE"); permission.put("suggestedSubjects", Collections.singletonList("ontology_reader")); permission.put("rowFilterRequired", true); permission.put("status", "NEEDS_REVIEW"); permissions.add(permission);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("actions", actions); out.put("serviceFlows", flows);
        out.put("permissionCandidates", permissions); out.put("queryPatterns", patterns);
        out.put("warnings", warnings); return out;
    }

    private Map<String, Object> item(String id, String name, String source, double confidence) { Map<String, Object> m = new LinkedHashMap<>(); m.put("id", id); m.put("name", name); m.put("source", source); m.put("confidence", confidence); m.put("status", "NEEDS_REVIEW"); return m; }
    private Map<String, Object> targetConfig(Map<String, Object> candidate) {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("format", "ontology-platform-generic"); target.put("version", "1.0"); target.put("reviewRequired", true);
        target.put("objects", candidate.get("objects")); target.put("properties", candidate.get("properties"));
        target.put("relations", candidate.get("relations")); target.put("logics", candidate.get("logics"));
        target.put("actions", candidate.get("actions")); target.put("serviceFlows", candidate.get("serviceFlows"));
        target.put("permissions", candidate.get("permissionCandidates")); target.put("mappings", candidate.get("mappings"));
        target.put("validationRules", candidate.get("validationRules")); target.put("axioms", candidate.get("axioms"));
        target.put("metadata", metadata(candidate));
        return target;
    }

    private Map<String, Object> metadata(Map<String, Object> candidate) {
        Map<String, Object> metadata = new LinkedHashMap<>(); metadata.put("engine", "java-deterministic-v1");
        metadata.put("candidateCount", count(candidate.get("objects")) + count(candidate.get("properties")) + count(candidate.get("relations")) + count(candidate.get("actions")));
        metadata.put("status", "DRAFT"); return metadata;
    }

    private int count(Object value) { return value instanceof Collection ? ((Collection<?>) value).size() : 0; }

    private Map<String, Object> ruleExpression(String content) {
        Map<String, Object> expression = new LinkedHashMap<>(); List<Map<String, Object>> clauses = new ArrayList<>();
        Matcher matcher = RULE_CLAUSE.matcher(content);
        while (matcher.find() && clauses.size() < 20) { Map<String, Object> clause = new LinkedHashMap<>(); clause.put("field", matcher.group(1)); clause.put("operator", normalizeOperator(matcher.group(2))); clause.put("value", matcher.group(3).trim()); clauses.add(clause); }
        expression.put("type", clauses.size() > 1 ? (content.matches("(?i).*(或|\\bor\\b).*") ? "OR" : "AND") : "PREDICATE"); expression.put("clauses", clauses); expression.put("source", content); return expression;
    }

    private String normalizeOperator(String operator) { if (operator == null) return "="; if (operator.contains("不") || operator.contains("!")) return "!="; if (operator.contains("大于等于") || operator.contains(">=")) return ">="; if (operator.contains("小于等于") || operator.contains("<=")) return "<="; if (operator.contains("大于") || operator.contains(">")) return ">"; if (operator.contains("小于") || operator.contains("<")) return "<"; return "="; }
    private Map<String, Object> rule(String type, String objectId, String column) { Map<String, Object> r = new LinkedHashMap<>(); r.put("type", type); r.put("objectId", objectId); r.put("column", column); r.put("risk", "LOW"); return r; }
    private Map<String, Object> report(int stage, String name, Map<String, Object> data) { Map<String, Object> r = new LinkedHashMap<>(); r.put("stage", stage); r.put("name", name); r.put("result", data); return r; }
    private Map<String, Object> ev(String type, String ref, String description) { Map<String, Object> e = new LinkedHashMap<>(); e.put("type", type); e.put("ref", ref); e.put("description", description); return e; }
    private Map<String, Object> warning(String code, String ref, String message) { Map<String, Object> w = new LinkedHashMap<>(); w.put("code", code); w.put("ref", ref); w.put("message", message); return w; }
    private String id(String prefix, String value) { return prefix + "_" + normalize(value); }
    private String tableKey(Map<String, Object> table) { String tableName = text(table.get("table")); return key(text(table.get("schema")), tableName == null ? text(table.get("name")) : tableName); }
    private String key(String schema, String table) { return (schema == null ? "" : schema.toLowerCase(Locale.ROOT) + ".") + (table == null ? "" : table.toLowerCase(Locale.ROOT)); }
    private String normalize(String value) { String v = value == null ? "unknown" : value.trim().toLowerCase(Locale.ROOT); v = WORD.matcher(v).replaceAll("_"); return v.length() > 80 ? v.substring(0, 80) : v; }
    private String humanize(String value) { return value == null ? "未命名" : value.replace('_', ' ').replace('-', ' '); }
    private Object findIgnoreCase(Map<String, Object> row, String column) {
        if (row == null || column == null) return null;
        for (Map.Entry<String, Object> entry : row.entrySet()) if (column.equalsIgnoreCase(entry.getKey())) return entry.getValue();
        return null;
    }
    private String text(Object value) { return value == null || String.valueOf(value).trim().isEmpty() ? null : String.valueOf(value).trim(); }
    @SuppressWarnings("unchecked") private List<Map<String, Object>> list(Object value) { return value instanceof List ? (List<Map<String, Object>>) value : Collections.<Map<String, Object>>emptyList(); }
    private List<String> strings(Object value) { List<String> r = new ArrayList<>(); if (value instanceof Collection) for (Object x : (Collection<?>) value) if (x != null) r.add(String.valueOf(x)); return r; }
    private List<String> ids(List<Map<String, Object>> nodes) { List<String> r = new ArrayList<>(); for (Map<String, Object> n : nodes) r.add(text(n.get("id"))); return r; }
    private String clip(String value, int max) { return value == null ? "" : value.substring(0, Math.min(max, value.length())); }
}

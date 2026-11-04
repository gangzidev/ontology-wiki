package com.ontologywiki.ontology.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Exports reviewed generation candidates in the sample platform's RDF/TAR layouts. */
@Service
public class OntologyArtifactExporter {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS");
    private final ObjectMapper json = new ObjectMapper();

    public Artifact rdf(Map<String, Object> config, String proposalRef, String ontologyLabel, String datasourceName) {
        Map<String, Object> candidate = candidate(config);
        List<Map<String, Object>> objects = rows(candidate.get("objects"));
        List<Map<String, Object>> properties = rows(candidate.get("properties"));
        List<Map<String, Object>> relations = rows(candidate.get("relations"));
        List<Map<String, Object>> actions = rows(candidate.get("actions"));
        List<Map<String, Object>> logics = rows(candidate.get("logics"));
        Map<String, String> objectIris = new LinkedHashMap<>();
        Set<String> used = new HashSet<>();
        for (Map<String, Object> object : objects) objectIris.put(str(object.get("id")), uniqueLocal(object.get("name"), object.get("id"), used));
        Map<String, String> propertyIris = new HashMap<>();
        for (Map<String, Object> property : properties) {
            String objectId = str(property.get("objectId"));
            String objectIri = objectIris.get(objectId);
            if (objectIri != null) propertyIris.put(key(objectId, column(property)), uniqueLocal(objectIri + "_" + column(property), property.get("id"), used));
        }
        Set<String> primaryKeys = primaryKeys(candidate);
        String ontologyId = stableId("ontology:" + proposalRef);
        String ontologyName = uniqueLocal(ontologyLabel, ontologyId, used);
        StringBuilder out = new StringBuilder(16384);
        out.append("@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n")
           .append("@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n")
           .append("@prefix owl: <http://www.w3.org/2002/07/owl#> .\n")
           .append("@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .\n")
           .append("@prefix prov: <http://www.w3.org/ns/prov#> .\n")
           .append("@prefix dcterms: <http://purl.org/dc/terms/> .\n")
           .append("@prefix ex: <http://asiainfo.com/").append(ontologyId).append("#> .\n\n")
           .append("ex:Entity a owl:Class ; rdfs:subClassOf prov:Entity ; rdfs:label \"实体\"@zh .\n")
           .append("ex:Object a owl:Class ; rdfs:subClassOf ex:Entity ; rdfs:label \"本体对象\"@zh .\n")
           .append("ex:Action a owl:Class ; rdfs:subClassOf prov:Activity ; rdfs:label \"行动\"@zh .\n")
           .append("ex:Logic a owl:Class ; rdfs:subClassOf prov:Activity ; rdfs:label \"决策/逻辑函数\"@zh .\n")
           .append("ex:ontologyName a owl:Class ; rdfs:label \"本体名称\"@zh .\n")
           .append("ex:hasPrimaryKey a owl:AnnotationProperty ; rdfs:label \"主键属性\"@zh .\n")
           .append("ex:hasTitle a owl:AnnotationProperty ; rdfs:label \"标题属性\"@zh .\n")
           .append("ex:bindAction a owl:ObjectProperty ; rdfs:domain ex:Object ; rdfs:range ex:Action ; rdfs:label \"关联行动\"@zh .\n")
           .append("ex:bindLogic a owl:ObjectProperty ; rdfs:domain ex:Object ; rdfs:range ex:Logic ; rdfs:label \"关联逻辑\"@zh .\n")
           .append("ex:custom_relation a owl:ObjectProperty ; rdfs:label \"自定义对象关系\"@zh .\n")
           .append("ex:domainAttribute a owl:AnnotationProperty ; rdfs:label \"域属性\"@zh .\n")
           .append("ex:rangeAttribute a owl:AnnotationProperty ; rdfs:label \"值域属性\"@zh .\n")
           .append("ex:relationLabel a owl:AnnotationProperty ; rdfs:label \"关系标签\"@zh .\n")
           .append("ex:hasMiddleObject a owl:ObjectProperty ; rdfs:label \"中间对象\"@zh .\n")
           .append("ex:tableName a owl:AnnotationProperty .\nex:dbSchema a owl:AnnotationProperty .\nex:datasourceName a owl:AnnotationProperty .\n")
           .append("ex:fieldName a owl:AnnotationProperty .\nex:confidence a owl:AnnotationProperty .\nex:evidence a owl:AnnotationProperty .\n")
           .append("ex:reviewRequired a owl:AnnotationProperty .\nex:operation a owl:AnnotationProperty .\nex:transactional a owl:AnnotationProperty .\n")
           .append("ex:risk a owl:AnnotationProperty .\nex:conditionText a owl:AnnotationProperty .\nex:inferenceMethod a owl:AnnotationProperty .\n\n")
           .append("ex:").append(ontologyName).append(" a ex:ontologyName ; rdfs:label ").append(lit(ontologyLabel, "zh")).append(" .\n\n");

        Map<String, List<Map<String, Object>>> propertiesByObject = group(properties, "objectId");
        for (Map<String, Object> object : objects) {
            String objectId = str(object.get("id"));
            String iri = objectIris.get(objectId);
            out.append("ex:").append(iri).append(" a owl:Class ; rdfs:subClassOf ex:Object ;\n  rdfs:label ")
               .append(lit(str(object.get("name")), "zh"));
            String technical = first(object.get("technicalName"), object.get("table"));
            if (!technical.isEmpty() && technical.matches("[A-Za-z_][A-Za-z0-9_]*")) out.append(" ;\n  rdfs:label ").append(lit(technical, "en"));
            appendLiteral(out, "rdfs:comment", object.get("description"), "zh");
            appendLiteral(out, "ex:tableName", object.get("table"), null);
            appendLiteral(out, "ex:dbSchema", object.get("schema"), null);
            if (!datasourceName.isEmpty()) appendLiteral(out, "ex:datasourceName", datasourceName, null);
            out.append(" ;\n  ex:reviewRequired true");
            for (Map<String, Object> property : propertiesByObject.getOrDefault(objectId, Collections.<Map<String, Object>>emptyList())) {
                String field = column(property);
                String propertyIri = propertyIris.get(key(objectId, field));
                if (primaryKeys.contains(key(objectId, field))) out.append(" ;\n  ex:hasPrimaryKey ex:").append(propertyIri);
                if (truth(property.get("isTitle")) || truth(property.get("is_title"))) out.append(" ;\n  ex:hasTitle ex:").append(propertyIri);
            }
            out.append(" .\n\n");
        }
        for (Map<String, Object> property : properties) {
            String objectId = str(property.get("objectId"));
            String domain = objectIris.get(objectId);
            String iri = propertyIris.get(key(objectId, column(property)));
            if (domain == null || iri == null) continue;
            out.append("ex:").append(iri).append(" a owl:DatatypeProperty ;\n  rdfs:domain ex:").append(domain)
               .append(" ;\n  rdfs:range xsd:").append(xsdType(property.get("dataType")))
               .append(" ;\n  rdfs:label ").append(lit(first(property.get("name"), column(property)), "zh"));
            String label = column(property);
            if (label.matches("[A-Za-z_][A-Za-z0-9_]*")) out.append(" ;\n  rdfs:label ").append(lit(label, "en"));
            appendLiteral(out, "rdfs:comment", property.get("description"), "zh");
            appendLiteral(out, "ex:fieldName", column(property), null);
            out.append(" ;\n  ex:reviewRequired true .\n\n");
        }
        for (Map<String, Object> relation : relations) {
            String sourceId = str(relation.get("sourceObjectId")), targetId = str(relation.get("targetObjectId"));
            String source = objectIris.get(sourceId), target = objectIris.get(targetId);
            if (source == null || target == null) continue;
            String iri = uniqueLocal(source + "_" + first(relation.get("name"), "related") + "_" + target, relation.get("id"), used);
            out.append("ex:").append(iri).append(" a owl:ObjectProperty ; rdfs:subPropertyOf ex:custom_relation ;\n  rdfs:domain ex:")
               .append(source).append(" ;\n  rdfs:range ex:").append(target)
               .append(" ;\n  rdfs:label ").append(lit(first(relation.get("name"), "对象关系"), "zh"))
               .append(" ;\n  ex:relationLabel ").append(lit(first(relation.get("name"), "related"), "zh"));
            String sourceAttr = propertyIris.get(key(sourceId, str(relation.get("sourceColumn"))));
            String targetAttr = propertyIris.get(key(targetId, str(relation.get("targetColumn"))));
            if (sourceAttr != null) out.append(" ;\n  ex:domainAttribute ex:").append(sourceAttr);
            if (targetAttr != null) out.append(" ;\n  ex:rangeAttribute ex:").append(targetAttr);
            String via = objectIris.get(str(relation.get("viaObjectId")));
            if (via != null) out.append(" ;\n  ex:hasMiddleObject ex:").append(via);
            appendLiteral(out, "ex:evidence", relation.get("evidence"), null);
            appendLiteral(out, "ex:confidence", relation.get("confidence"), null);
            out.append(" ;\n  ex:reviewRequired true .\n\n");
        }
        writeRdfCandidates(out, actions, "Action", objectIris, "bindAction", used);
        writeRdfCandidates(out, logics, "Logic", objectIris, "bindLogic", used);
        return new Artifact(ontologyFileName(ontologyLabel, proposalRef) + ".owl", "text/turtle; charset=UTF-8", out.toString().getBytes(StandardCharsets.UTF_8));
    }

    public Artifact engineeringProject(Map<String, Object> config, String proposalRef, String ontologyLabel,
                                       String owner, String datasourceName, String datasourceSchema, String dbType) {
        Map<String, Object> candidate = candidate(config);
        List<Map<String, Object>> objects = rows(candidate.get("objects"));
        List<Map<String, Object>> properties = rows(candidate.get("properties"));
        List<Map<String, Object>> relations = rows(candidate.get("relations"));
        List<Map<String, Object>> logics = rows(candidate.get("logics"));
        Map<String, Object> ontology = new LinkedHashMap<>();
        String root = ontologyFileName(ontologyLabel, proposalRef);
        String ontologyId = stableId("ontology:" + proposalRef);
        String now = LocalDateTime.now(ZoneOffset.UTC).format(TIME);
        Map<String, String> platformObjectIds = new LinkedHashMap<>();
        for (Map<String, Object> object : objects) platformObjectIds.put(str(object.get("id")), stableId("object:" + proposalRef + ":" + str(object.get("id"))));
        Map<String, String> attributeIds = new HashMap<>();
        Map<String, String> attributeLabels = new HashMap<>();
        Map<String, List<Map<String, Object>>> propsByObject = group(properties, "objectId");
        Set<String> primary = primaryKeys(candidate);
        for (Map<String, Object> property : properties) {
            String objectId = str(property.get("objectId")), field = column(property);
            String attrId = stableId("attribute:" + proposalRef + ":" + objectId + ":" + field);
            attributeIds.put(key(objectId, field), attrId);
            attributeLabels.put(key(objectId, field), first(property.get("name"), field));
        }
        List<Map<String, Object>> nativeRelations = new ArrayList<>(), skippedRelations = new ArrayList<>();
        for (Map<String, Object> relation : relations) {
            String sourceId = str(relation.get("sourceObjectId")), targetId = str(relation.get("targetObjectId"));
            String sourceAttribute = attributeIds.get(key(sourceId, str(relation.get("sourceColumn"))));
            String targetAttribute = attributeIds.get(key(targetId, str(relation.get("targetColumn"))));
            if (sourceAttribute == null || targetAttribute == null) skippedRelations.add(relation);
            else nativeRelations.add(relation);
        }
        int attributeCount = properties.size();
        ontology.put("id", ontologyId); ontology.put("create_time", now); ontology.put("last_update", now);
        ontology.put("oper_status", 0); ontology.put("sync_status", 0); ontology.put("is_favorite", null); ontology.put("latest_version", null);
        ontology.put("ontology_desc", "由本体 Wiki 生成的待审核工程包；导入前请确认数据源绑定、关系和候选代码。");
        ontology.put("ontology_label", ontologyLabel); ontology.put("ontology_name", root); ontology.put("owner_id", owner);
        ontology.put("publish_time", null); ontology.put("publish_user", null); ontology.put("status", 0); ontology.put("sync_label", null);
        ontology.put("workspace_id", "workdir"); ontology.put("is_recommend", null); ontology.put("build_status", 0); ontology.put("build_type", 0);
        ontology.put("is_review", 1); ontology.put("original_id", ontologyId); ontology.put("export_time", now + "Z");
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("objects", objects.size()); stats.put("attributes", attributeCount); stats.put("relations", nativeRelations.size());
        stats.put("relation_tags", nativeRelations.size() * 2); stats.put("actions", 0); stats.put("action_params", 0);
        stats.put("logic", logics.size()); stats.put("apis", 0); stats.put("api_params", 0); stats.put("prompt", 0);
        stats.put("interfaces", 0); stats.put("interface_attributes", 0); stats.put("interface_constraints", 0); ontology.put("stats", stats);

        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            TarArchiveOutputStream tar = new TarArchiveOutputStream(bytes, "UTF-8");
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
            for (String dir : Arrays.asList(root, root + "/objects", root + "/relations", root + "/logic", root + "/actions", root + "/apis", root + "/prompt", root + "/interface", root + "/interface/interface_objects", root + "/interface/interface_constraints", root + "/code", root + "/code/ontology", root + "/code/ontology/" + root + "/objects", root + "/code/ontology/" + root + "/logics", root + "/review")) addDirectory(tar, dir);
            addJson(tar, root + "/ontology.json", ontology);
            for (Map<String, Object> object : objects) {
                String sourceId = str(object.get("id")), platformId = platformObjectIds.get(sourceId);
                String className = className(first(object.get("technicalName"), object.get("name")), platformId);
                List<Map<String, Object>> attrs = propsByObject.getOrDefault(sourceId, Collections.<Map<String, Object>>emptyList());
                Map<String, Object> objectType = objectType(object, platformId, ontologyId, owner, now, datasourceName, datasourceSchema, dbType, className);
                List<Map<String, Object>> attributeRows = new ArrayList<>();
                String primaryLabel = null, titleLabel = null;
                for (Map<String, Object> property : attrs) {
                    String field = column(property), attrId = attributeIds.get(key(sourceId, field));
                    boolean isPk = primary.contains(key(sourceId, field));
                    Map<String, Object> attr = attribute(property, attrId, platformId, now, isPk);
                    attributeRows.add(attr);
                    if (isPk && primaryLabel == null) primaryLabel = str(attr.get("attribute_label"));
                    if (truth(property.get("isTitle")) || truth(property.get("is_title"))) titleLabel = str(attr.get("attribute_label"));
                }
                if (titleLabel == null) for (Map<String, Object> attr : attributeRows) if (number(attr.get("is_primary_key")) == 0) { titleLabel = str(attr.get("attribute_label")); break; }
                Map<String, Object> file = new LinkedHashMap<>(); file.put("object_type", objectType); file.put("attributes", attributeRows);
                addJson(tar, root + "/objects/" + className + "__" + platformId.substring(0, 8) + ".json", file);
                addText(tar, root + "/code/ontology/" + root + "/objects/" + className + ".py", objectPython(className, object, attrs, primaryLabel));
            }
            for (Map<String, Object> relation : nativeRelations) {
                Map<String, Object> file = relationFile(relation, proposalRef, ontologyId, owner, now, platformObjectIds, attributeIds, attributeLabels, objects);
                String name = safeFile(className(str(relation.get("name")), stableId("relation-name:" + str(relation.get("id")))));
                String source = className(objectName(objects, str(relation.get("sourceObjectId"))), "src");
                String target = className(objectName(objects, str(relation.get("targetObjectId"))), "dst");
                addJson(tar, root + "/relations/" + source + "-" + target + "-" + name + ".json", file);
            }
            for (Map<String, Object> logic : logics) {
                String id = stableId("logic:" + proposalRef + ":" + str(logic.get("id")));
                String functionName = snake(first(logic.get("technicalName"), logic.get("name"), "generated_logic"));
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", id); item.put("create_time", now); item.put("last_update", now); item.put("oper_status", 0); item.put("sync_status", 0);
                item.put("build_type", "function"); item.put("file_name", functionName); item.put("function_code", null); item.put("intput_param", null);
                item.put("logic_type_desc", first(logic.get("conditionText"), logic.get("description"), "待审核逻辑候选"));
                item.put("logic_type_label", first(logic.get("name"), functionName)); item.put("logic_type_name", functionName);
                item.put("ontology_id", ontologyId); item.put("output_param", null); item.put("owner_id", owner); item.put("repo_name", null);
                item.put("status", 1); item.put("signature_detail", null); item.put("api_id", null); item.put("original_id", id);
                Map<String, Object> used = new LinkedHashMap<>(); used.put("object_type_original_id", platformObjectIds.get(str(logic.get("objectId"))));
                used.put("object_type_name", objectName(objects, str(logic.get("objectId"))));
                Map<String, Object> logicFile = new LinkedHashMap<>(); logicFile.put("logic", item);
                logicFile.put("used_objects", logic.get("objectId") == null ? Collections.emptyList() : Collections.singletonList(used));
                addJson(tar, root + "/logic/" + functionName + "__" + id.substring(0, 8) + ".json", logicFile);
                addText(tar, root + "/code/ontology/" + root + "/logics/" + functionName + ".py", logicPython(functionName, logic));
            }
            Map<String, Object> review = new LinkedHashMap<>();
            review.put("format", "ontology-wiki-candidate-review-v1"); review.put("proposalRef", proposalRef);
            review.put("candidateConfig", candidate); review.put("nativeExportWarnings", Arrays.asList(
                    "Data-source IDs belong to the source system and are left unbound; bind a target-platform data source during import.",
                    "The supplied TAR has no action/API/interface examples. Action candidates are retained here for review, not emitted as native action records.",
                    "Logic code is a non-executable stub and must be implemented and tested in the ontology platform sandbox."));
            review.put("relationsSkippedWithoutFieldMapping", skippedRelations);
            addJson(tar, root + "/review/candidate-review.json", review);
            addText(tar, root + "/README.md", "# " + ontologyLabel + "\n\nGenerated from an ontology candidate. Review object and relation mappings and bind the target platform data source before import.\n\nAction candidates are preserved in `review/candidate-review.json`; this export sample contains no native action schema, so they are not emitted as native action records. Logic modules are stubs only and are not executable business logic. Validate the imported project in the target platform sandbox.\n");
            tar.finish(); tar.close();
            return new Artifact(root + ".tar", "application/x-tar", bytes.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("本体工程 TAR 导出失败", e);
        }
    }

    private void writeRdfCandidates(StringBuilder out, List<Map<String, Object>> candidates, String type,
                                    Map<String, String> objectIris, String bindPredicate, Set<String> used) {
        for (Map<String, Object> item : candidates) {
            String iri = uniqueLocal(type.toLowerCase(Locale.ROOT) + "_" + first(item.get("technicalName"), item.get("name")), item.get("id"), used);
            out.append("ex:").append(iri).append(" a ex:").append(type).append(" ; rdfs:label ").append(lit(first(item.get("name"), type), "zh"));
            appendLiteral(out, "rdfs:comment", first(item.get("description"), item.get("conditionText"), item.get("evidence")), "zh");
            appendLiteral(out, "ex:operation", item.get("operation"), null);
            appendLiteral(out, "ex:transactional", item.get("transactional"), null);
            appendLiteral(out, "ex:risk", item.get("risk"), null);
            appendLiteral(out, "ex:conditionText", item.get("conditionText"), null);
            appendLiteral(out, "ex:confidence", item.get("confidence"), null);
            out.append(" ;\n  ex:reviewRequired true .\n");
            String objectIri = objectIris.get(str(item.get("objectId")));
            if (objectIri != null) out.append("ex:").append(objectIri).append(" ex:").append(bindPredicate).append(" ex:").append(iri).append(" .\n");
        }
        if (!candidates.isEmpty()) out.append('\n');
    }

    private Map<String, Object> objectType(Map<String, Object> object, String id, String ontologyId, String owner, String now,
                                           String dsName, String fallbackSchema, String dbType, String className) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id); out.put("create_time", now); out.put("last_update", now); out.put("oper_status", 0); out.put("sync_status", 0);
        out.put("ds_id", null); out.put("ds_schema", first(object.get("schema"), fallbackSchema)); out.put("icon", "IconUserColor-primary");
        out.put("object_type_desc", first(object.get("description"), object.get("name"))); out.put("object_type_label", first(object.get("name"), className));
        out.put("object_type_name", className); out.put("ontology_id", ontologyId); out.put("owner_id", owner); out.put("status", 1);
        out.put("table_name", object.get("table")); out.put("link_type_id", null); out.put("api_id", null);
        out.put("interface_icon", null); out.put("interface_id", null); out.put("ds_name", dsName);
        out.put("ds_type", "mysql".equalsIgnoreCase(dbType) ? 0 : null); out.put("custom_sql", null); out.put("oag_tag", ""); out.put("original_id", id);
        return out;
    }

    private Map<String, Object> attribute(Map<String, Object> property, String id, String objectId, String now, boolean primaryKey) {
        Map<String, Object> out = new LinkedHashMap<>();
        String label = first(property.get("name"), column(property));
        out.put("id", id); out.put("create_time", now); out.put("last_update", now); out.put("oper_status", 0); out.put("sync_status", 0);
        out.put("attribute_desc", first(property.get("description"), label)); out.put("attribute_inst", null);
        out.put("attribute_name", column(property)); out.put("field_name", column(property)); out.put("field_type", platformType(property.get("dataType")));
        out.put("is_primary_key", primaryKey ? 1 : 0); out.put("is_title", truth(property.get("isTitle")) || truth(property.get("is_title")) ? 1 : 0);
        out.put("object_type_id", objectId); out.put("shared_attribute_id", null); out.put("status", 1); out.put("attribute_label", label);
        out.put("interface_attr_id", null); out.put("interface_type", null); out.put("oag_tag", ""); out.put("enum_desc", null); out.put("enum_json", null); out.put("is_enum", 0);
        out.put("original_id", id); out.put("object_type_original_id", objectId); return out;
    }

    private Map<String, Object> relationFile(Map<String, Object> relation, String proposalRef, String ontologyId, String owner, String now,
                                             Map<String, String> objectIds, Map<String, String> attributeIds,
                                             Map<String, String> attributeLabels, List<Map<String, Object>> objects) {
        String id = stableId("relation:" + proposalRef + ":" + str(relation.get("id")));
        String sourceId = str(relation.get("sourceObjectId")), targetId = str(relation.get("targetObjectId"));
        String sourceObjectId = objectIds.get(sourceId), targetObjectId = objectIds.get(targetId);
        String sourceAttrId = attributeIds.get(key(sourceId, str(relation.get("sourceColumn"))));
        String targetAttrId = attributeIds.get(key(targetId, str(relation.get("targetColumn"))));
        Map<String, Object> link = new LinkedHashMap<>();
        link.put("id", id); link.put("create_time", now); link.put("last_update", now); link.put("oper_status", 0); link.put("sync_status", 0);
        link.put("link_method", 2); link.put("link_type", 1); link.put("middle_ds_id", null);
        link.put("middle_ds_schema", relation.get("schema")); link.put("middle_source_field", relation.get("viaSourceColumn"));
        link.put("middle_table_name", relation.get("viaTable")); link.put("middle_target_field", relation.get("viaTargetColumn"));
        link.put("ontology_id", ontologyId); link.put("owner_id", owner); link.put("source_attribute_id", sourceAttrId); link.put("source_label", null);
        link.put("source_name", objectName(objects, sourceId)); link.put("source_object_type_id", sourceObjectId); link.put("status", 1);
        link.put("target_attribute_id", targetAttrId); link.put("target_label", null); link.put("target_name", objectName(objects, targetId));
        link.put("target_object_type_id", targetObjectId); link.put("relation_constraint_type", null); link.put("original_id", id);
        link.put("source_object_type_original_id", sourceObjectId); link.put("target_object_type_original_id", targetObjectId);
        String name = first(relation.get("name"), "相关");
        List<Map<String, Object>> tags = new ArrayList<>();
        String sourceTag = snake(name), reverseTag = snake("反向_" + name);
        tags.add(tag(stableId("tag-type:" + sourceTag + ":source"), stableId("link-tag:" + id + ":source"), now, sourceTag, name, str(relation.get("evidence")), "source"));
        tags.add(tag(stableId("tag-type:" + reverseTag + ":target"), stableId("link-tag:" + id + ":target"), now, reverseTag, "反向：" + name, "由生成器补出的反向关系候选，需人工核对", "target"));
        Map<String, Object> result = new LinkedHashMap<>(); result.put("link_type", link); result.put("tags", tags); return result;
    }

    private Map<String, Object> tag(String tagTypeId, String linkTagId, String now, String name, String label, String description, String direction) {
        Map<String, Object> tag = new LinkedHashMap<>();
        tag.put("id", tagTypeId); tag.put("create_time", now); tag.put("last_update", now); tag.put("oper_status", 0); tag.put("sync_status", 0);
        tag.put("tag_desc", description); tag.put("tag_label", label); tag.put("tag_name", name); tag.put("link_direct", direction);
        tag.put("link_type_tag_id", linkTagId); return tag;
    }

    private Map<String, Object> candidate(Map<String, Object> config) {
        if (config == null) throw new IllegalArgumentException("配置草案为空");
        if (config.get("objects") instanceof List) return config;
        Object target = config.get("targetConfig");
        if (target instanceof Map) return (Map<String, Object>) target;
        throw new IllegalArgumentException("草案中没有对象候选");
    }

    private Set<String> primaryKeys(Map<String, Object> candidate) {
        Set<String> result = new HashSet<>();
        for (Map<String, Object> identity : rows(candidate.get("identitySpecs"))) {
            String objectId = str(identity.get("objectId"));
            for (String column : strings(identity.get("columns"))) result.add(key(objectId, column));
        }
        for (Map<String, Object> property : rows(candidate.get("properties"))) if (truth(property.get("isPrimaryKey")) || truth(property.get("is_primary_key"))) result.add(key(str(property.get("objectId")), column(property)));
        return result;
    }

    private Map<String, List<Map<String, Object>>> group(List<Map<String, Object>> rows, String key) {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) result.computeIfAbsent(str(row.get(key)), k -> new ArrayList<>()).add(row);
        return result;
    }

    private List<Map<String, Object>> rows(Object value) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (value instanceof Collection) for (Object item : (Collection<?>) value) if (item instanceof Map) result.add((Map<String, Object>) item);
        return result;
    }

    private List<String> strings(Object value) {
        List<String> out = new ArrayList<>(); if (value instanceof Collection) for (Object item : (Collection<?>) value) if (item != null) out.add(String.valueOf(item)); return out;
    }

    private void addDirectory(TarArchiveOutputStream tar, String path) throws Exception {
        TarArchiveEntry entry = new TarArchiveEntry(path + "/"); entry.setSize(0); tar.putArchiveEntry(entry); tar.closeArchiveEntry();
    }

    private void addJson(TarArchiveOutputStream tar, String path, Object value) throws Exception {
        addText(tar, path, json.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n");
    }

    private void addText(TarArchiveOutputStream tar, String path, String text) throws Exception {
        byte[] data = text.getBytes(StandardCharsets.UTF_8); TarArchiveEntry entry = new TarArchiveEntry(path); entry.setSize(data.length);
        tar.putArchiveEntry(entry); tar.write(data); tar.closeArchiveEntry();
    }

    private String objectPython(String className, Map<String, Object> object, List<Map<String, Object>> properties, String primaryLabel) {
        StringBuilder out = new StringBuilder("# Generated candidate. Review and test in the target ontology platform.\n# -*- coding: utf-8 -*-\n\nfrom core.runtime.base import OntologyObject\n\nclass ")
                .append(className).append("(OntologyObject):\n    \"\"\"").append(py(first(object.get("description"), object.get("name"), className))).append("\"\"\"\n\n    def __init__(self) -> None:\n        self._table_name = ")
                .append(object.get("table") == null ? "None" : py(str(object.get("table")))).append("\n");
        if (properties.isEmpty()) out.append("        self._field_map = None\n");
        else {
            out.append("        self._field_map = {\n");
            for (Map<String, Object> property : properties) out.append("            ").append(py(first(property.get("name"), column(property)))).append(": ").append(py(column(property))).append(",\n");
            out.append("        }\n");
        }
        out.append("        self._primary_key_attr = ").append(primaryLabel == null ? "None" : py(primaryLabel)).append("\n        self._pre_sql = None\n");
        return out.toString();
    }

    private String logicPython(String name, Map<String, Object> logic) {
        return "# Generated candidate stub. Implement and sandbox-test before use.\n# " + py(first(logic.get("conditionText"), logic.get("name"), "logic")) + "\n\ndef " + name + "(**kwargs):\n    raise NotImplementedError('待本体工程师实现并在沙箱验证')\n";
    }

    private void appendLiteral(StringBuilder out, String predicate, Object value, String lang) {
        if (value == null || String.valueOf(value).trim().isEmpty()) return;
        out.append(" ;\n  ").append(predicate).append(' ');
        if (lang == null && (value instanceof Boolean || value instanceof Number)) out.append(value);
        else out.append(lit(String.valueOf(value), lang));
    }

    private String lit(String value, String lang) { return "\"" + turtle(value) + "\"" + (lang == null ? "" : "@" + lang); }
    private String turtle(String value) { return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t"); }
    private String py(String value) { return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n") + "\""; }
    private String xsdType(Object value) {
        String type = str(value).toLowerCase(Locale.ROOT);
        if (type.matches(".*(bool).*")) return "boolean";
        if (type.matches(".*(date|time).*")) return type.contains("time") ? "dateTime" : "date";
        if (type.matches(".*(decimal|numeric|number|float|double|real).*")) return "decimal";
        if (type.matches(".*(int|serial).*")) return "integer";
        return "string";
    }
    private String platformType(Object value) {
        String type = str(value).toLowerCase(Locale.ROOT);
        if (type.contains("bool")) return "bool";
        if (type.matches(".*(date|time).*")) return "datetime";
        if (type.matches(".*(decimal|numeric|number|float|double|real).*")) return "float";
        if (type.matches(".*(int|serial).*")) return "int";
        return "string";
    }
    private boolean truth(Object value) { return Boolean.TRUE.equals(value) || value instanceof Number && ((Number) value).intValue() != 0 || "true".equalsIgnoreCase(str(value)); }
    private int number(Object value) { return value instanceof Number ? ((Number) value).intValue() : 0; }
    private String column(Map<String, Object> property) { return first(property.get("technicalName"), property.get("column"), property.get("name"), "field"); }
    private String key(String objectId, String column) { return str(objectId) + "\u0000" + str(column).toLowerCase(Locale.ROOT); }
    private String objectName(List<Map<String, Object>> objects, String id) { for (Map<String, Object> object : objects) if (str(object.get("id")).equals(id)) return first(object.get("technicalName"), object.get("name"), id); return id; }
    private String className(String value, String fallback) {
        String raw = value == null ? "" : value.replaceAll("([a-z0-9])([A-Z])", "$1 $2");
        StringBuilder out = new StringBuilder(); boolean cap = true;
        for (char c : raw.toCharArray()) if (Character.isLetterOrDigit(c) && c < 128) { out.append(cap ? Character.toUpperCase(c) : c); cap = false; } else cap = true;
        return out.length() == 0 ? "Generated" + fallback.substring(0, Math.min(8, fallback.length())) : out.toString();
    }
    private String uniqueLocal(Object raw, Object fallback, Set<String> used) {
        String normalized = Normalizer.normalize(str(raw), Normalizer.Form.NFKD).replaceAll("[^A-Za-z0-9_]+", "_").replaceAll("_+", "_");
        if (normalized.isEmpty()) normalized = "node_" + stableId(str(fallback)).substring(0, 8);
        if (!Character.isLetter(normalized.charAt(0)) && normalized.charAt(0) != '_') normalized = "n_" + normalized;
        if (!used.add(normalized)) { normalized += "_" + stableId(str(fallback)).substring(0, 8); used.add(normalized); }
        return normalized;
    }
    private String snake(String value) {
        String text = Normalizer.normalize(value == null ? "generated" : value, Normalizer.Form.NFKD).replaceAll("([a-z0-9])([A-Z])", "$1_$2").replaceAll("[^A-Za-z0-9]+", "_").replaceAll("_+", "_").replaceAll("^_|_$", "").toLowerCase(Locale.ROOT);
        return text.isEmpty() ? "generated_" + stableId(value).substring(0, 8) : text;
    }
    private String safeFile(String value) { return value.replaceAll("[^A-Za-z0-9_-]+", "_"); }
    private String ontologyFileName(String label, String ref) { return safeFile(snake(label) + "_" + stableId(ref).substring(0, 8)); }
    private String stableId(String value) {
        try { byte[] digest = MessageDigest.getInstance("SHA-256").digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8)); StringBuilder out = new StringBuilder(); for (int i = 0; i < 16; i++) out.append(String.format("%02x", digest[i])); return out.toString(); }
        catch (Exception e) { throw new IllegalStateException("无法生成导出 ID", e); }
    }
    private String first(Object... values) { for (Object value : values) if (value != null && !String.valueOf(value).trim().isEmpty()) return String.valueOf(value).trim(); return ""; }
    private String str(Object value) { return value == null ? "" : String.valueOf(value).trim(); }

    public static final class Artifact {
        private final String filename;
        private final String contentType;
        private final byte[] bytes;
        public Artifact(String filename, String contentType, byte[] bytes) { this.filename = filename; this.contentType = contentType; this.bytes = bytes; }
        public String getFilename() { return filename; }
        public String getContentType() { return contentType; }
        public byte[] getBytes() { return bytes; }
    }
}

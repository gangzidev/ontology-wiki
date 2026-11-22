package com.ontologywiki.query.planning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ontologywiki.ontology.service.llm.LlmClient;
import com.ontologywiki.query.catalog.QuerySemanticCatalogService;
import com.ontologywiki.query.catalog.SemanticCatalog;
import com.ontologywiki.query.policy.QueryReadinessService;
import com.ontologywiki.query.repository.QueryConfigurationRepository;
import com.ontologywiki.query.support.QueryConfigHash;
import com.ontologywiki.query.validation.QueryPlanValidator;
import org.springframework.stereotype.Service;

import java.util.*;

/** 将自然语言转为受限查询计划；模型无权生成 SQL 或新增本体定义。 */
@Service
public class QueryPlannerService {
    private final QueryContextRetriever contextRetriever = new QueryContextRetriever();
    private final QueryConfigurationRepository repository;
    private final QueryReadinessService readiness;
    private final QueryPlanValidator validator;
    private final QuerySemanticCatalogService catalog;
    private final LlmClient llm;
    private final ObjectMapper json;

    public QueryPlannerService(QueryConfigurationRepository repository, QueryReadinessService readiness,
                               QueryPlanValidator validator, QuerySemanticCatalogService catalog, LlmClient llm, ObjectMapper json) {
        this.repository = repository;
        this.readiness = readiness;
        this.validator = validator;
        this.catalog = catalog;
        this.llm = llm;
        this.json = json;
    }

    /** 只生成计划；配置未完成或语义不充分时拒绝猜测。 */
    public Map<String, Object> plan(long proposalId, String question) {
        if (question == null || question.trim().isEmpty() || question.length() > 2000) throw new IllegalArgumentException("问题不能为空且不超过2000字");
        QueryReadinessService.Readiness state = readiness.check(proposalId);
        if (!state.isEligibleForEvaluation()) throw new IllegalArgumentException("配置暂不具备查询条件：" + String.join("；", state.getReasons()));
        Map<String, Object> proposal = repository.findProposal(proposalId);
        Map<String, Object> config = readiness.proposal(proposalId);
        QuerySemanticCatalogService.Resolution resolution = catalog.resolve(config, question);
        if (resolution.needsRetrieval()) resolution = retrieveSemantics(config, question, resolution);
        if (resolution.needsClarification()) return Collections.<String, Object>singletonMap("clarification", resolution.getClarification());
        if (resolution.isDetailQuery()) {
            Map<String, Object> plan = detailPlan(config, question, resolution);
            if (plan.containsKey("clarification")) return plan;
            validator.validate(plan, config);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("plan", plan);
            result.put("proposalId", proposalId);
            result.put("proposalStatus", proposal.get("status"));
            result.put("semanticEvidence", resolution.getEvidence());
            result.put("configHash", QueryConfigHash.of(json, config));
            return result;
        }
        // shortcut: 仅覆盖目录词 + 基础统计措辞，出现过滤或时间条件时继续走模型解析。
        if (isSimpleAggregationQuestion(config, question, resolution)) {
            Map<String, Object> plan = new LinkedHashMap<>();
            plan.put("metrics", ids(resolution.getMetricIds()));
            plan.put("dimensions", ids(resolution.getDimensionIds()));
            plan.put("filters", resolution.getDefaultFilters());
            validator.validate(plan, config);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("plan", plan);
            result.put("proposalId", proposalId);
            result.put("proposalStatus", proposal.get("status"));
            result.put("semanticEvidence", resolution.getEvidence());
            result.put("configHash", QueryConfigHash.of(json, config));
            return result;
        }
        Map<String, Object> promptContext = contextRetriever.context(config, question, resolution);
        Map<?, ?> semantics = mergedSemantics(config);
        promptContext.put("metrics", select(rows(semantics, "metrics"), resolution.getMetricIds()));
        promptContext.put("dimensions", select(rows(semantics, "dimensions"), resolution.getDimensionIds()));
        promptContext.put("semanticEvidence", resolution.getEvidence());
        promptContext.put("defaultFilters", resolution.getDefaultFilters());
        String prompt = "你是查询条件解析器。指标和维度已经由本体语义目录确定，禁止选择、替换或新增指标和维度。"
                + "用户问题中的补充说明是对前文的回答；仅指定业务对象的短语（例如分析任务的）用于消除指标歧义，不能因其不是完整句子再次追问。结合原问题保留过滤和时间要求。"
                + "只解析用户明确表达的过滤条件、日期范围、日期粒度、排序、返回条数、同比/环比类型；属性必须来自配置。不得编造字段，不得输出SQL。"
                + "若用户请求的统计方式与指标口径不符，或要求分组但所选维度不足，必须追问，不能忽略这些要求。"
                + "根据字段说明把业务条件映射为真实编码，例如说明为0是1否时，是必须映射为0；没有编码依据必须追问。当前日期：" + java.time.LocalDate.now() + "。"
                + "严格只返回JSON：{\"filters\":[{\"propertyId\":\"现有属性ID\",\"operator\":\"=\",\"value\":\"用户条件值\"}],\"timeDimension\":\"现有时间维度ID\",\"timeRange\":{\"from\":\"YYYY-MM-DD\",\"to\":\"YYYY-MM-DD\"},\"timeGranularity\":\"MONTH\",\"orderBy\":[{\"field\":\"当前指标或维度ID\",\"direction\":\"DESC\"}],\"comparison\":{\"type\":\"YOY|MOM|QOQ|WOW\",\"periods\":1},\"limit\":100}。没有明确表达的字段省略；时间粒度只能 DAY/WEEK/MONTH/QUARTER/YEAR。时间维度必须选自配置候选。"
                + "无法准确映射过滤条件时返回 {\"clarification\":\"需要向用户确认的问题\"}。本体配置："
                + write(promptContext) + "\n用户问题：" + question.trim();
        JsonNode node = llm.parseJson(llm.chat(prompt, 0));
        if (node == null || !node.isObject()) throw new IllegalStateException("模型没有返回有效查询计划");
        if (node.path("clarification").isTextual() && !node.path("clarification").asText().trim().isEmpty()) {
            return Collections.<String, Object>singletonMap("clarification", node.path("clarification").asText());
        }
        Map<String, Object> plan = json.convertValue(node, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
        for (String key : plan.keySet()) if (!Arrays.asList("filters", "timeDimension", "timeRange", "timeGranularity", "orderBy", "limit", "comparison").contains(key)) throw new IllegalArgumentException("条件解析结果包含不允许字段：" + key);
        contextRetriever.validateReferences(plan, promptContext);
        plan.put("metrics", ids(resolution.getMetricIds()));
        plan.put("dimensions", ids(resolution.getDimensionIds()));
        List<Map<String, Object>> filters = new ArrayList<>(resolution.getDefaultFilters());
        filters.addAll(rows(plan.get("filters")));
        plan.put("filters", filters);
        validator.validate(plan, config);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("plan", plan);
        result.put("proposalId", proposalId);
        result.put("proposalStatus", proposal.get("status"));
        result.put("semanticEvidence", resolution.getEvidence());
        result.put("configHash", QueryConfigHash.of(json, config));
        return result;
    }

    private Map<String, Object> detailPlan(Map<String, Object> config, String question, QuerySemanticCatalogService.Resolution resolution) {
        Map<String, Object> plan = new LinkedHashMap<>();
        if (!catalog.isSimpleDetailQuestion(config, question, resolution.getDetailObjectId())) {
            Map<String, Object> context = contextRetriever.context(config, question, resolution);
            String prompt = "用户正在查看表数据明细，数据对象已经确定为 " + resolution.getDetailObjectId()
                    + "。不要再追问统计数量还是查看具体信息。结合补充说明解析字段、过滤、排序、时间和条数，不能忽略用户条件。"
                    + "只返回JSON：{\"fields\":[{\"id\":\"属性ID\"}],\"filters\":[{\"propertyId\":\"属性ID\",\"operator\":\"=\",\"value\":\"条件值\"}],"
                    + "\"orderBy\":[{\"field\":\"属性ID\",\"direction\":\"DESC\"}],\"limit\":100,\"offset\":0,"
                    + "\"timeDimension\":\"时间维度ID\",\"timeRange\":{\"from\":\"YYYY-MM-DD\",\"to\":\"YYYY-MM-DD\"}}。"
                    + "未指定字段时省略fields表示全部字段；未指定条件时省略。limit为1至1000，每页默认100条，offset为非负整数。"
                    + "过滤支持 = != > >= < <= LIKE IS_NULL IS_NOT_NULL；所有条件取AND，OR等无法表达的条件必须澄清。"
                    + "只能引用候选属性；排序可以引用未展示字段。枚举必须依据字段说明映射实际值，禁止猜测。"
                    + "不能表示的请求或无依据的编码返回{\"clarification\":\"说明具体缺少的信息\"}，禁止输出SQL或改为统计。"
                    + "当前日期：" + java.time.LocalDate.now() + "。候选：" + write(context) + "\n用户问题：" + question;
            JsonNode node = llm.parseJson(llm.chat(prompt, 0));
            if (node == null || !node.isObject()) throw new IllegalStateException("模型没有返回有效的明细查询计划");
            if (node.path("clarification").isTextual() && !node.path("clarification").asText().trim().isEmpty())
                return Collections.singletonMap("clarification", node.path("clarification").asText());
            plan = json.convertValue(node, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
            for (String key : plan.keySet()) if (!Arrays.asList("fields", "filters", "orderBy", "limit", "offset", "timeDimension", "timeRange").contains(key))
                throw new IllegalArgumentException("明细解析包含不允许的字段：" + key);
            contextRetriever.validateReferences(plan, context);
            Set<String> available = index(rows(context.get("properties"))).keySet();
            for (Map<String, Object> field : rows(plan.get("fields"))) if (!available.contains(String.valueOf(field.get("id"))))
                throw new IllegalArgumentException("展示字段不在本次查询范围内");
            for (Map<String, Object> order : rows(plan.get("orderBy"))) if (!available.contains(String.valueOf(order.get("field"))))
                throw new IllegalArgumentException("排序字段不在本次查询范围内");
        }
        plan.put("detailObjectId", resolution.getDetailObjectId());
        plan.putIfAbsent("limit", 100);
        return plan;
    }

    private QuerySemanticCatalogService.Resolution retrieveSemantics(Map<String, Object> config, String question,
                                                                     QuerySemanticCatalogService.Resolution fallback) {
        Map<String, Object> candidates = contextRetriever.candidates(config, question);
        List<Map<String, Object>> metrics = rows(candidates.get("metrics"));
        metrics.removeIf(metric -> !catalog.isBusinessMetric(metric, config));
        candidates.put("metrics", metrics);
        if (metrics.isEmpty()) return fallback;
        String prompt = "根据用户业务问题，从下列已审核的候选中选择一个统计指标，以及用户明确要求分组时的一个维度。"
                + "指标口径、聚合方式和默认条件不能修改。记录数量必须使用COUNT指标，不能对是否标志或状态编码求和。"
                + "只返回JSON {\"metricId\":\"候选指标ID\",\"dimensionId\":\"候选维度ID，可省略\"}。"
                + "存在多种业务解释、没有相符口径或问题不属于这些候选时，返回{\"clarification\":\"简短的业务澄清问题\"}。禁止输出SQL或编造ID。"
                + "候选：" + write(candidates) + "\n用户问题：" + question;
        JsonNode node = llm.parseJson(llm.chat(prompt, 0));
        if (node == null || !node.isObject()) throw new IllegalStateException("模型没有返回有效的业务指标选择");
        if (node.path("clarification").isTextual() && !node.path("clarification").asText().trim().isEmpty()) return QuerySemanticCatalogService.Resolution.clarification(node.path("clarification").asText());
        String metricId = node.path("metricId").asText("");
        String dimensionId = node.path("dimensionId").asText("");
        if (select(metrics, Collections.singletonList(metricId)).isEmpty()
                || (!dimensionId.isEmpty() && select(rows(candidates.get("dimensions")), Collections.singletonList(dimensionId)).isEmpty())) {
            throw new IllegalArgumentException("业务解析选择了候选范围外的指标或维度");
        }
        return catalog.select(config, metricId, dimensionId);
    }

    boolean isSimpleAggregationQuestion(Map<String, Object> config, String question,
                                        QuerySemanticCatalogService.Resolution resolution) {
        String remaining = QuerySemanticCatalogService.normalize(question);
        Map<?, ?> semantics = mergedSemantics(config);
        List<String> aliases = new ArrayList<>();
        for (String category : Arrays.asList("metrics", "dimensions")) {
            Set<String> selected = new HashSet<>("metrics".equals(category)
                    ? resolution.getMetricIds() : resolution.getDimensionIds());
            for (Map<String, Object> definition : rows(semantics, category)) {
                if (selected.contains(String.valueOf(definition.get("id")))) aliases.addAll(aliases(definition));
            }
            for (Map<String, Object> term : rows(semantics, "terms")) {
                Map<?, ?> mapsTo = term.get("mapsTo") instanceof Map ? (Map<?, ?>) term.get("mapsTo") : Collections.emptyMap();
                if (selected.contains(String.valueOf(mapsTo.get(("metrics".equals(category) ? "metric" : "dimension") + "Id")))) aliases.addAll(aliases(term));
            }
        }
        // A business-object choice supplied in a clarification is part of the resolved
        // query, even though object names are not themselves metric/dimension aliases.
        // Strip the selected metric's object names before deciding whether an LLM is
        // needed for additional filters or time expressions.
        Map<String, Map<String, Object>> properties = index(rows(config.get("properties")));
        Map<String, Map<String, Object>> objects = index(rows(config.get("objects")));
        for (Map<String, Object> metric : rows(semantics.get("metrics"))) {
            if (!resolution.getMetricIds().contains(String.valueOf(metric.get("id")))) continue;
            Map<String, Object> property = properties.get(String.valueOf(metric.get("propertyId")));
            Map<String, Object> object = property == null ? null : objects.get(String.valueOf(property.get("objectId")));
            if (object != null) for (String field : Arrays.asList("name", "table")) {
                Object alias = object.get(field);
                if (alias != null) aliases.add(QuerySemanticCatalogService.normalize(String.valueOf(alias)));
            }
        }
        aliases.sort((left, right) -> Integer.compare(right.length(), left.length()));
        for (String alias : aliases) remaining = remaining.replace(QuerySemanticCatalogService.normalize(alias), "");
        for (String word : Arrays.asList("按照", "按", "统计", "计算", "查询", "查看", "展示", "分析", "总共", "总计", "总", "合计", "多少", "分别", "的", "任务", "补充说明")) {
            remaining = remaining.replace(word, "");
        }
        return remaining.isEmpty();
    }

    private List<String> aliases(Map<String, Object> definition) {
        List<String> result = new ArrayList<>();
        for (String field : Arrays.asList("name", "label", "term")) {
            Object value = definition.get(field);
            if (value != null && !String.valueOf(value).trim().isEmpty()) result.add(QuerySemanticCatalogService.normalize(String.valueOf(value)));
        }
        Object synonyms = definition.get("synonyms");
        if (synonyms instanceof List) for (Object value : (List<?>) synonyms) {
            if (value != null && !String.valueOf(value).trim().isEmpty()) result.add(QuerySemanticCatalogService.normalize(String.valueOf(value)));
        }
        return result;
    }

    private String write(Object value) { try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException("无法组织本体查询上下文", e); } }
    private Map<String, Object> mergedSemantics(Map<String, Object> config) { return SemanticCatalog.from(config); }
    private List<Map<String, Object>> rows(Object value) { List<Map<String, Object>> result = new ArrayList<>(); if (value instanceof List) for (Object row : (List<?>) value) if (row instanceof Map) result.add((Map<String, Object>) row); return result; }
    private List<Map<String, Object>> rows(Map<?, ?> parent, String key) { return parent == null ? Collections.<Map<String, Object>>emptyList() : rows(parent.get(key)); }
    private Map<String, Map<String, Object>> index(List<Map<String, Object>> rows) { Map<String, Map<String, Object>> result = new LinkedHashMap<>(); for (Map<String, Object> row : rows) result.put(String.valueOf(row.get("id")), row); return result; }
    private List<Map<String, Object>> select(List<Map<String, Object>> rows, List<String> ids) { List<Map<String, Object>> result = new ArrayList<>(); for (Map<String, Object> row : rows) if (ids.contains(String.valueOf(row.get("id")))) result.add(row); return result; }
    private List<Map<String, Object>> ids(List<String> ids) { List<Map<String, Object>> result = new ArrayList<>(); for (String id : ids) { Map<String, Object> row = new LinkedHashMap<>(); row.put("id", id); result.add(row); } return result; }
}

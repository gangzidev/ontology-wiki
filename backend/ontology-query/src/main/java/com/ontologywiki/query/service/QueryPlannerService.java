package com.ontologywiki.query.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ontologywiki.ontology.service.llm.LlmClient;
import com.ontologywiki.query.repository.QueryConfigurationRepository;
import org.springframework.stereotype.Service;

import java.util.*;

/** 将自然语言转为受限查询计划；模型无权生成 SQL 或新增本体定义。 */
@Service
public class QueryPlannerService {
    private static final int MAX_PROPERTIES = 300;
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
        if (resolution.needsClarification()) return Collections.<String, Object>singletonMap("clarification", resolution.getClarification());
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
        List<Map<String, Object>> properties = rows(config.get("properties"));
        if (properties.size() > MAX_PROPERTIES) throw new IllegalArgumentException("当前配置字段过多，请先限定查询对象范围");
        Map<String, Object> promptContext = new LinkedHashMap<>();
        Map<?, ?> semantics = mergedSemantics(config);
        promptContext.put("metrics", select(rows(semantics, "metrics"), resolution.getMetricIds()));
        promptContext.put("dimensions", select(rows(semantics, "dimensions"), resolution.getDimensionIds()));
        promptContext.put("timeDimensions", rows(semantics, "timeDimensions"));
        promptContext.put("properties", properties);
        List<Map<String, Object>> relations = rows(config.get("relations"));
        if (relations.isEmpty()) relations = rows(semantics, "relations");
        promptContext.put("relations", relations);
        promptContext.put("semanticEvidence", resolution.getEvidence());
        promptContext.put("defaultFilters", resolution.getDefaultFilters());
        String prompt = "你是查询条件解析器。指标和维度已经由本体语义目录确定，禁止选择、替换或新增指标和维度。"
                + "只解析用户明确表达的过滤条件、日期范围、日期粒度、排序、返回条数、同比/环比类型；属性必须来自配置。不得编造字段，不得输出SQL。"
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
                if (selected.contains(String.valueOf(mapsTo.get(category + "Id")))) aliases.addAll(aliases(term));
            }
        }
        aliases.sort((left, right) -> Integer.compare(right.length(), left.length()));
        for (String alias : aliases) remaining = remaining.replace(QuerySemanticCatalogService.normalize(alias), "");
        for (String word : Arrays.asList("按照", "按", "统计", "计算", "查询", "查看", "展示", "分析", "总共", "总计", "总", "合计", "多少", "分别", "的")) {
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
    private Map<String, Object> mergedSemantics(Map<String, Object> config) {
        Map<String, Object> result = new LinkedHashMap<>();
        Object legacy = config.get("querySemantics");
        Object catalog = config.get("semanticCatalog");
        if (legacy instanceof Map) for (Map.Entry<?, ?> entry : ((Map<?, ?>) legacy).entrySet()) result.put(String.valueOf(entry.getKey()), entry.getValue());
        if (catalog instanceof Map) for (Map.Entry<?, ?> entry : ((Map<?, ?>) catalog).entrySet()) result.put(String.valueOf(entry.getKey()), entry.getValue());
        return result;
    }
    private List<Map<String, Object>> rows(Object value) { List<Map<String, Object>> result = new ArrayList<>(); if (value instanceof List) for (Object row : (List<?>) value) if (row instanceof Map) result.add((Map<String, Object>) row); return result; }
    private List<Map<String, Object>> rows(Map<?, ?> parent, String key) { return parent == null ? Collections.<Map<String, Object>>emptyList() : rows(parent.get(key)); }
    private List<Map<String, Object>> select(List<Map<String, Object>> rows, List<String> ids) { List<Map<String, Object>> result = new ArrayList<>(); for (Map<String, Object> row : rows) if (ids.contains(String.valueOf(row.get("id")))) result.add(row); return result; }
    private List<Map<String, Object>> ids(List<String> ids) { List<Map<String, Object>> result = new ArrayList<>(); for (String id : ids) { Map<String, Object> row = new LinkedHashMap<>(); row.put("id", id); result.add(row); } return result; }
}

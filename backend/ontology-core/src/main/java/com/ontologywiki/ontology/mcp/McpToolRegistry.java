package com.ontologywiki.ontology.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ontologywiki.ontology.mapper.McpMapper;
import com.ontologywiki.ontology.service.LlmWikiClient;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * MCP 工具注册表。
 *
 * <p>负责两件事：
 * <ul>
 *   <li>返回 {@code tools/list} 的工具描述（JSON Schema）；</li>
 *   <li>把 {@code tools/call} 请求分发到具体工具实现。</li>
 * </ul>
 *
 * <p>SQL 已全部下沉到 {@link McpMapper} 与对应 XML，本类只保留参数校验、结果整形与响应包装。
 */
@Component
public class McpToolRegistry {

    /** 资产/空间启用状态值 */
    private static final String STATUS_ENABLED = "0";

    /** 工具返回条数上限 */
    private static final int SEARCH_LIMIT = 20;
    private static final int TASK_LIST_LIMIT = 50;

    /** 候选摘要中最多返回的示例条数 */
    private static final int EXAMPLE_LIMIT = 5;

    /** 候选摘要涉及的配置分类 */
    private static final List<String> CANDIDATE_CATEGORIES = Arrays.asList(
            "objects", "properties", "relations", "logics", "actions", "terms", "warnings");

    /** 允许筛选的分析任务状态 */
    private static final List<String> ALLOWED_TASK_STATUS = Arrays.asList(
            "DRAFT", "RUNNING", "DONE", "FAILED", "READY");

    private final McpMapper mapper;

    public McpToolRegistry(McpMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 返回所有工具的描述（JSON Schema）。
     *
     * @return tools/list 响应体
     */
    public Map<String, Object> toolsList() {
        List<Map<String,Object>> tools = new ArrayList<>();
        tools.add(buildTool("wiki_query",
                "在本体 Wiki 知识库中进行问答，返回 AI 回答和引用来源",
                props("question", "string", "要提问的内容，支持自然语言",
                      "spaceId",  "number", "（可选）限定在某个行业知识空间内搜索")));
        tools.add(buildTool("search_wiki",
                "搜索 Wiki 资产（资料/页面），按关键词返回匹配条目",
                props("query",   "string", "搜索关键词",
                      "spaceId", "number", "（可选）限定在某个行业知识空间内")));
        tools.add(buildTool("list_wiki_spaces",
                "列出所有行业知识空间",
                Collections.emptyMap()));
        tools.add(buildTool("list_analysis_tasks",
                "列出本体分析任务，可按状态筛选",
                props("status", "string", "（可选）DRAFT/RUNNING/DONE/FAILED")));
        tools.add(buildTool("get_analysis_status",
                "查询分析任务的当前状态和进度",
                requiredProps("taskId", "number", "分析任务 ID")));
        tools.add(buildTool("get_analysis_candidates",
                "获取分析任务的候选结构摘要（对象、属性、关系等）",
                requiredProps("taskId", "number", "分析任务 ID")));
        tools.add(buildTool("run_analysis",
                "触发分析任务（异步执行，立即返回；用 get_analysis_status 轮询结果）",
                requiredProps("taskId", "number", "分析任务 ID")));
        tools.add(buildTool("chat_analysis",
                "与分析结果进行 AI 对话（评审候选、提问、请求修改）",
                props("taskId",   "number", "分析任务 ID",
                      "question", "string", "要提问或请求的内容")));
        tools.add(buildTool("save_analysis_result",
                "确认并沉淀分析结果到知识库",
                props("taskId",     "number", "分析任务 ID",
                      "proposalId", "number", "（可选）配置草案 ID",
                      "answer",     "string", "分析说明文字")));
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("tools", tools);
        return result;
    }

    /**
     * 分发 tools/call 请求，并把结果包装成 MCP content 格式。
     *
     * @param params    JSON-RPC 的 params，含 name 与 arguments
     * @param llmWiki   问答与分析知识沉淀入口
     * @param json      JSON 序列化器
     * @return MCP 响应结果
     */
    @SuppressWarnings("unchecked")
    public Map<String,Object> dispatch(Map<String,Object> params, LlmWikiClient llmWiki, ObjectMapper json) {
        String name = params.get("name") == null ? "" : String.valueOf(params.get("name"));
        Map<String,Object> args = params.get("arguments") instanceof Map
                ? (Map<String,Object>) params.get("arguments") : Collections.emptyMap();

        Object toolResult;
        switch (name) {
            case "wiki_query":           toolResult = wikiQuery(args, llmWiki); break;
            case "search_wiki":          toolResult = searchWiki(args); break;
            case "list_wiki_spaces":     toolResult = listWikiSpaces(); break;
            case "list_analysis_tasks":  toolResult = listAnalysisTasks(args); break;
            case "get_analysis_status":  toolResult = getAnalysisStatus(args); break;
            case "get_analysis_candidates": toolResult = getAnalysisCandidates(args, json); break;
            case "run_analysis":         toolResult = runAnalysis(args); break;
            case "chat_analysis":        toolResult = chatAnalysis(args, llmWiki); break;
            case "save_analysis_result": toolResult = saveAnalysisResult(args, llmWiki); break;
            default: throw new McpToolException("Unknown tool: " + name);
        }

        return wrapAsContent(toolResult, json);
    }

    // ─── 工具实现 ─────────────────────────────────────────────────────────────

    /** 知识库问答：返回答案与引用来源。 */
    private Object wikiQuery(Map<String,Object> args, LlmWikiClient llmWiki) {
        String question = requireString(args, "question");
        Long spaceId = readOptionalLong(args, "spaceId");
        Map<String,Object> resp = llmWiki.queryResult(question, false, spaceId,
                Collections.emptyList(), spaceId == null ? "ALL" : "SPACE", spaceId);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("answer",       resp.get("answer"));
        result.put("citedPages",   resp.get("citedPages"));
        result.put("citedSources", resp.get("citedSources"));
        return result;
    }

    /** 资产检索：有空间则限定空间，否则全库检索。 */
    private Object searchWiki(Map<String,Object> args) {
        String query = requireString(args, "query");
        Long spaceId = readOptionalLong(args, "spaceId");
        String like = "%" + query.replace("%","\\%").replace("_","\\_") + "%";
        if (spaceId != null) {
            return mapper.searchAssetsInSpace(spaceId, STATUS_ENABLED, like, SEARCH_LIMIT);
        }
        return mapper.searchAssets(STATUS_ENABLED, like, SEARCH_LIMIT);
    }

    /** 列出启用状态的行业知识空间。 */
    private Object listWikiSpaces() {
        return mapper.listSpaces(STATUS_ENABLED);
    }

    /** 列出分析任务，可按状态筛选。 */
    private Object listAnalysisTasks(Map<String,Object> args) {
        String status = args.get("status") instanceof String
                ? String.valueOf(args.get("status")).toUpperCase(Locale.ROOT) : null;
        if (status != null && !ALLOWED_TASK_STATUS.contains(status)) {
            throw new McpToolException("status 无效，可选值：DRAFT/RUNNING/DONE/FAILED");
        }
        if (status != null) {
            return mapper.listTasksByStatus(status, TASK_LIST_LIMIT);
        }
        return mapper.listTasks(TASK_LIST_LIMIT);
    }

    /** 查询单个任务的状态。 */
    private Object getAnalysisStatus(Map<String,Object> args) {
        long taskId = requireLong(args, "taskId");
        List<Map<String,Object>> rows = mapper.selectTaskStatus(taskId);
        if (rows.isEmpty()) {
            throw new McpToolException("分析任务不存在：" + taskId);
        }
        return rows.get(0);
    }

    /** 读取最新草案并汇总候选结构，只返回各类数量与少量示例。 */
    private Object getAnalysisCandidates(Map<String,Object> args, ObjectMapper json) {
        long taskId = requireLong(args, "taskId");
        List<Map<String,Object>> rows = mapper.selectLatestProposalConfig(taskId);
        if (rows.isEmpty()) {
            throw new McpToolException("该任务暂无配置草案，请先执行分析");
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String,Object> config = json.readValue(String.valueOf(rows.get(0).get("config_json")), Map.class);
            Map<String,Object> summary = new LinkedHashMap<>();
            for (String key : CANDIDATE_CATEGORIES) {
                Object val = config.get(key);
                if (val instanceof List) {
                    summary.put(key + "_count", ((List<?>) val).size());
                    summary.put(key + "_examples", firstItems((List<?>) val, EXAMPLE_LIMIT));
                }
            }
            summary.put("llmAnswer", config.get("llmAnswer"));
            return summary;
        } catch (Exception e) {
            throw new McpToolException("草案内容解析失败");
        }
    }

    /**
     * 触发分析的提示。
     *
     * <p>MCP Token 不具备写权限，这里只做前置校验并返回操作指引，
     * 实际执行仍由 Web 端携带 JWT 调用分析接口完成。
     */
    private Object runAnalysis(Map<String,Object> args) {
        long taskId = requireLong(args, "taskId");
        List<Map<String,Object>> rows = mapper.selectTaskIdAndStatus(taskId);
        if (rows.isEmpty()) {
            throw new McpToolException("分析任务不存在：" + taskId);
        }
        if ("RUNNING".equals(rows.get(0).get("status"))) {
            throw new McpToolException("该任务正在执行中");
        }
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("taskId", taskId);
        result.put("taskName", "");
        result.put("message", "请通过 POST /ontology/analyses/" + taskId
                + "/analyze 并携带 JWT Token 触发分析（MCP Token 不支持写操作触发，需要 Web 登录凭证）");
        result.put("hint", "分析完成后可用 get_analysis_status 轮询结果");
        return result;
    }

    /** 与分析结果对话。 */
    private Object chatAnalysis(Map<String,Object> args, LlmWikiClient llmWiki) {
        long taskId = requireLong(args, "taskId");
        String question = requireString(args, "question");
        Map<String,Object> resp = llmWiki.queryResult(question, false, null,
                Collections.emptyList(), "ANALYSIS", taskId);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("answer",          resp.get("answer"));
        result.put("analysisChanges", resp.get("analysisChanges"));
        return result;
    }

    /** 确认并沉淀分析结果。 */
    private Object saveAnalysisResult(Map<String,Object> args, LlmWikiClient llmWiki) {
        long taskId = requireLong(args, "taskId");
        String answer = requireString(args, "answer");
        Long proposalId = readOptionalLong(args, "proposalId");
        Map<String,Object> memory = llmWiki.saveAnalysisAnswer(taskId, proposalId, answer, true, "mcp");
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("saved", true);
        result.put("memoryId", memory.get("memory_id"));
        result.put("version",  memory.get("version"));
        return result;
    }

    // ─── 响应包装 ─────────────────────────────────────────────────────────────

    /** 把工具结果序列化为文本，并包装成 MCP content 结构。 */
    private Map<String,Object> wrapAsContent(Object toolResult, ObjectMapper json) {
        String text;
        try {
            text = json.writerWithDefaultPrettyPrinter().writeValueAsString(toolResult);
        } catch (Exception e) {
            text = String.valueOf(toolResult);
        }

        Map<String,Object> contentItem = new LinkedHashMap<>();
        contentItem.put("type", "text");
        contentItem.put("text", text);

        Map<String,Object> result = new LinkedHashMap<>();
        result.put("content", Collections.singletonList(contentItem));
        return result;
    }

    // ─── Schema 构建工具方法 ───────────────────────────────────────────────────

    /** 构建单个工具的 JSON Schema 描述。 */
    private Map<String,Object> buildTool(String name, String description, Map<String,Object> properties) {
        Map<String,Object> inputSchema = new LinkedHashMap<>();
        inputSchema.put("type", "object");
        inputSchema.put("properties", properties);
        Map<String,Object> tool = new LinkedHashMap<>();
        tool.put("name", name);
        tool.put("description", description);
        tool.put("inputSchema", inputSchema);
        return tool;
    }

    /** 构建属性 Map：name, type, description 三元组列表。 */
    private Map<String,Object> props(String... triplets) {
        Map<String,Object> properties = new LinkedHashMap<>();
        for (int i = 0; i + 2 < triplets.length; i += 3) {
            Map<String,Object> prop = new LinkedHashMap<>();
            prop.put("type", triplets[i + 1]);
            prop.put("description", triplets[i + 2]);
            properties.put(triplets[i], prop);
        }
        return properties;
    }

    /** 仅含单个必填属性的 Schema。 */
    private Map<String,Object> requiredProps(String name, String type, String description) {
        return props(name, type, description);
    }

    // ─── 参数校验 ─────────────────────────────────────────────────────────────

    /** 取必填字符串参数，缺失或空白时报错。 */
    private String requireString(Map<String,Object> args, String key) {
        Object v = args.get(key);
        if (v == null || String.valueOf(v).trim().isEmpty()) {
            throw new McpToolException("参数缺失：" + key);
        }
        return String.valueOf(v).trim();
    }

    /** 取必填数字参数。 */
    private long requireLong(Map<String,Object> args, String key) {
        Object v = args.get(key);
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (Exception e) {
            throw new McpToolException("参数无效：" + key + " 必须是数字");
        }
    }

    /** 取可选数字参数，非数字时返回 null。 */
    private Long readOptionalLong(Map<String,Object> args, String key) {
        Object v = args.get(key);
        return v instanceof Number ? ((Number) v).longValue() : null;
    }

    /** 截取列表前 n 条，避免响应体过大。 */
    private List<Object> firstItems(List<?> source, int limit) {
        int size = Math.min(limit, source.size());
        List<Object> items = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            items.add(source.get(i));
        }
        return items;
    }
}

package com.ontologywiki.ontology.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ontologywiki.common.annotation.Anonymous;
import com.ontologywiki.ontology.mapper.McpMapper;
import com.ontologywiki.ontology.service.LlmWikiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * MCP over HTTP (Streamable HTTP / JSON-RPC 2.0)
 *
 * <p>端点：POST /mcp（接收 JSON-RPC 请求）
 * <p>认证：使用专用 MCP Long-lived Token（在"模型配置"页面生成，SHA-256 存储，不存明文）。
 * <p>使用 @Anonymous 跳过 JwtAuthenticationTokenFilter，在方法内自行鉴权。
 */
@RestController
@Anonymous
public class McpController {

    private static final Logger log = LoggerFactory.getLogger(McpController.class);

    private static final String MCP_VERSION = "2024-11-05";

    /** 令牌启用状态 */
    private static final int TOKEN_ENABLED = 1;

    private final McpMapper mapper;
    private final LlmWikiClient llmWiki;
    private final McpToolRegistry toolRegistry;
    private final ObjectMapper json = new ObjectMapper();

    public McpController(McpMapper mapper, LlmWikiClient llmWiki, McpToolRegistry toolRegistry) {
        this.mapper = mapper;
        this.llmWiki = llmWiki;
        this.toolRegistry = toolRegistry;
    }

    @PostMapping("/mcp")
    public Map<String, Object> handle(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        // 1. 鉴权（MCP 专用 Token）
        String authError = authenticate(req);
        if (authError != null) return errorResponse(null, -32001, authError);

        // 2. 解析 JSON-RPC
        Object id      = body.get("id");
        String method  = body.get("method") == null ? "" : String.valueOf(body.get("method"));
        @SuppressWarnings("unchecked")
        Map<String,Object> params = body.get("params") instanceof Map
                ? (Map<String,Object>) body.get("params") : Collections.emptyMap();

        try {
            switch (method) {
                case "initialize":
                    return successResponse(id, handleInitialize());
                case "tools/list":
                    return successResponse(id, toolRegistry.toolsList());
                case "tools/call":
                    return successResponse(id, toolRegistry.dispatch(params, llmWiki, json));
                case "ping":
                    return successResponse(id, Collections.emptyMap());
                default:
                    return errorResponse(id, -32601, "Method not found: " + method);
            }
        } catch (McpToolException e) {
            return errorResponse(id, -32000, e.getMessage());
        } catch (Exception e) {
            log.error("MCP 处理异常 method={}", method, e);
            return errorResponse(id, -32000, "Internal error");
        }
    }

    // ─── initialize ──────────────────────────────────────────────────────────

    /** 返回协议版本、能力声明与服务信息。 */
    private Map<String, Object> handleInitialize() {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", MCP_VERSION);
        Map<String,Object> capabilities = new LinkedHashMap<>();
        Map<String,Object> tools = new LinkedHashMap<>(); tools.put("listChanged", false);
        capabilities.put("tools", tools);
        result.put("capabilities", capabilities);
        Map<String,Object> serverInfo = new LinkedHashMap<>();
        serverInfo.put("name", "ontology-wiki");
        serverInfo.put("version", "1.0.0");
        result.put("serverInfo", serverInfo);
        return result;
    }

    // ─── 认证 ─────────────────────────────────────────────────────────────────

    /**
     * 验证 MCP 专用 Token。
     *
     * @param req HTTP 请求
     * @return 通过返回 null，失败返回错误原因
     */
    private String authenticate(HttpServletRequest req) {
        String header = req.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) return "Missing Authorization header";
        String token = header.substring(7).trim();
        if (token.isEmpty()) return "Empty token";

        try {
            String hash = sha256Hex(token);
            List<Map<String,Object>> rows = mapper.selectEnabledToken(hash, TOKEN_ENABLED);
            if (!rows.isEmpty()) {
                long tokenId = ((Number) rows.get(0).get("token_id")).longValue();
                // 刷新最近使用时间，失败不影响本次鉴权
                mapper.touchToken(tokenId);
                return null;
            }
        } catch (Exception e) {
            log.warn("MCP Token 校验异常", e);
        }
        return "Invalid or expired token";
    }

    /** 计算令牌的 SHA-256 十六进制摘要，与库中存储值比对。 */
    private String sha256Hex(String token) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return bytesToHex(md.digest(token.getBytes(StandardCharsets.UTF_8)));
    }

    // ─── 工具方法 ─────────────────────────────────────────────────────────────

    private Map<String,Object> successResponse(Object id, Object result) {
        Map<String,Object> resp = new LinkedHashMap<>();
        resp.put("jsonrpc", "2.0");
        resp.put("id", id);
        resp.put("result", result);
        return resp;
    }

    private Map<String,Object> errorResponse(Object id, int code, String message) {
        Map<String,Object> error = new LinkedHashMap<>(); error.put("code", code); error.put("message", message);
        Map<String,Object> resp = new LinkedHashMap<>();
        resp.put("jsonrpc", "2.0");
        resp.put("id", id);
        resp.put("error", error);
        return resp;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}

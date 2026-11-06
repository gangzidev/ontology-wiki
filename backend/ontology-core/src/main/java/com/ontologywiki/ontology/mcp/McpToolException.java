package com.ontologywiki.ontology.mcp;

/** MCP 工具执行业务异常，转换为 JSON-RPC error 响应。 */
public class McpToolException extends RuntimeException {
    public McpToolException(String message) { super(message); }
}

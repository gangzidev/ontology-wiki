package com.ontologywiki.ontology.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ontologywiki.ontology.config.OntologyIntegrationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LLM 模型调用客户端
 *
 * <p>职责：
 * <ul>
 *   <li>OpenAI 兼容接口调用</li>
 *   <li>响应解析与错误处理</li>
 *   <li>超时重试逻辑</li>
 * </ul>
 *
 * @author ontology-wiki
 */
@Service
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    private final OntologyIntegrationProperties properties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public LlmClient(OntologyIntegrationProperties properties,
                     RestTemplate restTemplate,
                     ObjectMapper objectMapper) {
        this.properties = properties;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 调用 Chat Completions 接口获取文本回复
     *
     * @param prompt 用户提示词
     * @return 模型回复文本
     * @throws LlmException 调用失败时抛出
     */
    public String chat(String prompt) {
        return chat(prompt, 0.1);
    }

    /**
     * 调用 Chat Completions 接口获取文本回复
     *
     * @param prompt      用户提示词
     * @param temperature 生成随机度 (0-2)
     * @return 模型回复文本
     * @throws LlmException 调用失败时抛出
     */
    public String chat(String prompt, double temperature) {
        if (prompt == null || prompt.trim().isEmpty()) {
            throw new IllegalArgumentException("提示词不能为空");
        }

        OntologyIntegrationProperties.LlmWiki config = properties.getLlmWiki();
        if (!config.isEnabled() || isBlank(config.getApiBaseUrl()) || isBlank(config.getApiKey())) {
            throw new LlmException("LLM API 未配置或未启用");
        }

        try {
            Map<String, Object> requestBody = buildChatRequest(prompt, temperature, config.getModel());
            String response = callOpenAiApi("/chat/completions", HttpMethod.POST, requestBody, config);
            return extractChatContent(response);
        } catch (RestClientException e) {
            log.error("LLM API 调用失败: {}", e.getMessage(), e);
            throw new LlmException("模型 API 调用失败: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("LLM 响应解析失败: {}", e.getMessage(), e);
            throw new LlmException("模型响应解析失败", e);
        }
    }

    /**
     * 调用 Models 接口获取可用模型列表
     *
     * @return 模型列表 JSON
     * @throws LlmException 调用失败时抛出
     */
    public String listModels() {
        OntologyIntegrationProperties.LlmWiki config = properties.getLlmWiki();
        if (!config.isEnabled() || isBlank(config.getApiBaseUrl()) || isBlank(config.getApiKey())) {
            throw new LlmException("LLM API 未配置或未启用");
        }

        try {
            return callOpenAiApi("/models", HttpMethod.GET, null, config);
        } catch (RestClientException e) {
            log.error("获取模型列表失败: {}", e.getMessage(), e);
            throw new LlmException("获取模型列表失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解析 JSON 响应（支持代码块包裹）
     *
     * @param rawResponse 原始响应文本
     * @return 解析后的 JsonNode，解析失败返回 null
     */
    public JsonNode parseJson(String rawResponse) {
        if (rawResponse == null || rawResponse.trim().isEmpty()) {
            return null;
        }

        try {
            // 去除 Markdown 代码块包裹
            String cleaned = rawResponse.trim()
                    .replaceFirst("(?s)^```(?:json)?\\s*", "")
                    .replaceFirst("\\s*```$", "");

            // 提取 JSON 对象
            int startIndex = cleaned.indexOf('{');
            int endIndex = cleaned.lastIndexOf('}');

            if (startIndex >= 0 && endIndex > startIndex) {
                cleaned = cleaned.substring(startIndex, endIndex + 1);
            }

            return objectMapper.readTree(cleaned);
        } catch (Exception e) {
            log.warn("JSON 解析失败，返回 null: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 构建 Chat Completions 请求体
     */
    private Map<String, Object> buildChatRequest(String prompt, double temperature, String model) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", prompt);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("temperature", Math.max(0.0, Math.min(2.0, temperature)));
        body.put("messages", Collections.singletonList(message));

        return body;
    }

    /**
     * 调用 OpenAI 兼容接口
     */
    private String callOpenAiApi(String path,
                                  HttpMethod method,
                                  Object requestBody,
                                  OntologyIntegrationProperties.LlmWiki config) {
        String url = joinUrl(config.getApiBaseUrl(), path);
        URI uri = URI.create(url);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(config.getApiKey().trim());

        HttpEntity<?> requestEntity = new HttpEntity<>(requestBody, headers);
        ResponseEntity<String> response = restTemplate.exchange(uri, method, requestEntity, String.class);

        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new LlmException("模型 API 返回错误状态: HTTP " + response.getStatusCodeValue());
        }

        return response.getBody() == null ? "" : response.getBody();
    }

    /**
     * 从 Chat Completions 响应中提取回复内容
     */
    private String extractChatContent(String response) throws Exception {
        JsonNode root = objectMapper.readTree(response);
        JsonNode contentNode = root.path("choices").path(0).path("message").path("content");

        if (contentNode.isMissingNode() || contentNode.isNull()) {
            throw new LlmException("模型响应中未找到有效内容");
        }

        return contentNode.asText();
    }

    /**
     * 拼接 URL 路径
     */
    private String joinUrl(String base, String path) {
        if (base == null || path == null) {
            throw new IllegalArgumentException("URL 基础路径和子路径不能为空");
        }

        String cleanBase = base.trim();
        String cleanPath = path.trim();

        if (cleanBase.endsWith("/") && cleanPath.startsWith("/")) {
            return cleanBase.substring(0, cleanBase.length() - 1) + cleanPath;
        }

        if (!cleanBase.endsWith("/") && !cleanPath.startsWith("/")) {
            return cleanBase + "/" + cleanPath;
        }

        return cleanBase + cleanPath;
    }

    /**
     * 判断字符串是否为空
     */
    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /**
     * LLM 调用异常
     */
    public static class LlmException extends RuntimeException {
        public LlmException(String message) {
            super(message);
        }

        public LlmException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

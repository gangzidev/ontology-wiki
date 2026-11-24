package com.ontologywiki.ontology.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ModelCompletionGateway {
    private static final Logger log = LoggerFactory.getLogger(ModelCompletionGateway.class);
    private final RestTemplate rest;
    private final ObjectMapper json;

    public ModelCompletionGateway(RestTemplate rest, ObjectMapper json) {
        this.rest = rest;
        this.json = json;
    }

    public String complete(String prompt, String scene, Map<String, Object> config) {
        String provider = String.valueOf(config.getOrDefault("providerType", "OPENAI_COMPAT"));
        String model = String.valueOf(config.get("model"));
        long start = System.currentTimeMillis();
        try {
            JsonNode response = exchange(prompt, 0.1, provider, model,
                    String.valueOf(config.get("apiBaseUrl")), String.valueOf(config.getOrDefault("apiKey", "")));
            JsonNode content = messageContent(response, provider);
            if (!content.isTextual() || content.asText().trim().isEmpty()) {
                throw new IllegalStateException("模型 completion 响应中没有文本内容");
            }
            JsonNode tokens = response.path("usage").get("total_tokens");
            if ("OLLAMA".equals(provider) && response.has("eval_count")) tokens = response.get("eval_count");
            log.info("LLM completion scene={} configId={} provider={} model={} source={} durationMs={} totalTokens={}",
                    scene, config.get("settingId"), provider, model, config.get("configSource"),
                    System.currentTimeMillis() - start, tokens == null ? null : tokens.asInt());
            return content.asText();
        } catch (RuntimeException failure) {
            log.warn("LLM completion failed scene={} configId={} provider={} model={} source={} durationMs={} reason={}",
                    scene, config.get("settingId"), provider, model, config.get("configSource"),
                    System.currentTimeMillis() - start, clip(failure.getMessage(), 300));
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("模型 completion 响应解析失败", failure);
        }
    }

    public Map<String, Object> testConnection(String baseUrl, String apiKey, String model, String provider,
                                               Object settingId, Object usageScene) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("providerType", provider);
        result.put("settingId", settingId);
        result.put("usageScene", usageScene);
        result.put("model", model);
        try {
            String content = requestContent("请仅回复：连接成功", 0, provider, model, baseUrl, apiKey).asText("");
            if (content.trim().isEmpty()) throw new IllegalStateException("模型 completion 请求成功，但响应中没有文本内容");
            result.put("connected", true);
            result.put("responsePreview", clip(content, 200));
        } catch (Exception failure) {
            result.put("connected", false);
            result.put("message", clip(failure.getMessage() == null ? "模型 completion 请求失败" : failure.getMessage(), 500));
        }
        return result;
    }

    JsonNode requestContent(String prompt, Number temperature, String provider, String model,
                            String baseUrl, String apiKey) throws java.io.IOException {
        return messageContent(exchange(prompt, temperature, provider, model, baseUrl, apiKey), provider);
    }

    private JsonNode exchange(String prompt, Number temperature, String provider, String model,
                              String baseUrl, String apiKey) throws java.io.IOException {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", prompt);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", Collections.singletonList(message));
        String path;
        if ("OLLAMA".equals(provider)) {
            path = "/api/chat";
            body.put("stream", false);
            body.put("options", Collections.singletonMap("temperature", temperature));
        } else {
            path = "/chat/completions";
            body.put("temperature", temperature);
            if (apiKey != null && !apiKey.trim().isEmpty()) headers.setBearerAuth(apiKey.trim());
        }
        String base = baseUrl.trim();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        ResponseEntity<String> response = rest.exchange(URI.create(base + path), HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("模型 API 返回 HTTP " + response.getStatusCodeValue());
        }
        return json.readTree(response.getBody() == null ? "{}" : response.getBody());
    }

    private JsonNode messageContent(JsonNode response, String provider) {
        JsonNode message = "OLLAMA".equals(provider) ? response : response.path("choices").path(0);
        return message.path("message").path("content");
    }

    private String clip(String value, int max) {
        return value == null ? "" : value.substring(0, Math.min(value.length(), max));
    }
}

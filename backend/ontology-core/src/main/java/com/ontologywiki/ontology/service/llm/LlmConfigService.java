package com.ontologywiki.ontology.service.llm;

import com.ontologywiki.ontology.config.OntologyIntegrationProperties;
import com.ontologywiki.ontology.service.CredentialService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM 模型配置管理服务
 *
 * <p>职责：
 * <ul>
 *   <li>模型配置的读取、保存</li>
 *   <li>配置加密存储</li>
 *   <li>配置有效性验证</li>
 * </ul>
 *
 * @author ontology-wiki
 */
@Service
public class LlmConfigService {

    private static final int SETTING_ID = 1;

    private final OntologyIntegrationProperties properties;
    private final CredentialService credentialService;
    private final JdbcTemplate jdbcTemplate;
    private final LlmClient llmClient;

    public LlmConfigService(OntologyIntegrationProperties properties,
                            CredentialService credentialService,
                            JdbcTemplate jdbcTemplate,
                            LlmClient llmClient) {
        this.properties = properties;
        this.credentialService = credentialService;
        this.jdbcTemplate = jdbcTemplate;
        this.llmClient = llmClient;
    }

    /**
     * 应用启动时加载数据库中的配置
     */
    @PostConstruct
    public void loadStoredSettings() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT enabled, api_base_url, api_key_enc, model " +
                        "FROM ontology_llm_settings WHERE setting_id = ?",
                SETTING_ID
        );

        if (rows.isEmpty()) {
            return;
        }

        Map<String, Object> row = rows.get(0);
        OntologyIntegrationProperties.LlmWiki config = properties.getLlmWiki();

        // 加载启用状态
        config.setEnabled(isTrue(row.get("enabled")));

        // 加载 API 地址
        if (row.get("api_base_url") != null) {
            config.setApiBaseUrl(String.valueOf(row.get("api_base_url")));
        }

        // 加载模型名称
        if (row.get("model") != null) {
            config.setModel(String.valueOf(row.get("model")));
        }

        // 解密并加载 API Key
        if (row.get("api_key_enc") != null) {
            String encrypted = String.valueOf(row.get("api_key_enc"));
            if (!encrypted.trim().isEmpty()) {
                config.setApiKey(credentialService.decrypt(encrypted));
            }
        }
    }

    /**
     * 获取当前模型配置（不返回敏感信息）
     *
     * @return 配置摘要
     */
    public Map<String, Object> getSettings() {
        OntologyIntegrationProperties.LlmWiki config = properties.getLlmWiki();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", config.isEnabled());
        result.put("apiBaseUrl", config.getApiBaseUrl());
        result.put("model", config.getModel());
        result.put("apiKeyConfigured", isNotBlank(config.getApiKey()));

        return result;
    }

    /**
     * 保存模型配置
     *
     * @param input    用户输入的配置
     * @param username 操作人用户名
     * @return 保存后的配置摘要
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> saveSettings(Map<String, Object> input, String username) {
        if (input == null) {
            throw new IllegalArgumentException("请填写模型配置");
        }

        // 解析输入参数
        String apiBaseUrl = getString(input, "apiBaseUrl", "").trim();
        String model = getString(input, "model", "").trim();
        String apiKey = getString(input, "apiKey", "");
        boolean enabled = getBoolean(input, "enabled", false);
        boolean clearApiKey = getBoolean(input, "clearApiKey", false);

        // 校验参数
        validateSettings(apiBaseUrl, model, apiKey, enabled);

        // 确定最终的 API Key
        String effectiveApiKey = determineApiKey(apiKey, clearApiKey);

        if (enabled && isBlank(effectiveApiKey)) {
            throw new IllegalArgumentException("启用模型前请配置 API Key");
        }

        // 持久化配置
        persistSettings(apiBaseUrl, model, effectiveApiKey, enabled, username);

        // 更新内存配置
        updateInMemoryConfig(apiBaseUrl, model, effectiveApiKey, enabled);

        return getSettings();
    }

    /**
     * 测试当前模型配置是否可用
     *
     * @return 测试结果
     */
    public Map<String, Object> testConnection() {
        OntologyIntegrationProperties.LlmWiki config = properties.getLlmWiki();

        if (!config.isEnabled() || isBlank(config.getApiBaseUrl()) || isBlank(config.getApiKey())) {
            throw new IllegalStateException("请先保存并启用模型配置");
        }

        try {
            String response = llmClient.chat("请仅回复：连接成功");

            if (isBlank(response)) {
                throw new IllegalStateException("模型 API 未返回有效内容");
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("connected", true);
            result.put("model", config.getModel());
            result.put("message", "模型 API 调用成功");
            result.put("response", response);

            return result;
        } catch (LlmClient.LlmException e) {
            throw new IllegalStateException("模型 API 调用失败: " + e.getMessage(), e);
        }
    }

    /**
     * 校验配置参数
     */
    private void validateSettings(String apiBaseUrl, String model, String apiKey, boolean enabled) {
        // 校验长度
        if (apiBaseUrl.length() > 1000) {
            throw new IllegalArgumentException("API 地址长度不能超过 1000 字符");
        }
        if (model.isEmpty() || model.length() > 200) {
            throw new IllegalArgumentException("模型名称不能为空且不超过 200 字符");
        }
        if (apiKey.length() > 4096) {
            throw new IllegalArgumentException("API Key 长度不能超过 4096 字符");
        }

        // 校验 URL 格式
        if (!apiBaseUrl.isEmpty() || enabled) {
            try {
                URI uri = URI.create(apiBaseUrl);
                if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
                    throw new IllegalArgumentException();
                }
                if (uri.getHost() == null || uri.getUserInfo() != null ||
                        uri.getQuery() != null || uri.getFragment() != null) {
                    throw new IllegalArgumentException();
                }
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "模型 API 地址必须是有效的 http 或 https 地址，不能包含账号、查询参数或片段"
                );
            }
        }
    }

    /**
     * 确定最终要使用的 API Key
     */
    private String determineApiKey(String inputApiKey, boolean clearApiKey) {
        if (clearApiKey) {
            return "";
        }

        if (!inputApiKey.trim().isEmpty()) {
            return inputApiKey.trim();
        }

        // 使用当前内存中的配置
        OntologyIntegrationProperties.LlmWiki config = properties.getLlmWiki();
        return config.getApiKey() == null ? "" : config.getApiKey();
    }

    /**
     * 持久化配置到数据库
     */
    private void persistSettings(String apiBaseUrl, String model, String apiKey,
                                  boolean enabled, String username) {
        List<Map<String, Object>> existing = jdbcTemplate.queryForList(
                "SELECT setting_id FROM ontology_llm_settings WHERE setting_id = ?",
                SETTING_ID
        );

        String encryptedKey = isBlank(apiKey) ? null : credentialService.encrypt(apiKey);

        if (existing.isEmpty()) {
            // 首次插入
            jdbcTemplate.update(
                    "INSERT INTO ontology_llm_settings (setting_id, enabled, api_base_url, api_key_enc, model, update_by, update_time) " +
                            "VALUES (?, ?, ?, ?, ?, ?, NOW())",
                    SETTING_ID, enabled, apiBaseUrl, encryptedKey, model, username
            );
        } else {
            // 更新已有配置
            if (encryptedKey == null) {
                jdbcTemplate.update(
                        "UPDATE ontology_llm_settings " +
                                "SET enabled = ?, api_base_url = ?, api_key_enc = NULL, model = ?, update_by = ?, update_time = NOW() " +
                                "WHERE setting_id = ?",
                        enabled, apiBaseUrl, model, username, SETTING_ID
                );
            } else {
                jdbcTemplate.update(
                        "UPDATE ontology_llm_settings " +
                                "SET enabled = ?, api_base_url = ?, api_key_enc = ?, model = ?, update_by = ?, update_time = NOW() " +
                                "WHERE setting_id = ?",
                        enabled, apiBaseUrl, encryptedKey, model, username, SETTING_ID
                );
            }
        }
    }

    /**
     * 更新内存中的配置
     */
    private void updateInMemoryConfig(String apiBaseUrl, String model, String apiKey, boolean enabled) {
        OntologyIntegrationProperties.LlmWiki config = properties.getLlmWiki();
        config.setEnabled(enabled);
        config.setApiBaseUrl(apiBaseUrl);
        config.setModel(model);
        config.setApiKey(apiKey);
    }

    // ==================== 工具方法 ====================

    private boolean isTrue(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return "1".equals(String.valueOf(value)) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    private String getString(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }

    private boolean getBoolean(Map<String, Object> map, String key, boolean defaultValue) {
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private boolean isNotBlank(String value) {
        return !isBlank(value);
    }
}

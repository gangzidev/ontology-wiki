package com.ontologywiki.ontology.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 外部系统连接配置。密钥只从环境变量或安全配置中心注入。 */
@ConfigurationProperties(prefix = "ontology.integration")
public class OntologyIntegrationProperties {
    private String credentialKey = "change-this-credential-key-in-production";
    private LlmWiki llmWiki = new LlmWiki();
    private Zhiwen zhiwen = new Zhiwen();
    private Platform platform = new Platform();

    public String getCredentialKey() { return credentialKey; }
    public void setCredentialKey(String credentialKey) { this.credentialKey = credentialKey; }
    public LlmWiki getLlmWiki() { return llmWiki; }
    public void setLlmWiki(LlmWiki llmWiki) { this.llmWiki = llmWiki; }
    public Zhiwen getZhiwen() { return zhiwen; }
    public void setZhiwen(Zhiwen zhiwen) { this.zhiwen = zhiwen; }
    public Platform getPlatform() { return platform; }
    public void setPlatform(Platform platform) { this.platform = platform; }

    public static class LlmWiki {
        private boolean enabled;
        private String apiBaseUrl;
        private String apiKey;
        private String model = "gpt-6-sol";
        private int connectTimeoutMs = 5000;
        private int readTimeoutMs = 120000;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getApiBaseUrl() { return apiBaseUrl; }
        public void setApiBaseUrl(String apiBaseUrl) { this.apiBaseUrl = apiBaseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getConnectTimeoutMs() { return connectTimeoutMs; }
        public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
        public int getReadTimeoutMs() { return readTimeoutMs; }
        public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
    }

    public static class Zhiwen {
        private boolean enabled;
        private String baseUrl;
        private String type = "bot";
        private String agentCode;
        private String workflowId;
        private String userId = "ontology-wiki";
        private String tenantId = "test";
        private String source = "ONTOLOGY_WIKI";
        private String secretId;
        private String secretKey;
        private String token;
        private String path = "/ais/channel/openapi/message/syncAuthChat";
        private int connectTimeoutMs = 10000;
        private int readTimeoutMs = 120000;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getAgentCode() { return agentCode; }
        public void setAgentCode(String agentCode) { this.agentCode = agentCode; }
        public String getWorkflowId() { return workflowId; }
        public void setWorkflowId(String workflowId) { this.workflowId = workflowId; }
        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
        public String getTenantId() { return tenantId; }
        public void setTenantId(String tenantId) { this.tenantId = tenantId; }
        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }
        public String getSecretId() { return secretId; }
        public void setSecretId(String secretId) { this.secretId = secretId; }
        public String getSecretKey() { return secretKey; }
        public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        public int getConnectTimeoutMs() { return connectTimeoutMs; }
        public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
        public int getReadTimeoutMs() { return readTimeoutMs; }
        public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
    }

    public static class Platform {
        private boolean enabled;
        private String baseUrl;
        private String token;
        private String importPath;
        private int connectTimeoutMs = 10000;
        private int readTimeoutMs = 120000;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
        public String getImportPath() { return importPath; }
        public void setImportPath(String importPath) { this.importPath = importPath; }
        public int getConnectTimeoutMs() { return connectTimeoutMs; }
        public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
        public int getReadTimeoutMs() { return readTimeoutMs; }
        public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
    }
}

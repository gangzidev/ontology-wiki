package com.ontologywiki.ontology.service.wiki;

import com.fasterxml.jackson.databind.JsonNode;
import com.ontologywiki.ontology.domain.WikiAssetStatus;
import com.ontologywiki.ontology.domain.WikiAssetType;
import com.ontologywiki.ontology.service.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wiki 资料整理服务
 *
 * <p>职责：
 * <ul>
 *   <li>将原始业务资料整理成结构化 Wiki 页面</li>
 *   <li>支持多种整理模板</li>
 *   <li>管理整理后的页面与原始资料的关联</li>
 * </ul>
 *
 * @author ontology-wiki
 */
@Service
public class WikiIngestionService {

    private static final Logger log = LoggerFactory.getLogger(WikiIngestionService.class);

    private static final int MAX_CONTENT_LENGTH = 30000;
    private static final int MAX_RESULT_LENGTH = 1000000;

    private final JdbcTemplate jdbcTemplate;
    private final LlmClient llmClient;
    private final WikiIndexService indexService;

    public WikiIngestionService(JdbcTemplate jdbcTemplate,
                                LlmClient llmClient,
                                WikiIndexService indexService) {
        this.jdbcTemplate = jdbcTemplate;
        this.llmClient = llmClient;
        this.indexService = indexService;
    }

    /**
     * 整理资产为 Wiki 页面（使用默认模板）
     *
     * @param assetId 原始资产 ID
     * @return 整理结果（包含生成的页面标题和 ID）
     * @throws IllegalArgumentException 资产不存在时抛出
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> ingestAsset(Long assetId) {
        return ingestAsset(assetId, WikiTemplateType.DEFAULT);
    }

    /**
     * 整理资产为 Wiki 页面（指定模板）
     *
     * @param assetId      原始资产 ID
     * @param templateType 整理模板类型
     * @return 整理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> ingestAsset(Long assetId, WikiTemplateType templateType) {
        // 查询原始资产
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT asset_id, space_id, asset_name, asset_type, domain_name, description, content " +
                        "FROM wiki_asset " +
                        "WHERE asset_id = ? AND status = ?",
                assetId,
                WikiAssetStatus.ACTIVE.getCode()
        );

        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Wiki 资产不存在或已停用: " + assetId);
        }

        Map<String, Object> source = rows.get(0);

        // 构建整理提示词
        String prompt = buildIngestionPrompt(source, templateType);

        // 调用模型整理
        String llmResponse = llmClient.chat(prompt);

        // 解析结果
        JsonNode result = llmClient.parseJson(llmResponse);

        if (result == null || !result.path("content").isTextual() || result.path("content").asText().trim().isEmpty()) {
            throw new IllegalStateException("模型未返回有效 Wiki 页面 JSON");
        }

        String title = clip(result.path("title").asText(String.valueOf(source.get("asset_name"))), 200);
        String content = clip(result.path("content").asText(), MAX_RESULT_LENGTH);
        String summary = clip(result.path("summary").asText("来源资料摘要"), 1000);

        // 保存或更新 Wiki 页面
        String sourceTag = "SOURCE:" + assetId;
        Long spaceId = source.get("space_id") == null ? null : ((Number) source.get("space_id")).longValue();

        List<Map<String, Object>> existingPages = jdbcTemplate.queryForList(
                "SELECT asset_id FROM wiki_asset WHERE asset_type = ? AND tags = ? LIMIT 1",
                WikiAssetType.WIKI_PAGE.getCode(),
                sourceTag
        );

        if (existingPages.isEmpty()) {
            // 插入新页面
            jdbcTemplate.update(
                    "INSERT INTO wiki_asset (space_id, asset_name, asset_type, domain_name, description, content, tags, status, " +
                            "create_by, create_time, update_by, update_time) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'system', NOW(), 'system', NOW())",
                    spaceId,
                    title,
                    WikiAssetType.WIKI_PAGE.getCode(),
                    source.get("domain_name"),
                    summary,
                    content,
                    sourceTag,
                    WikiAssetStatus.ACTIVE.getCode()
            );
        } else {
            // 更新已有页面
            Long pageAssetId = ((Number) existingPages.get(0).get("asset_id")).longValue();
            jdbcTemplate.update(
                    "UPDATE wiki_asset " +
                            "SET space_id = ?, asset_name = ?, domain_name = ?, description = ?, content = ?, " +
                            "update_by = 'system', update_time = NOW() " +
                            "WHERE asset_id = ?",
                    spaceId,
                    title,
                    source.get("domain_name"),
                    summary,
                    content,
                    pageAssetId
            );
        }

        // 刷新索引
        indexService.refreshIndex();

        // 返回结果
        Map<String, Object> resultMap = new LinkedHashMap<>();
        resultMap.put("title", title);
        resultMap.put("sourceId", assetId);
        resultMap.put("updated", !existingPages.isEmpty());
        resultMap.put("template", templateType.name());

        return resultMap;
    }

    /**
     * 构建整理提示词
     */
    private String buildIngestionPrompt(Map<String, Object> source, WikiTemplateType templateType) {
        String assetName = String.valueOf(source.get("asset_name"));
        String assetType = String.valueOf(source.get("asset_type"));
        String domainName = String.valueOf(source.get("domain_name"));
        String description = String.valueOf(source.get("description"));
        String content = String.valueOf(source.get("content"));

        String templateInstruction = getTemplateInstruction(templateType);

        return "请把输入的业务资料整理成一张持久化 Wiki 页面。" +
                "保留可核验事实、术语、规则及来源，不推测缺失信息。\n" +
                templateInstruction + "\n" +
                "只返回 JSON：{\"title\":\"...\",\"summary\":\"...\",\"content\":\"Markdown...\"}\n\n" +
                "资料标题：" + assetName + "\n" +
                "资料类型：" + assetType + "\n" +
                "业务域：" + domainName + "\n" +
                "说明：" + description + "\n" +
                "正文：\n" + clip(content, MAX_CONTENT_LENGTH);
    }

    /**
     * 获取模板指令
     */
    private String getTemplateInstruction(WikiTemplateType templateType) {
        switch (templateType) {
            case OBJECT:
                return "请按以下结构整理对象定义：\n" +
                        "# 对象名称\n" +
                        "## 业务含义\n" +
                        "## 关键属性\n" +
                        "## 业务规则\n" +
                        "## 关联对象";

            case FIELD:
                return "请按以下结构整理字段说明：\n" +
                        "# 字段名称\n" +
                        "## 业务含义\n" +
                        "## 数据类型\n" +
                        "## 取值范围\n" +
                        "## 示例值";

            case RULE:
                return "请按以下结构整理业务规则：\n" +
                        "# 规则名称\n" +
                        "## 适用场景\n" +
                        "## 规则条件\n" +
                        "## 规则动作\n" +
                        "## 异常处理";

            case PROCESS:
                return "请按以下结构整理业务流程：\n" +
                        "# 流程名称\n" +
                        "## 触发条件\n" +
                        "## 操作步骤\n" +
                        "## 涉及角色\n" +
                        "## 结束状态";

            case DEFAULT:
            default:
                return "请自动识别资料类型，选择最合适的结构进行整理。";
        }
    }

    /**
     * 裁剪字符串长度
     */
    private String clip(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.substring(0, Math.min(value.length(), maxLength));
    }

    /**
     * Wiki 整理模板枚举
     */
    public enum WikiTemplateType {
        /** 默认模板（自动识别） */
        DEFAULT("默认模板", "自动识别资料类型并整理"),

        /** 对象定义模板 */
        OBJECT("对象定义", "适用于业务对象、实体描述"),

        /** 字段说明模板 */
        FIELD("字段说明", "适用于数据库字段、属性说明"),

        /** 业务规则模板 */
        RULE("业务规则", "适用于校验规则、计算逻辑"),

        /** 流程说明模板 */
        PROCESS("流程说明", "适用于业务流程、操作步骤");

        private final String displayName;
        private final String description;

        WikiTemplateType(String displayName, String description) {
            this.displayName = displayName;
            this.description = description;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getDescription() {
            return description;
        }
    }
}

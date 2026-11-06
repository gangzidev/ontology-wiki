package com.ontologywiki.ontology.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 大模型配置（{@code ontology_llm_settings}）数据访问。
 *
 * <p>覆盖旧版「单配置」读写路径，以及多配置表中的默认配置读取。
 */
@Mapper
public interface LlmSettingsMapper {

    /**
     * 读取最新一条启用配置，用于系统启动时的内存兜底。
     *
     * @return 配置列表，最多一条
     */
    List<Map<String, Object>> selectLatestEnabled();

    /**
     * 判断旧版单配置行是否存在。
     *
     * @param settingId 旧版固定配置标识
     * @return 命中的配置行
     */
    List<Map<String, Object>> selectBySettingId(@Param("settingId") int settingId);

    /**
     * 首次写入旧版单配置。
     *
     * @param settingId    配置标识
     * @param enabled      是否启用
     * @param apiBaseUrl   API 地址
     * @param apiKeyEnc    加密后的 API Key，可为空
     * @param model        模型名称
     * @param username     操作人
     * @return 影响行数
     */
    int insertSingleSetting(@Param("settingId") int settingId,
                            @Param("enabled") boolean enabled,
                            @Param("apiBaseUrl") String apiBaseUrl,
                            @Param("apiKeyEnc") String apiKeyEnc,
                            @Param("model") String model,
                            @Param("username") String username);

    /**
     * 更新旧版单配置，并把 API Key 清空。
     *
     * @param settingId  配置标识
     * @param enabled    是否启用
     * @param apiBaseUrl API 地址
     * @param model      模型名称
     * @param username   操作人
     * @return 影响行数
     */
    int updateSingleSettingClearKey(@Param("settingId") int settingId,
                                    @Param("enabled") boolean enabled,
                                    @Param("apiBaseUrl") String apiBaseUrl,
                                    @Param("model") String model,
                                    @Param("username") String username);

    /**
     * 更新旧版单配置，连同 API Key 一起覆盖。
     *
     * @param settingId  配置标识
     * @param enabled    是否启用
     * @param apiBaseUrl API 地址
     * @param apiKeyEnc  加密后的 API Key
     * @param model      模型名称
     * @param username   操作人
     * @return 影响行数
     */
    int updateSingleSetting(@Param("settingId") int settingId,
                            @Param("enabled") boolean enabled,
                            @Param("apiBaseUrl") String apiBaseUrl,
                            @Param("apiKeyEnc") String apiKeyEnc,
                            @Param("model") String model,
                            @Param("username") String username);

    /**
     * 只更新旧版单配置的启用开关、地址与模型，保留原有 API Key。
     *
     * @param settingId  配置标识
     * @param enabled    是否启用
     * @param apiBaseUrl API 地址
     * @param model      模型名称
     * @param username   操作人
     * @return 影响行数
     */
    int updateSingleSettingKeepKey(@Param("settingId") int settingId,
                                   @Param("enabled") boolean enabled,
                                   @Param("apiBaseUrl") String apiBaseUrl,
                                   @Param("model") String model,
                                   @Param("username") String username);

    // ==================== 多配置管理 ====================

    /**
     * 列出全部模型配置（不含密钥明文，只返回是否已配置）。
     *
     * @return 配置列表
     */
    List<Map<String, Object>> selectAll();

    /**
     * 按主键读取配置的连接信息。
     *
     * @param id 配置主键
     * @return 配置信息，不存在时为空列表
     */
    List<Map<String, Object>> selectById(@Param("id") Long id);

    /**
     * 新增一条多场景模型配置并回填自增主键。
     *
     * @param params 参数集合：settingName、providerType、usageScene、enabled、apiBaseUrl、
     *               apiKeyEnc、model、username、generatedId（输出）
     * @return 影响行数
     */
    int insertConfig(Map<String, Object> params);

    /**
     * 更新配置并清空密钥。
     *
     * @param params 参数集合：id、settingName、providerType、usageScene、enabled、apiBaseUrl、model、username
     * @return 影响行数
     */
    int updateConfigClearKey(Map<String, Object> params);

    /**
     * 更新配置并覆盖密钥。
     *
     * @param params 参数集合：同上，额外含 apiKeyEnc
     * @return 影响行数
     */
    int updateConfigWithKey(Map<String, Object> params);

    /**
     * 更新配置并保留原密钥。
     *
     * @param params 参数集合：id、settingName、providerType、usageScene、enabled、apiBaseUrl、model、username
     * @return 影响行数
     */
    int updateConfigKeepKey(Map<String, Object> params);

    /**
     * 删除一条配置。
     *
     * @param id 配置主键
     * @return 影响行数
     */
    int deleteById(@Param("id") Long id);
}

package com.ontologywiki.ontology.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * LLM Wiki 门面服务的兜底查询。
 *
 * <p>仅供已标记为 {@code @Deprecated} 的 {@code LlmWikiFacade} 兼容接口使用，
 * 新代码请直接使用 {@link WikiAssetMapper} 与分析记忆相关 Mapper。
 */
@Mapper
public interface LlmWikiFacadeMapper {

    /**
     * 读取最近的问答日志。
     *
     * @param limit 最多条数
     * @return 问答消息列表
     */
    List<Map<String, Object>> selectRecentChatMessages(@Param("limit") int limit);

    /**
     * 新增一条 URL 来源资料，等待后续整理。
     *
     * @param url 来源地址
     * @return 影响行数
     */
    int insertUrlSource(@Param("url") String url);
}

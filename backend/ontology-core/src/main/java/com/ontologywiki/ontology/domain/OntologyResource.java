package com.ontologywiki.ontology.domain;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 本体 Wiki 通用资源定义。
 *
 * <p>空间、资产、数据源、分析任务、配置草案、导入记录六类资源的表结构形态一致，
 * 因此把「表名、主键、可写列、必填列、可搜索列」集中在此描述，避免散落在 SQL 与分支判断中。
 *
 * <p>所有字段都来自服务端白名单，不接受前端传入，所以下游使用它拼接 SQL 是安全的。
 */
public final class OntologyResource {

    /** 六类资源的注册表，key 为接口路径中的资源名 */
    private static final Map<String, OntologyResource> REGISTRY = new LinkedHashMap<>();

    static {
        register(new OntologyResource(
                "spaces", "wiki_space", "space_id",
                "space_name,description,status,create_by",
                "space_name"));
        register(new OntologyResource(
                "assets", "wiki_asset", "asset_id",
                "space_id,asset_name,asset_type,domain_name,description,content,tags,status,create_by",
                "space_id,asset_name,asset_type"));
        register(new OntologyResource(
                "datasources", "ontology_datasource", "datasource_id",
                "name,db_type,host,port,database_name,schema_name,jdbc_url,username,sample_limit,description,status,create_by",
                "name,db_type"));
        register(new OntologyResource(
                "analyses", "analysis_task", "task_id",
                "task_name,source_id,wiki_space_id,scope,status,summary,create_by",
                "task_name,source_id,wiki_space_id"));
        register(new OntologyResource(
                "proposals", "ontology_proposal", "proposal_id",
                "proposal_name,proposal_type,task_id,config_json,status,create_by",
                "proposal_name,proposal_type"));
        register(new OntologyResource(
                "imports", "ontology_import_record", "import_id",
                "proposal_id,target_system,status,detail,create_by",
                "target_system"));
    }

    /** 资源名，对应接口路径片段 */
    private final String key;
    /** 物理表名 */
    private final String table;
    /** 主键列名 */
    private final String idColumn;
    /** 允许前端写入的业务列（不含审计列） */
    private final String columns;
    /** 必填列 */
    private final String required;
    /** 参与关键字模糊匹配的列 */
    private final String searchColumns;

    private OntologyResource(String key, String table, String idColumn, String columns, String required) {
        this.key = key;
        this.table = table;
        this.idColumn = idColumn;
        this.columns = columns;
        this.required = required;
        // 搜索列由可写列转换而来：把 "a,b,c" 变成 "a b c"，配合 concat_ws 使用
        this.searchColumns = String.join(" ", columns.split(","));
    }

    private static void register(OntologyResource resource) {
        REGISTRY.put(resource.key, resource);
    }

    /**
     * 取得全部资源定义，用于工作台统计。
     *
     * @return 资源名到定义的映射
     */
    public static Map<String, OntologyResource> all() {
        return Collections.unmodifiableMap(REGISTRY);
    }

    /**
     * 按资源名取定义。
     *
     * @param key 资源名
     * @return 资源定义
     * @throws IllegalArgumentException 资源名不在白名单内
     */
    public static OntologyResource of(String key) {
        OntologyResource resource = REGISTRY.get(key);
        if (resource == null) {
            throw new IllegalArgumentException("未知资源类型");
        }
        return resource;
    }

    /**
     * 拼接列表查询所需的完整列集合：主键、业务列、草案审核列与审计列。
     *
     * @return 逗号分隔的列名
     */
    public String selectColumns() {
        StringBuilder sql = new StringBuilder(idColumn).append(',').append(columns);
        // 草案表额外带出审核信息，前端列表需要展示审核人与审核意见
        if ("ontology_proposal".equals(table)) {
            sql.append(",review_by,review_time,review_comment");
        }
        return sql.append(",create_time,update_by,update_time").toString();
    }

    /**
     * 可写列集合，用于过滤前端提交的多余字段。
     *
     * @return 列名集合
     */
    public Set<String> writableColumns() {
        return new LinkedHashSet<>(Arrays.asList(columns.split(",")));
    }

    /**
     * 必填列集合。
     *
     * @return 列名集合
     */
    public Set<String> requiredColumns() {
        return new LinkedHashSet<>(Arrays.asList(required.split(",")));
    }

    public String getKey() {
        return key;
    }

    public String getTable() {
        return table;
    }

    public String getIdColumn() {
        return idColumn;
    }

    public String getSearchColumns() {
        return searchColumns;
    }
}

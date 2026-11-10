package com.ontologywiki.ontology.service;

import com.ontologywiki.ontology.domain.OntologyResource;
import com.ontologywiki.ontology.repository.OntologyResourceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 本体 Wiki 通用资源服务。
 *
 * <p>承担五类资源（空间、资产、数据源、分析任务、配置草案）的通用增删改查业务规则：
 * 字段白名单收口、必填校验、行业空间归属校验、草案状态保护、数据源密码加密与 JDBC 地址校验。
 *
 * <p>SQL 已全部下沉到 {@link OntologyResourceRepository} 与对应 Mapper XML，
 * 本类只保留校验与编排逻辑。
 */
@Service
public class OntologyResourceService {

    /** 启用状态值 */
    private static final String STATUS_ENABLED = "0";

    /** 草案待审核状态值 */
    private static final String STATUS_DRAFT = "DRAFT";

    /** 资产业务域列名 */
    private static final String COLUMN_ASSET_SPACE = "space_id";

    /** 分析任务业务域列名 */
    private static final String COLUMN_ANALYSIS_SPACE = "wiki_space_id";

    private final OntologyResourceRepository repository;
    private final CredentialService credentials;

    public OntologyResourceService(OntologyResourceRepository repository,
                                   CredentialService credentials) {
        this.repository = repository;
        this.credentials = credentials;
    }

    /** 工作台统计：各资源记录数 */
    public Map<String, Object> dashboardCounts() {
        return repository.countAll();
    }

    /**
     * 分页查询资源列表。
     *
     * @param resourceKey 资源名
     * @param pageNum     页码
     * @param pageSize    每页条数
     * @param keyword     关键字
     * @param spaceId     知识空间过滤
     * @param taskId      分析任务过滤
     * @return 分页结果
     */
    public OntologyResourceRepository.PageResult page(String resourceKey, int pageNum, int pageSize,
                                                      String keyword, Long spaceId, Long taskId) {
        OntologyResource resource = OntologyResource.of(resourceKey);
        OntologyResourceRepository.PageResult result = repository.page(resource, pageNum, pageSize, keyword, spaceId, taskId);
        // 草案列表需要展示来源任务名称
        if ("proposals".equals(resourceKey)) {
            repository.fillTaskNames(result.getRows());
        }
        return result;
    }

    /**
     * 查询单条资源。
     *
     * @param resourceKey 资源名
     * @param id          主键
     * @return 记录，不存在返回 null
     */
    public Map<String, Object> find(String resourceKey, long id) {
        OntologyResource resource = OntologyResource.of(resourceKey);
        Map<String, Object> row = repository.findById(resource, id);
        if (row != null && "proposals".equals(resourceKey)) {
            repository.fillTaskNames(java.util.Collections.singletonList(row));
        }
        return row;
    }

    /**
     * 新增资源。
     *
     * @param resourceKey 资源名
     * @param body        前端提交内容
     * @return 新记录主键
     * @throws IllegalArgumentException 校验不通过时抛出，由 Controller 转换为业务提示
     */
    @Transactional(rollbackFor = Exception.class)
    public Long create(String resourceKey, Map<String, Object> body) {
        OntologyResource resource = OntologyResource.of(resourceKey);
        // 数据源密码单独处理：先取出，避免进入通用列写入
        String datasourcePassword = extractDatasourcePassword(resourceKey, body, false);
        rejectProposalStatusChange(resourceKey, body);

        Map<String, Object> data = filterWritable(resource, body);
        if ("datasources".equals(resourceKey) && !isPostgres(data.get("db_type"))) {
            data.put("schema_name", "");
        }
        requireValidJdbcUrl(resourceKey, data);
        requireNotEmpty(data);
        requireRequired(resource, data);
        requireIndustrySpace(resourceKey, data, null);

        String username = com.ontologywiki.common.utils.SecurityUtils.getUsername();
        data.put("create_by", username);
        data.put("update_by", username);
        data.put("create_time", new Date());
        data.put("update_time", new Date());

        Long newId = repository.insert(resource, data);
        if (datasourcePassword != null && newId != null) {
            saveDatasourcePassword(newId, datasourcePassword);
        }
        return newId;
    }

    /**
     * 修改资源。
     *
     * @param resourceKey 资源名
     * @param id          主键
     * @param body        前端提交内容
     * @return 影响行数
     */
    @Transactional(rollbackFor = Exception.class)
    public int update(String resourceKey, long id, Map<String, Object> body) {
        OntologyResource resource = OntologyResource.of(resourceKey);
        String datasourcePassword = extractDatasourcePassword(resourceKey, body, true);
        rejectProposalStatusChange(resourceKey, body);

        Map<String, Object> data = filterWritable(resource, body);
        if ("datasources".equals(resourceKey) && !isPostgres(data.get("db_type"))) {
            data.put("schema_name", "");
        }
        requireValidJdbcUrl(resourceKey, data);
        requireNotEmpty(data);
        requireRequired(resource, data);
        requireIndustrySpace(resourceKey, data, id);

        // 草案内容或类型变化后，需要重新由工程师修正并标记完成
        boolean resetProposalReview = shouldResetProposalReview(resourceKey, id, data);
        String username = com.ontologywiki.common.utils.SecurityUtils.getUsername();
        data.put("update_by", username);
        data.put("update_time", new Date());

        int updated = repository.update(resource, id, data);
        if (updated > 0 && resetProposalReview) {
            repository.resetProposalReview(id, STATUS_DRAFT);
        }
        if (updated > 0 && datasourcePassword != null) {
            saveDatasourcePassword(id, datasourcePassword);
        }
        return updated;
    }

    /**
     * 批量删除资源。
     *
     * @param resourceKey 资源名
     * @param ids         逗号分隔的主键
     * @return 影响行数
     */
    public int delete(String resourceKey, String ids) {
        OntologyResource resource = OntologyResource.of(resourceKey);
        String[] parts = ids.split(",");
        if (parts.length > 100) {
            throw new IllegalArgumentException("单次最多删除100条");
        }
        List<Long> keys = new java.util.ArrayList<>(parts.length);
        for (String part : parts) {
            keys.add(Long.parseLong(part.trim()));
        }
        return repository.deleteByIds(resource, keys);
    }

    // ==================== 校验与转换 ====================

    /** 提取并校验数据源密码，新增时允许为空，编辑时为空表示不修改 */
    private String extractDatasourcePassword(String resourceKey, Map<String, Object> body, boolean editing) {
        if (!"datasources".equals(resourceKey) || body == null) {
            return null;
        }
        Object raw = body.get("password");
        if (raw == null) {
            return null;
        }
        String password = String.valueOf(raw);
        if (password.length() > 2048) {
            throw new IllegalArgumentException("数据库密码过长");
        }
        if (!editing && password.trim().isEmpty()) {
            throw new IllegalArgumentException("数据库密码不能为空或过长");
        }
        return password.trim().isEmpty() ? null : password;
    }

    /** 草案状态只能由审核接口维护，普通编辑不允许直接改状态 */
    private void rejectProposalStatusChange(String resourceKey, Map<String, Object> body) {
        if ("proposals".equals(resourceKey) && body != null && body.containsKey("status")) {
            throw new IllegalArgumentException("草案状态由审核操作维护");
        }
    }

    /** 只保留资源白名单内的列，去掉前端可能提交的多余字段 */
    private Map<String, Object> filterWritable(OntologyResource resource, Map<String, Object> input) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (input == null) {
            return out;
        }
        java.util.Set<String> allowed = resource.writableColumns();
        input.forEach((key, value) -> {
            if (allowed.contains(key) && value != null) {
                out.put(key, value);
            }
        });
        return out;
    }

    /** JDBC 地址校验：不允许内嵌账号密码，且前缀必须与数据库类型一致 */
    private void requireValidJdbcUrl(String resourceKey, Map<String, Object> data) {
        if (!"datasources".equals(resourceKey) || !data.containsKey("jdbc_url")) {
            return;
        }
        Object raw = data.get("jdbc_url");
        String url = raw == null ? "" : String.valueOf(raw);
        if (url.trim().isEmpty()) {
            return;
        }
        if (!isValidJdbcUrl(String.valueOf(data.get("db_type")), url)) {
            throw new IllegalArgumentException("JDBC URL 无效、与数据库类型不匹配或包含账号密码；请检查地址并将密码单独填写");
        }
    }

    /**
     * 判断 JDBC 地址是否合法。
     *
     * <p>供数据源连接测试复用，保持与保存时同一套规则，避免「能测通却存不进」的不一致。
     *
     * @param dbType 数据库类型
     * @param url    JDBC 地址
     * @return 合法返回 true
     */
    public boolean isValidJdbcUrl(String dbType, String url) {
        if (url == null || url.trim().isEmpty()) {
            return true;
        }
        // 地址中不允许内嵌账号密码，密码必须通过独立字段录入并加密保存
        if (url.matches("(?is).*([?;&](password|passwd|pwd)=|//[^/@]+@).*?")) {
            return false;
        }
        String type = dbType == null ? "" : dbType.trim().toLowerCase(Locale.ROOT);
        return ("mysql".equals(type) && url.matches("(?is)^jdbc:mysql://.*"))
                || (("postgresql".equals(type) || "postgres".equals(type)) && url.matches("(?is)^jdbc:(postgresql|postgres)://.*"))
                || ("hive".equals(type) && url.matches("(?is)^jdbc:hive2://.*"));
    }

    /** 过滤后没有任何字段时直接拒绝，避免执行空插入 */
    private void requireNotEmpty(Map<String, Object> data) {
        if (data.isEmpty()) {
            throw new IllegalArgumentException("请填写有效内容");
        }
    }

    /** 必填列校验 */
    private void requireRequired(OntologyResource resource, Map<String, Object> data) {
        for (String column : resource.requiredColumns()) {
            Object value = data.get(column);
            if (value == null || String.valueOf(value).trim().isEmpty()) {
                throw new IllegalArgumentException("缺少必填字段");
            }
        }
    }

    /**
     * 行业空间归属校验。
     *
     * <p>资产必须归属某个行业空间，分析任务必须绑定行业空间；
     * 编辑时若前端未提交该字段，则回查库中已有值，避免误判为未填写。
     */
    private void requireIndustrySpace(String resourceKey, Map<String, Object> data, Long id) {
        if (!"analyses".equals(resourceKey) && !"assets".equals(resourceKey)) {
            return;
        }
        String field = "analyses".equals(resourceKey) ? COLUMN_ANALYSIS_SPACE : COLUMN_ASSET_SPACE;
        Object rawSpaceId = data.get(field);
        if (rawSpaceId == null && id != null) {
            OntologyResource resource = OntologyResource.of(resourceKey);
            rawSpaceId = repository.findColumnById(resource, field, id);
        }
        if (rawSpaceId == null) {
            throw new IllegalArgumentException("analyses".equals(resourceKey)
                    ? "请选择行业知识空间；一个分析任务只使用该空间绑定的 Wiki 资产"
                    : "请选择所属行业知识空间；未归类资料不会参与本体分析");
        }
        long spaceId;
        try {
            spaceId = Long.parseLong(String.valueOf(rawSpaceId));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("所选行业知识空间无效");
        }
        if (!repository.existsEnabledSpace(spaceId, STATUS_ENABLED)) {
            throw new IllegalArgumentException("所选行业知识空间不存在或已停用");
        }
    }

    /** 判断草案内容或类型是否发生变化，变化则需重置审核状态 */
    private boolean shouldResetProposalReview(String resourceKey, long id, Map<String, Object> data) {
        if (!"proposals".equals(resourceKey) || (!data.containsKey("config_json") && !data.containsKey("proposal_type"))) {
            return false;
        }
        Map<String, Object> current = repository.findProposalSnapshot(id);
        if (current == null) {
            return false;
        }
        boolean typeChanged = data.containsKey("proposal_type")
                && !java.util.Objects.equals(data.get("proposal_type"), current.get("proposal_type"));
        boolean configChanged = data.containsKey("config_json")
                && !java.util.Objects.equals(data.get("config_json"), current.get("config_json"));
        return typeChanged || configChanged;
    }

    /** 数据源密码加密落库 */
    private void saveDatasourcePassword(long datasourceId, String password) {
        repository.updateDatasourcePassword(datasourceId, credentials.encrypt(password),
                com.ontologywiki.common.utils.SecurityUtils.getUsername());
    }

    /** 判断是否为 PostgreSQL 系列数据库 */
    private boolean isPostgres(Object type) {
        String value = type == null ? "" : String.valueOf(type).trim().toLowerCase(Locale.ROOT);
        return "postgresql".equals(value) || "postgres".equals(value);
    }
}

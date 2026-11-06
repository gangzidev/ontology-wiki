package com.ontologywiki.ontology.repository;

import com.ontologywiki.ontology.domain.OntologyResource;
import com.ontologywiki.ontology.mapper.OntologyResourceMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 通用资源仓储。
 *
 * <p>职责边界：只做「把领域对象翻译成 Mapper 参数」和「结果整理」，
 * 不包含业务校验、权限判断和状态流转，这些逻辑保留在 Service 层。
 */
@Repository
public class OntologyResourceRepository {

    /** 单页最大条数，防止前端传入超大 pageSize 拖垮数据库 */
    private static final int MAX_PAGE_SIZE = 100;

    private final OntologyResourceMapper mapper;

    public OntologyResourceRepository(OntologyResourceMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 统计六类资源各自的记录总数。
     *
     * @return 资源名到数量的映射
     */
    public Map<String, Object> countAll() {
        Map<String, Object> counts = new LinkedHashMap<>();
        OntologyResource.all().forEach((key, resource) -> counts.put(key, mapper.countAll(resource.getTable())));
        return counts;
    }

    /**
     * 分页查询资源列表。
     *
     * @param resource 资源定义
     * @param pageNum  页码，从 1 开始
     * @param pageSize 每页条数
     * @param keyword  关键字，可为空
     * @param spaceId  知识空间过滤，可为空
     * @param taskId   分析任务过滤，可为空
     * @return 包含 total 与 rows 的结果
     */
    public PageResult page(OntologyResource resource, int pageNum, int pageSize, String keyword, Long spaceId, Long taskId) {
        int size = Math.max(1, Math.min(pageSize, MAX_PAGE_SIZE));
        int page = Math.max(pageNum, 1);
        boolean excludeSystemIndex = "assets".equals(resource.getKey());
        // 关键字两端加通配符，配合 XML 中的 like 使用
        String likeKeyword = keyword == null || keyword.trim().isEmpty()
                ? null
                : "%" + clipKeyword(keyword.trim()) + "%";

        long total = mapper.countPage(resource.getTable(), excludeSystemIndex, spaceId, taskId,
                likeKeyword, resource.getSearchColumns());
        long offset = ((long) page - 1) * size;
        List<Map<String, Object>> rows = mapper.selectPage(resource.getTable(), resource.selectColumns(),
                resource.getIdColumn(), excludeSystemIndex, spaceId, taskId, likeKeyword,
                resource.getSearchColumns(), size, offset);
        return new PageResult(total, rows);
    }

    /**
     * 按主键查询一条记录。
     *
     * @param resource 资源定义
     * @param id       主键值
     * @return 记录，不存在时返回 null
     */
    public Map<String, Object> findById(OntologyResource resource, long id) {
        List<Map<String, Object>> rows = mapper.selectById(resource.getTable(), resource.selectColumns(),
                resource.getIdColumn(), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 按主键读取单个列值，用于跨表归属校验。
     *
     * @param resource 资源定义
     * @param column   目标列
     * @param id       主键值
     * @return 列值，记录不存在时返回 null
     */
    public Object findColumnById(OntologyResource resource, String column, long id) {
        return mapper.selectColumnById(resource.getTable(), column, resource.getIdColumn(), id);
    }

    /**
     * 插入一条记录。
     *
     * @param resource 资源定义
     * @param data     已通过白名单过滤的列与值
     * @return 自增主键，驱动未返回时返回 null
     */
    public Long insert(OntologyResource resource, Map<String, Object> data) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("table", resource.getTable());
        params.put("idColumn", resource.getIdColumn());
        params.put("data", data);
        params.put("generatedId", null);
        mapper.insert(params);
        Object generated = params.get("generatedId");
        return generated instanceof Number ? ((Number) generated).longValue() : null;
    }

    /**
     * 按主键更新记录。
     *
     * @param resource 资源定义
     * @param id       主键值
     * @param data     待更新的列与值
     * @return 影响行数
     */
    public int update(OntologyResource resource, long id, Map<String, Object> data) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("table", resource.getTable());
        params.put("idColumn", resource.getIdColumn());
        params.put("id", id);
        params.put("data", data);
        return mapper.update(params);
    }

    /**
     * 按主键批量删除。
     *
     * @param resource 资源定义
     * @param ids      主键集合
     * @return 影响行数
     */
    public int deleteByIds(OntologyResource resource, List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        return mapper.deleteByIds(resource.getTable(), resource.getIdColumn(), ids);
    }

    /**
     * 批量补充分析任务名称。
     *
     * <p>草案列表需要展示来源任务名，原始 SQL 使用子查询逐行获取，
     * 这里改为一次批量查询后在内存中回填，减少数据库往返。
     *
     * @param rows 草案列表
     */
    public void fillTaskNames(List<Map<String, Object>> rows) {
        List<Long> taskIds = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object taskId = row.get("task_id");
            if (taskId instanceof Number) {
                taskIds.add(((Number) taskId).longValue());
            }
        }
        Map<Long, String> names = new LinkedHashMap<>();
        if (!taskIds.isEmpty()) {
            Set<Long> distinct = new LinkedHashSet<>(taskIds);
            for (Map<String, Object> row : mapper.selectTaskNames(new ArrayList<>(distinct))) {
                names.put(((Number) row.get("task_id")).longValue(), String.valueOf(row.get("task_name")));
            }
        }
        for (Map<String, Object> row : rows) {
            Object taskId = row.get("task_id");
            String name;
            if (taskId == null) {
                name = "手工创建";
            } else {
                name = names.getOrDefault(((Number) taskId).longValue(), "来源任务已删除");
            }
            row.put("task_name", name);
        }
    }

    /** 关键字截断，避免超长 like 拖慢查询 */
    private String clipKeyword(String keyword) {
        return keyword.length() > 200 ? keyword.substring(0, 200) : keyword;
    }

    // ==================== 跨表校验与附属写入 ====================

    /**
     * 判断行业知识空间是否存在且启用。
     *
     * @param spaceId 知识空间主键
     * @param status  期望状态
     * @return 存在且启用返回 true
     */
    public boolean existsEnabledSpace(Long spaceId, String status) {
        return mapper.countEnabledSpace(spaceId, status) > 0;
    }

    /**
     * 读取草案的类型与配置快照。
     *
     * @param proposalId 草案主键
     * @return 快照记录，不存在时返回 null
     */
    public Map<String, Object> findProposalSnapshot(Long proposalId) {
        List<Map<String, Object>> rows = mapper.selectProposalSnapshot(proposalId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 把草案重置为待审核。
     *
     * @param proposalId 草案主键
     * @param status     重置后的状态
     * @return 影响行数
     */
    public int resetProposalReview(Long proposalId, String status) {
        return mapper.resetProposalReview(proposalId, status);
    }

    /**
     * 回写数据源密码密文。
     *
     * @param datasourceId 数据源主键
     * @param passwordEnc  密文
     * @param username     操作人
     * @return 影响行数
     */
    public int updateDatasourcePassword(Long datasourceId, String passwordEnc, String username) {
        return mapper.updateDatasourcePassword(datasourceId, passwordEnc, username);
    }

    /** 分页结果，保持与原 TableDataInfo 相同的取值方式 */
    public static final class PageResult {
        private final long total;
        private final List<Map<String, Object>> rows;

        PageResult(long total, List<Map<String, Object>> rows) {
            this.total = total;
            this.rows = rows == null ? Collections.emptyList() : rows;
        }

        public long getTotal() {
            return total;
        }

        public List<Map<String, Object>> getRows() {
            return rows;
        }
    }
}

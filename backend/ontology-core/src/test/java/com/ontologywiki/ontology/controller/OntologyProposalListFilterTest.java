package com.ontologywiki.ontology.controller;

import com.ontologywiki.common.core.page.TableDataInfo;
import com.ontologywiki.ontology.mapper.OntologyResourceMapper;
import com.ontologywiki.ontology.repository.OntologyResourceRepository;
import com.ontologywiki.ontology.service.OntologyResourceService;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 资源列表查询条件测试。
 *
 * <p>重构后列表 SQL 已下沉到 {@link OntologyResourceMapper} 对应的 XML，
 * 因此这里改为断言「传给 Mapper 的条件参数」，等价覆盖原有的两条规则：
 * <ul>
 *   <li>分析任务过滤只对草案列表生效；</li>
 *   <li>资产列表必须排除系统索引页。</li>
 * </ul>
 */
public class OntologyProposalListFilterTest {

    @Test
    public void taskFilterIsAppliedOnlyToProposalListQueries() {
        Recorder recorder = new Recorder();
        OntologyController controller = controller(recorder);

        TableDataInfo result = controller.list("proposals", 1, 20, null, null, 42L);

        Assert.assertEquals(0L, result.getTotal());
        Assert.assertEquals(Long.valueOf(42L), recorder.taskIds.get(0));
        Assert.assertEquals(Long.valueOf(42L), recorder.taskIds.get(1));
        Assert.assertFalse("草案列表不需要排除系统索引页", recorder.excludeSystemIndexFlags.get(0));
    }

    @Test
    public void assetListHidesTheSystemWikiIndex() {
        Recorder recorder = new Recorder();
        OntologyController controller = controller(recorder);

        controller.list("assets", 1, 20, null, null, null);

        Assert.assertTrue("资产列表必须排除系统索引页", recorder.excludeSystemIndexFlags.get(0));
        Assert.assertTrue("资产列表必须排除系统索引页", recorder.excludeSystemIndexFlags.get(1));
    }

    // ==================== 测试装配 ====================

    private OntologyController controller(Recorder recorder) {
        OntologyResourceRepository repository = new OntologyResourceRepository(recorder.mapper());
        OntologyResourceService service = new OntologyResourceService(repository, null);
        return new OntologyController(null, null, null, null, null, null, service,
                null, null, null, null, null, null);
    }

    /** 记录 Mapper 收到的查询条件，供断言使用。 */
    private static final class Recorder {
        final List<Long> taskIds = new ArrayList<>();
        final List<Boolean> excludeSystemIndexFlags = new ArrayList<>();

        OntologyResourceMapper mapper() {
            InvocationHandler handler = (proxy, method, args) -> {
                switch (method.getName()) {
                    case "countPage":
                        excludeSystemIndexFlags.add((Boolean) args[1]);
                        taskIds.add((Long) args[3]);
                        return 0L;
                    case "selectPage":
                        excludeSystemIndexFlags.add((Boolean) args[3]);
                        taskIds.add((Long) args[5]);
                        return Collections.emptyList();
                    case "selectTaskNames":
                        return Collections.emptyList();
                    default:
                        throw new AssertionError("不应调用 Mapper 方法：" + method.getName());
                }
            };
            return (OntologyResourceMapper) Proxy.newProxyInstance(
                    OntologyResourceMapper.class.getClassLoader(),
                    new Class<?>[]{OntologyResourceMapper.class}, handler);
        }
    }
}

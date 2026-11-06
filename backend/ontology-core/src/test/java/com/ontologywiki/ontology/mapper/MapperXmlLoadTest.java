package com.ontologywiki.ontology.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.InputStream;

/**
 * Mapper XML 加载校验。
 *
 * <p>本次重构把 core 的原生 SQL 全部下沉到 MyBatis XML，
 * 如果 XML 写错或语句 id 与接口方法名不一致，只有应用启动后才会暴露。
 * 这里在不连接数据库的前提下，用 MyBatis 解析全部 XML 并逐个绑定接口方法，
 * 把这类问题提前到单元测试阶段。
 */
public class MapperXmlLoadTest {

    /** 需要校验的 Mapper 接口，新增 Mapper 时同步登记 */
    private static final Class<?>[] MAPPERS = {
            AnalysisMemoryMapper.class,
            LlmSettingsMapper.class,
            LlmWikiAnalysisMapper.class,
            LlmWikiFacadeMapper.class,
            McpMapper.class,
            OntologyAnalysisMapper.class,
            OntologyResourceMapper.class,
            WikiAssetMapper.class,
            WikiChatMapper.class
    };

    @Test
    public void everyMapperXmlParsesAndBindsToItsInterface() {
        Configuration configuration = new Configuration(new Environment("test",
                new JdbcTransactionFactory(),
                new DriverManagerDataSource("jdbc:mysql://localhost:3306/placeholder")));

        int parsed = 0;
        for (Class<?> mapper : MAPPERS) {
            String resource = "mapper/ontology/" + mapper.getSimpleName() + ".xml";
            try (InputStream in = Resources.getResourceAsStream(resource)) {
                // 解析 namespace 时会自动把接口注册进 MapperRegistry，无需再 addMapper
                new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
            } catch (Exception e) {
                Assert.fail("Mapper XML 解析失败: " + resource + " -> " + e.getMessage());
            }
            parsed++;
        }

        Assert.assertEquals(MAPPERS.length, parsed);
        // 语句 id 与接口方法名不一致时不会报错，只会调用失败，这里逐个显式校验
        for (Class<?> mapper : MAPPERS) {
            for (java.lang.reflect.Method method : mapper.getDeclaredMethods()) {
                if (method.isSynthetic()) continue;
                String statementId = mapper.getName() + "." + method.getName();
                Assert.assertTrue("缺少 Mapper 语句: " + statementId,
                        configuration.hasStatement(statementId, false));
            }
        }
    }
}

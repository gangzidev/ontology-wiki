package com.ontologywiki.query.support;

import com.ontologywiki.query.mapper.QueryConfigurationMapper;
import com.ontologywiki.query.mapper.QueryEvaluationMapper;
import com.ontologywiki.query.mapper.QueryHistoryMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import javax.sql.DataSource;

public class QueryMapperTestSupport {
    public static final Class<?>[] MAPPERS = { QueryConfigurationMapper.class, QueryEvaluationMapper.class, QueryHistoryMapper.class };
    private final DataSource dataSource;
    private final SqlSessionTemplate sessions;

    public QueryMapperTestSupport(DataSource dataSource) throws Exception {
        this.dataSource = dataSource;
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        Resource[] locations = new Resource[MAPPERS.length];
        for (int index = 0; index < MAPPERS.length; index++) locations[index] = new ClassPathResource("mapper/query/" + MAPPERS[index].getSimpleName() + ".xml");
        factory.setMapperLocations(locations);
        sessions = new SqlSessionTemplate(factory.getObject());
    }

    public <Mapper> Mapper mapper(Class<Mapper> type) { return sessions.getMapper(type); }
    public SqlSessionFactory factory() { return sessions.getSqlSessionFactory(); }

    public <Service> Service transactional(Class<Service> type, Object target) {
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource), new AnnotationTransactionAttributeSource()));
        return type.cast(proxy.getProxy());
    }
}

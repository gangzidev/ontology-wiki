package com.ontologywiki.ontology.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
@EnableConfigurationProperties(OntologyIntegrationProperties.class)
public class OntologyIntegrationConfiguration {
    @Bean
    public RestTemplate ontologyRestTemplate(OntologyIntegrationProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Math.max(properties.getZhiwen().getConnectTimeoutMs(), properties.getLlmWiki().getConnectTimeoutMs()));
        factory.setReadTimeout(Math.max(properties.getZhiwen().getReadTimeoutMs(), properties.getLlmWiki().getReadTimeoutMs()));
        return new RestTemplate(factory);
    }
}

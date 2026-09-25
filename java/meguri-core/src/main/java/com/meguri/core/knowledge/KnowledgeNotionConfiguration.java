package com.meguri.core.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.knowledge.notion.JdkNotionHttpPort;
import com.meguri.core.knowledge.notion.ConfiguredNotionSourceRegistry;
import com.meguri.core.knowledge.notion.NotionKnowledgeProperties;
import com.meguri.core.knowledge.notion.NotionSyncCoordinator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NotionKnowledgeProperties.class)
public class KnowledgeNotionConfiguration {

    @Bean
    @ConditionalOnMissingBean(NotionHttpPort.class)
    NotionHttpPort notionHttpPort(NotionKnowledgeProperties properties) {
        return new JdkNotionHttpPort(
                JdkNotionHttpPort.defaultClient(properties.getTimeout()),
                properties.getBaseUrl());
    }

    @Bean
    ConfiguredNotionSourceRegistry notionSourceRegistry(
            NotionKnowledgeProperties properties,
            NotionHttpPort http,
            ObjectMapper mapper) {
        return new ConfiguredNotionSourceRegistry(properties, http, mapper);
    }

    @Bean
    NotionSyncCoordinator notionSyncCoordinator(
            NotionKnowledgeProperties properties,
            ConfiguredNotionSourceRegistry source,
            ObjectProvider<KnowledgeIngestionService> ingestionService) {
        return new NotionSyncCoordinator(
                properties, source, ingestionService, Clock.systemUTC());
    }
}

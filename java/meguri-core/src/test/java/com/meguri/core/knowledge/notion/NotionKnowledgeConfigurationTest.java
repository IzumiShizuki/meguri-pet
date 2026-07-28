package com.meguri.core.knowledge.notion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.knowledge.KnowledgeNotionConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class NotionKnowledgeConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(KnowledgeNotionConfiguration.class)
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void defaultApplicationConfigImportsNotionEnvironmentSettings() {
        runner.withSystemProperties(
                        "MEGURI_NOTION_KNOWLEDGE_ENABLED=true",
                        "MEGURI_NOTION_PAGE_ALLOWLIST=page-1,page-2")
                .run(context -> {
                    NotionKnowledgeProperties properties =
                            context.getBean(NotionKnowledgeProperties.class);
                    assertThat(properties.isEnabled()).isTrue();
                    assertThat(properties.getAllowlist()).containsExactlyInAnyOrder(
                            "page-1", "page-2");
                });
    }

    @Test
    void applicationStartsWhenEnabledWithoutTokenAndCoordinatorFailsClosed() {
        runner.withPropertyValues(
                        "meguri.knowledge.notion.enabled=true",
                        "meguri.knowledge.notion.allowlist=page-1")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    NotionSyncCoordinator coordinator =
                            context.getBean(NotionSyncCoordinator.class);
                    assertThat(org.assertj.core.api.Assertions.catchThrowable(
                            coordinator::synchronizeNow))
                            .isInstanceOf(NotionSyncUnavailableException.class)
                            .hasMessage("Notion knowledge token is not configured");
                });
    }
}

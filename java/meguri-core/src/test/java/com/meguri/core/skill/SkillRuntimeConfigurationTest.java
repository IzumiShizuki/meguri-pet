package com.meguri.core.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.capability.CapabilityRuntimeFacade;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("External Skill runtime configuration")
class SkillRuntimeConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules())
            .withBean(CapabilityRuntimeFacade.class, () -> new CapabilityRuntimeFacade(12))
            .withUserConfiguration(SkillRuntimeConfiguration.class);

    @Test
    void should_keep_modelscope_source_disabled_by_default_while_local_catalog_remains_available() {
        runner.run(context -> {
            assertThat(context).hasNotFailed()
                    .doesNotHaveBean(ModelScopeSkillSourcePlugin.class)
                    .hasSingleBean(SkillManagementService.class)
                    .hasSingleBean(SkillDisclosureService.class);
            assertThat(context.getBean(SkillSourceRegistry.class).ids()).isEmpty();
            assertThat(context.getBean(SkillCatalogRepository.class))
                    .isInstanceOf(InMemorySkillCatalogRepository.class);
        });
    }

    @Test
    void should_register_modelscope_source_only_when_explicitly_enabled() {
        runner.withPropertyValues(
                        "meguri.skills.modelscope.enabled=true",
                        "meguri.skills.modelscope.base-url=https://modelscope.cn",
                        "meguri.skills.modelscope.bridge-url=http://127.0.0.1:8000",
                        "meguri.skills.modelscope.bridge-token=fixture-token")
                .run(context -> {
                    assertThat(context).hasNotFailed()
                            .hasSingleBean(ModelScopeSkillSourcePlugin.class);
                    assertThat(context.getBean(SkillSourceRegistry.class).ids())
                            .containsExactly("modelscope");
                });
    }

    @Test
    void should_fail_fast_when_enabled_without_an_authenticated_bridge() {
        runner.withPropertyValues("meguri.skills.modelscope.enabled=true")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasRootCauseMessage(
                                "ModelScope Skill source requires a non-empty internal bridge token"));
    }
}

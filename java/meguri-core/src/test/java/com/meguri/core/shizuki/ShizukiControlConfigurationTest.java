package com.meguri.core.shizuki;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.capability.CapabilityRuntimeFacade;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ShizukiControlConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestDependencies.class, ShizukiControlConfiguration.class);

    @Test
    void remainsDisabledUnlessExplicitlyEnabled() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(ShizukiControlClient.class);
            assertThat(context).doesNotHaveBean(ShizukiControlCapabilities.class);
        });
    }

    @Test
    void registersAllCapabilitiesWhenEnabled() {
        contextRunner
                .withPropertyValues(
                        "meguri.shizuki.enabled=true",
                        "meguri.shizuki.manifest-path=build/test-shizuki/control-api.json")
                .run(context -> {
                    assertThat(context).hasSingleBean(ShizukiControlClient.class);
                    assertThat(context).hasSingleBean(ShizukiControlCapabilities.class);
                    assertThat(context.getBean(ShizukiControlCapabilities.class).registrations())
                            .hasSize(19);
                });
    }

    @Test
    void rejectsUnboundedTimeoutConfiguration() {
        contextRunner
                .withPropertyValues(
                        "meguri.shizuki.enabled=true",
                        "meguri.shizuki.connect-timeout-ms=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void resolvesAnExplicitManifestPathWithoutReadingIt() {
        Path resolved = ShizukiControlConfiguration.resolveManifestPath(
                "build/test-shizuki/control-api.json");

        assertThat(resolved).isAbsolute();
        assertThat(resolved.toString()).endsWith(
                Path.of("build", "test-shizuki", "control-api.json").toString());
    }

    @Configuration(proxyBeanMethods = false)
    static class TestDependencies {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean(destroyMethod = "close")
        CapabilityRuntimeFacade capabilityRuntimeFacade() {
            return new CapabilityRuntimeFacade(32);
        }
    }
}

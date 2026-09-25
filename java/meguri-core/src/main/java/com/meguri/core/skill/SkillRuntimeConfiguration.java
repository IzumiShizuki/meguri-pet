package com.meguri.core.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.capability.CapabilityRuntimeFacade;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.nio.file.Files;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

@Configuration
public class SkillRuntimeConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "meguri.skills.modelscope", name = "enabled", havingValue = "true")
    ModelScopeSkillSourcePlugin modelScopeSkillSourcePlugin(
            ObjectMapper mapper,
            @Value("${meguri.skills.modelscope.base-url:https://modelscope.cn}") String baseUrl,
            @Value("${meguri.skills.modelscope.bridge-url:http://127.0.0.1:8000}") String bridgeUrl,
            @Value("${meguri.skills.modelscope.bridge-token-file:}") String tokenFile,
            @Value("${meguri.skills.modelscope.bridge-token:}") String inlineToken,
            @Value("${meguri.skills.modelscope.timeout-ms:15000}") long timeoutMs) {
        if (timeoutMs <= 0) throw new IllegalArgumentException("ModelScope timeout must be positive");
        String bridgeToken = readToken(tokenFile, inlineToken);
        if (bridgeToken.isBlank()) {
            throw new IllegalStateException(
                    "ModelScope Skill source requires a non-empty internal bridge token");
        }
        return new ModelScopeSkillSourcePlugin(
                HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMs))
                        .followRedirects(HttpClient.Redirect.NEVER).build(),
                mapper, URI.create(baseUrl), URI.create(bridgeUrl),
                bridgeToken, Duration.ofMillis(timeoutMs));
    }

    @Bean @ConditionalOnMissingBean
    SkillSourceRegistry skillSourceRegistry(List<SkillSourcePlugin> plugins) { return new SkillSourceRegistry(plugins); }

    @Bean @ConditionalOnMissingBean
    SkillCatalogRepository skillCatalogRepository(
            ObjectProvider<JdbcTemplate> jdbcProvider, ObjectMapper mapper,
            @Value("${meguri.skills.store-mode:memory}") String mode) {
        if (!"postgres".equalsIgnoreCase(mode)) return new InMemorySkillCatalogRepository();
        JdbcTemplate jdbc = jdbcProvider.getIfAvailable();
        if (jdbc == null) throw new IllegalStateException("PostgreSQL Skill store requires JdbcTemplate");
        return new JdbcSkillCatalogRepository(jdbc, mapper);
    }

    @Bean @ConditionalOnMissingBean
    SkillPackageStore skillPackageStore(@Value("${meguri.skills.data-root:${user.home}/.meguri}") String dataRoot) {
        return new SkillPackageStore(Path.of(dataRoot));
    }

    @Bean @ConditionalOnMissingBean
    SkillPackageValidator skillPackageValidator(CapabilityRuntimeFacade runtime) {
        return new SkillPackageValidator(
                capabilityId -> runtime.availableDescriptors().stream()
                        .anyMatch(descriptor -> descriptor.id().equals(capabilityId)),
                name -> {
            String value = System.getenv(name);
            return value != null && !value.isBlank();
        });
    }

    @Bean @ConditionalOnMissingBean
    SkillManagementService skillManagementService(
            SkillSourceRegistry sources, SkillCatalogRepository repository,
            SkillPackageValidator validator, SkillPackageStore packages) {
        return new SkillManagementService(sources, repository, validator, packages);
    }

    @Bean @ConditionalOnMissingBean
    SkillSelectionService skillSelectionService(SkillCatalogRepository repository) {
        return new SkillSelectionService(repository);
    }

    @Bean @ConditionalOnMissingBean
    SkillDisclosureService skillDisclosureService(SkillManagementService management) {
        return new SkillDisclosureService(management);
    }

    private static String readToken(String tokenFile, String inlineToken) {
        String configured = tokenFile == null ? "" : tokenFile.trim();
        if (!configured.isBlank()) {
            try {
                Path path = Path.of(configured);
                if (!path.isAbsolute() || !Files.isRegularFile(path)) {
                    throw new IllegalStateException("ModelScope bridge token file is unavailable");
                }
                String value = Files.readString(path).trim();
                if (!value.isBlank()) return value;
            } catch (java.io.IOException error) {
                throw new IllegalStateException("ModelScope bridge token file is unreadable", error);
            }
        }
        return inlineToken == null ? "" : inlineToken.trim();
    }
}

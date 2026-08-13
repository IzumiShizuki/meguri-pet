package com.meguri.core.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Optional public ModelScope acceptance")
class ModelScopeNetworkAcceptanceTest {
    @TempDir Path temporary;

    @Test
    void should_download_validate_and_import_public_minimax_pdf_when_explicitly_enabled() {
        Assumptions.assumeTrue("true".equalsIgnoreCase(
                System.getenv("MEGURI_MODELSCOPE_NETWORK_E2E")));
        String bridgeUrl = value("MEGURI_MODELSCOPE_SKILL_BRIDGE_URL", "http://127.0.0.1:8000");
        String bridgeToken = value("MEGURI_INTERNAL_BRIDGE_TOKEN", "");
        Assumptions.assumeTrue(!bridgeToken.isBlank(),
                "MEGURI_INTERNAL_BRIDGE_TOKEN is required for the network acceptance");
        ModelScopeSkillSourcePlugin source = new ModelScopeSkillSourcePlugin(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                new ObjectMapper().findAndRegisterModules(), URI.create("https://modelscope.cn"),
                URI.create(bridgeUrl), bridgeToken, Duration.ofSeconds(30));
        SkillManagementService management = new SkillManagementService(
                new SkillSourceRegistry(List.of(source)), new InMemorySkillCatalogRepository(),
                new SkillPackageValidator(Set.of(), name -> false),
                new SkillPackageStore(temporary.resolve("data")));

        SkillCatalogEntry imported = management.importSkill(
                "modelscope", "@MiniMax-AI/minimax-pdf", "network-acceptance");

        assertThat(imported.revisions()).singleElement().satisfies(revision -> {
            assertThat(revision.report().state()).isEqualTo(SkillValidationReport.ValidationState.VALID);
            assertThat(revision.digest()).matches("[0-9a-f]{64}");
        });
    }

    private static String value(String name, String fallback) {
        String configured = System.getenv(name);
        return configured == null || configured.isBlank() ? fallback : configured.trim();
    }
}

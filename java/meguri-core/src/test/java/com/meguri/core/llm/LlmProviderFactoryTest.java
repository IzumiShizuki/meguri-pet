package com.meguri.core.llm;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmProviderFactoryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void plannerReusesOuterCredentialWhenNoPlannerSecretFileIsConfigured() {
        assertEquals("outer-secret",
                LlmProviderFactory.resolvePlannerApiKey("", "outer-secret"));
        assertEquals("outer-secret",
                LlmProviderFactory.resolvePlannerApiKey(null, "outer-secret"));
    }

    @Test
    void multimodalFallbackReusesOuterCredentialWhenNoFallbackSecretFileIsConfigured() {
        assertEquals("outer-secret",
                LlmProviderFactory.resolveFallbackApiKey("", "outer-secret"));
        assertEquals("outer-secret",
                LlmProviderFactory.resolveFallbackApiKey(null, "outer-secret"));
    }

    @Test
    void multimodalFallbackUsesAnExplicitSecretFileWhenConfigured() throws Exception {
        Path fallbackSecret = temporaryDirectory.resolve("fallback-api-key.txt");
        Files.writeString(fallbackSecret, "fallback-secret\n");

        assertEquals("fallback-secret", LlmProviderFactory.resolveFallbackApiKey(
                fallbackSecret.toAbsolutePath().toString(), "outer-secret"));
    }

    @Test
    void plannerUsesItsOwnCredentialWhenASecretFileIsConfigured() throws Exception {
        Path plannerSecret = temporaryDirectory.resolve("planner-api-key.txt");
        Files.writeString(plannerSecret, "planner-secret\n");

        assertEquals("planner-secret", LlmProviderFactory.resolvePlannerApiKey(
                plannerSecret.toAbsolutePath().toString(), "outer-secret"));
    }

    @Test
    void plannerRejectsARepositoryRelativeSecretFile() {
        LlmConfigurationException error = assertThrows(LlmConfigurationException.class,
                () -> LlmProviderFactory.resolvePlannerApiKey("planner-api-key.txt", "outer-secret"));

        assertTrue(error.getMessage().contains("MEGURI_AGENT_PLANNER_API_KEY_FILE"));
    }
}

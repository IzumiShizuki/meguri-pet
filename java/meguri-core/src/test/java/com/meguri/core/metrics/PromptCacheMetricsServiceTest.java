package com.meguri.core.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PromptCacheMetricsServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void persistsOnlyNumericTelemetryAndRestoresTotals() throws Exception {
        Path metricsFile = tempDir.resolve("prompt-cache.json");
        Clock clock = Clock.fixed(Instant.parse("2026-07-22T08:00:00Z"), ZoneOffset.UTC);
        PromptCacheMetricsService service = new PromptCacheMetricsService(
            new ObjectMapper(), metricsFile, "test-desktop", clock);

        service.registerPrompt("openai-compatible/langchain4j", "deepseek-chat", "secret persona text");
        service.recordSuccess("turn", "deepseek-chat",
            new PromptCacheUsage(true, true, 100L, 20L, 120L, 75L, 25L));
        service.recordFailure("conversation_boundary", "deepseek-chat");

        PromptCacheMetricsSnapshot snapshot = service.snapshot();
        assertEquals(2L, snapshot.totalRequests());
        assertEquals(1L, snapshot.successfulRequests());
        assertEquals(1L, snapshot.failedRequests());
        assertEquals(75L, snapshot.cacheHitTokens());
        assertEquals(0.75, snapshot.cacheHitRate());
        assertEquals(2, snapshot.recent().size());
        String persisted = Files.readString(metricsFile);
        assertFalse(persisted.contains("secret persona text"));
        assertTrue(persisted.contains("\"source_id\""));

        PromptCacheMetricsService restored = new PromptCacheMetricsService(
            new ObjectMapper(), metricsFile, "test-desktop", clock);
        assertEquals(2L, restored.snapshot().totalRequests());
        assertTrue(restored.snapshot().promptSha256().matches("[0-9a-f]{64}"));
    }
}

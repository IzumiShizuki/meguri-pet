package com.meguri.core.metrics;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;

/** Persisted, content-free prompt-cache telemetry exported to the admin dashboard. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record PromptCacheMetricsSnapshot(
        String sourceId,
        String observedAt,
        String collectingSince,
        String provider,
        String model,
        String cacheMode,
        String promptSha256,
        long promptCharacters,
        long totalRequests,
        long successfulRequests,
        long failedRequests,
        long usageReportedRequests,
        long cacheReportedRequests,
        long promptTokens,
        long outputTokens,
        long totalTokens,
        long cacheHitTokens,
        long cacheMissTokens,
        Double cacheHitRate,
        Double usageCoverageRate,
        String persistenceStatus,
        String lastPersistenceError,
        String exportStatus,
        String lastExportAt,
        String lastExportError,
        List<Daily> daily,
        List<Sample> recent) {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Daily(
            String date,
            long requests,
            long successfulRequests,
            long failedRequests,
            long promptTokens,
            long outputTokens,
            long cacheHitTokens,
            long cacheMissTokens) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Sample(
            String observedAt,
            String operation,
            String model,
            boolean successful,
            boolean usageReported,
            boolean cacheReported,
            long promptTokens,
            long outputTokens,
            long totalTokens,
            long cacheHitTokens,
            long cacheMissTokens) {}
}

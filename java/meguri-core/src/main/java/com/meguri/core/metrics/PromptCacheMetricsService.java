package com.meguri.core.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Owns numeric prompt-cache telemetry, bounded history, and atomic local persistence. */
@Service
public class PromptCacheMetricsService implements PromptCacheMetricsRecorder {
    private static final int MAX_RECENT = 100;
    private static final int MAX_DAYS = 31;

    private final ObjectMapper mapper;
    private final Path metricsFile;
    private final Clock clock;
    private final String sourceId;
    private final Deque<PromptCacheMetricsSnapshot.Sample> recent = new ArrayDeque<>();
    private final LinkedHashMap<String, MutableDaily> daily = new LinkedHashMap<>();

    private String collectingSince;
    private String provider = "unconfigured";
    private String model = "";
    private String promptSha256 = "";
    private long promptCharacters;
    private long totalRequests;
    private long successfulRequests;
    private long failedRequests;
    private long usageReportedRequests;
    private long cacheReportedRequests;
    private long promptTokens;
    private long outputTokens;
    private long totalTokens;
    private long cacheHitTokens;
    private long cacheMissTokens;
    private String persistenceStatus = "ready";
    private String lastPersistenceError = "";
    private String exportStatus = "disabled";
    private String lastExportAt = "";
    private String lastExportError = "";

    @Autowired
    public PromptCacheMetricsService(
            ObjectMapper mapper,
            @Value("${meguri.prompt-cache-metrics.file:${user.home}/.meguri/metrics/prompt-cache.json}") String metricsFile,
            @Value("${meguri.prompt-cache-metrics.source-id:meguri-desktop}") String sourceId) {
        this(mapper, Path.of(metricsFile), sourceId, Clock.systemUTC());
    }

    PromptCacheMetricsService(ObjectMapper mapper, Path metricsFile, String sourceId, Clock clock) {
        this.mapper = mapper.copy();
        this.metricsFile = metricsFile.toAbsolutePath().normalize();
        this.sourceId = sourceId == null || sourceId.isBlank() ? "meguri-desktop" : sourceId.trim();
        this.clock = clock.withZone(ZoneOffset.UTC);
        this.collectingSince = now();
        load();
    }

    @Override
    public synchronized void registerPrompt(String provider, String model, String prompt) {
        this.provider = text(provider, "openai-compatible/langchain4j");
        this.model = text(model, "");
        this.promptCharacters = prompt == null ? 0L : prompt.length();
        this.promptSha256 = sha256(prompt == null ? "" : prompt);
        persist();
    }

    @Override
    public synchronized void recordSuccess(String operation, String model, PromptCacheUsage usage) {
        PromptCacheUsage safe = usage == null ? PromptCacheUsage.unavailable() : usage;
        String observedAt = now();
        totalRequests++;
        successfulRequests++;
        this.model = text(model, this.model);
        if (safe.usageReported()) {
            usageReportedRequests++;
            promptTokens += safe.promptTokens();
            outputTokens += safe.outputTokens();
            totalTokens += safe.totalTokens();
        }
        if (safe.cacheReported()) {
            cacheReportedRequests++;
            cacheHitTokens += safe.cacheHitTokens();
            cacheMissTokens += safe.cacheMissTokens();
        }

        MutableDaily day = day(observedAt);
        day.requests++;
        day.successfulRequests++;
        day.promptTokens += safe.promptTokens();
        day.outputTokens += safe.outputTokens();
        day.cacheHitTokens += safe.cacheHitTokens();
        day.cacheMissTokens += safe.cacheMissTokens();
        appendRecent(new PromptCacheMetricsSnapshot.Sample(
                observedAt, text(operation, "unknown"), this.model, true,
                safe.usageReported(), safe.cacheReported(), safe.promptTokens(), safe.outputTokens(),
                safe.totalTokens(), safe.cacheHitTokens(), safe.cacheMissTokens()));
        persist();
    }

    @Override
    public synchronized void recordFailure(String operation, String model) {
        String observedAt = now();
        totalRequests++;
        failedRequests++;
        this.model = text(model, this.model);
        MutableDaily day = day(observedAt);
        day.requests++;
        day.failedRequests++;
        appendRecent(new PromptCacheMetricsSnapshot.Sample(
                observedAt, text(operation, "unknown"), this.model, false,
                false, false, 0L, 0L, 0L, 0L, 0L));
        persist();
    }

    public synchronized PromptCacheMetricsSnapshot snapshot() {
        long knownCacheTokens = cacheHitTokens + cacheMissTokens;
        Double hitRate = knownCacheTokens == 0L ? null : cacheHitTokens / (double) knownCacheTokens;
        Double coverage = totalRequests == 0L ? null : usageReportedRequests / (double) totalRequests;
        List<PromptCacheMetricsSnapshot.Daily> dailySnapshots = daily.entrySet().stream()
                .map(entry -> entry.getValue().snapshot(entry.getKey()))
                .toList();
        return new PromptCacheMetricsSnapshot(
                sourceId, now(), collectingSince, provider, model, "provider_managed_prefix_cache",
                promptSha256, promptCharacters, totalRequests, successfulRequests, failedRequests,
                usageReportedRequests, cacheReportedRequests, promptTokens, outputTokens, totalTokens,
                cacheHitTokens, cacheMissTokens, hitRate, coverage, persistenceStatus,
                lastPersistenceError, exportStatus, lastExportAt, lastExportError,
                dailySnapshots, List.copyOf(recent));
    }

    public synchronized void configureExport(boolean enabled) {
        exportStatus = enabled ? "pending" : "disabled";
    }

    public synchronized void recordExportSuccess() {
        exportStatus = "ok";
        lastExportAt = now();
        lastExportError = "";
    }

    public synchronized void recordExportFailure(String message) {
        exportStatus = "error";
        lastExportError = bounded(message, 220);
    }

    private void load() {
        if (!Files.isRegularFile(metricsFile)) return;
        try {
            PromptCacheMetricsSnapshot loaded = mapper.readValue(metricsFile.toFile(), PromptCacheMetricsSnapshot.class);
            if (loaded == null || !sourceId.equals(loaded.sourceId())) return;
            collectingSince = text(loaded.collectingSince(), collectingSince);
            provider = text(loaded.provider(), provider);
            model = text(loaded.model(), model);
            promptSha256 = text(loaded.promptSha256(), promptSha256);
            promptCharacters = Math.max(0L, loaded.promptCharacters());
            totalRequests = Math.max(0L, loaded.totalRequests());
            successfulRequests = Math.max(0L, loaded.successfulRequests());
            failedRequests = Math.max(0L, loaded.failedRequests());
            usageReportedRequests = Math.max(0L, loaded.usageReportedRequests());
            cacheReportedRequests = Math.max(0L, loaded.cacheReportedRequests());
            promptTokens = Math.max(0L, loaded.promptTokens());
            outputTokens = Math.max(0L, loaded.outputTokens());
            totalTokens = Math.max(0L, loaded.totalTokens());
            cacheHitTokens = Math.max(0L, loaded.cacheHitTokens());
            cacheMissTokens = Math.max(0L, loaded.cacheMissTokens());
            if (loaded.daily() != null) {
                for (PromptCacheMetricsSnapshot.Daily item : loaded.daily()) {
                    if (item == null || item.date() == null || item.date().isBlank()) continue;
                    daily.put(item.date(), MutableDaily.from(item));
                }
            }
            if (loaded.recent() != null) loaded.recent().forEach(this::appendRecent);
            persistenceStatus = "ready";
            lastPersistenceError = "";
        } catch (Exception ex) {
            persistenceStatus = "error";
            lastPersistenceError = bounded(ex.getMessage(), 220);
        }
    }

    private void persist() {
        try {
            Files.createDirectories(metricsFile.getParent());
            Path temporary = metricsFile.resolveSibling(metricsFile.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), snapshot());
            try {
                Files.move(temporary, metricsFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, metricsFile, StandardCopyOption.REPLACE_EXISTING);
            }
            persistenceStatus = "ready";
            lastPersistenceError = "";
        } catch (IOException ex) {
            persistenceStatus = "error";
            lastPersistenceError = bounded(ex.getMessage(), 220);
        }
    }

    private MutableDaily day(String observedAt) {
        String date = observedAt.length() >= 10 ? observedAt.substring(0, 10) : LocalDate.now(clock).toString();
        MutableDaily value = daily.computeIfAbsent(date, ignored -> new MutableDaily());
        while (daily.size() > MAX_DAYS) {
            String first = daily.keySet().iterator().next();
            daily.remove(first);
        }
        return value;
    }

    private void appendRecent(PromptCacheMetricsSnapshot.Sample sample) {
        if (sample == null) return;
        recent.addLast(sample);
        while (recent.size() > MAX_RECENT) recent.removeFirst();
    }

    private String now() {
        return Instant.now(clock).toString();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String bounded(String value, int limit) {
        String normalized = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit);
    }

    private static final class MutableDaily {
        private long requests;
        private long successfulRequests;
        private long failedRequests;
        private long promptTokens;
        private long outputTokens;
        private long cacheHitTokens;
        private long cacheMissTokens;

        private PromptCacheMetricsSnapshot.Daily snapshot(String date) {
            return new PromptCacheMetricsSnapshot.Daily(date, requests, successfulRequests, failedRequests,
                    promptTokens, outputTokens, cacheHitTokens, cacheMissTokens);
        }

        private static MutableDaily from(PromptCacheMetricsSnapshot.Daily source) {
            MutableDaily result = new MutableDaily();
            result.requests = Math.max(0L, source.requests());
            result.successfulRequests = Math.max(0L, source.successfulRequests());
            result.failedRequests = Math.max(0L, source.failedRequests());
            result.promptTokens = Math.max(0L, source.promptTokens());
            result.outputTokens = Math.max(0L, source.outputTokens());
            result.cacheHitTokens = Math.max(0L, source.cacheHitTokens());
            result.cacheMissTokens = Math.max(0L, source.cacheMissTokens());
            return result;
        }
    }
}

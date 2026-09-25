package com.meguri.core.metrics;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/** Periodically exports content-free snapshots to the shizuki-site internal ingest endpoint. */
@Component
public class PromptCacheMetricsPublisher {
    private static final Logger LOGGER = LoggerFactory.getLogger(PromptCacheMetricsPublisher.class);

    private final PromptCacheMetricsService metrics;
    private final WebClient client;
    private final String exportUrl;
    private final Path tokenFile;
    private final Duration timeout;
    private final boolean enabled;

    public PromptCacheMetricsPublisher(
            PromptCacheMetricsService metrics,
            @Value("${meguri.prompt-cache-metrics.export-url:}") String exportUrl,
            @Value("${meguri.prompt-cache-metrics.export-token-file:}") String tokenFile,
            @Value("${meguri.prompt-cache-metrics.export-timeout-ms:5000}") long timeoutMs) {
        this.metrics = metrics;
        this.exportUrl = exportUrl == null ? "" : exportUrl.trim();
        this.tokenFile = tokenFile == null || tokenFile.isBlank() ? null : Path.of(tokenFile).toAbsolutePath().normalize();
        this.timeout = Duration.ofMillis(Math.max(500L, timeoutMs));
        this.enabled = isSafeUrl(this.exportUrl) && this.tokenFile != null;
        this.client = WebClient.builder().build();
    }

    @PostConstruct
    void configure() {
        metrics.configureExport(enabled);
    }

    @Scheduled(fixedDelayString = "${meguri.prompt-cache-metrics.export-interval-ms:60000}")
    public void publish() {
        if (!enabled) return;
        try {
            String token = Files.readString(tokenFile).trim();
            if (token.isBlank() || token.length() > 4096) {
                throw new IllegalStateException("metrics export token file is empty or unexpectedly large");
            }
            client.post()
                    .uri(exportUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Meguri-Metrics-Token", token)
                    .bodyValue(metrics.snapshot())
                    .retrieve()
                    .toBodilessEntity()
                    .block(timeout);
            metrics.recordExportSuccess();
        } catch (Exception ex) {
            String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            metrics.recordExportFailure(message);
            LOGGER.warn("Prompt-cache metrics export failed: {}", sanitize(message));
        }
    }

    private static boolean isSafeUrl(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            URI uri = URI.create(value);
            if ("https".equalsIgnoreCase(uri.getScheme())) return uri.getHost() != null;
            return "http".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null
                    && ("127.0.0.1".equals(uri.getHost()) || "localhost".equalsIgnoreCase(uri.getHost())
                    || "::1".equals(uri.getHost()));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static String sanitize(String value) {
        String text = value == null ? "unknown" : value.replace('\n', ' ').replace('\r', ' ').trim();
        return text.length() <= 220 ? text : text.substring(0, 220);
    }
}

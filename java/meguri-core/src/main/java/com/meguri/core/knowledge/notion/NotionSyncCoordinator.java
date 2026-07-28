package com.meguri.core.knowledge.notion;

import com.meguri.core.knowledge.KnowledgeIngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

public final class NotionSyncCoordinator {
    private static final Logger log = LoggerFactory.getLogger(NotionSyncCoordinator.class);

    private final NotionKnowledgeProperties properties;
    private final ConfiguredNotionSourceRegistry source;
    private final Supplier<KnowledgeIngestionService> ingestionService;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean();

    public NotionSyncCoordinator(
            NotionKnowledgeProperties properties,
            ConfiguredNotionSourceRegistry source,
            ObjectProvider<KnowledgeIngestionService> ingestionService,
            Clock clock) {
        this(properties, source, ingestionService::getIfAvailable, clock);
    }

    NotionSyncCoordinator(
            NotionKnowledgeProperties properties,
            ConfiguredNotionSourceRegistry source,
            Supplier<KnowledgeIngestionService> ingestionService,
            Clock clock) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.source = Objects.requireNonNull(source, "source");
        this.ingestionService = Objects.requireNonNull(ingestionService, "ingestionService");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public NotionSyncReport synchronizeNow() {
        source.requireReady();
        KnowledgeIngestionService service = ingestionService.get();
        if (service == null) {
            throw new NotionSyncUnavailableException(
                    "Knowledge ingestion runtime is not configured");
        }
        if (!running.compareAndSet(false, true)) {
            throw new NotionSyncUnavailableException("Notion knowledge synchronization is already running");
        }
        try {
            var results = service.synchronize();
            NotionSyncReport report = NotionSyncReport.from(
                    properties.getSourceId(), clock.instant(), results);
            log.info("Notion knowledge sync completed: source={}, pages={}",
                    report.sourceId(), report.pages());
            return report;
        } finally {
            running.set(false);
        }
    }

    @Scheduled(
            initialDelayString = "${meguri.knowledge.notion.initial-delay-ms:60000}",
            fixedDelayString = "${meguri.knowledge.notion.sync-delay-ms:900000}")
    public void scheduledSynchronize() {
        if (!properties.isEnabled()) return;
        try {
            synchronizeNow();
        } catch (NotionSyncUnavailableException error) {
            log.warn("Notion knowledge sync skipped: {}", error.getMessage());
        } catch (RuntimeException error) {
            log.error("Notion knowledge sync failed: type={}", error.getClass().getSimpleName());
        }
    }

}

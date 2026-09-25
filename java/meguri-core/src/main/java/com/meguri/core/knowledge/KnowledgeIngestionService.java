package com.meguri.core.knowledge;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Application service that keeps BUILDING work invisible until atomic publication. */
public final class KnowledgeIngestionService {
    private final KnowledgeRepository repository;
    private final List<SourceRegistry> sources;
    private final KnowledgeProjector projector;
    private final KnowledgeEvidenceValidator validator;
    private final SecretDetector secretDetector;
    private final Clock clock;

    public KnowledgeIngestionService(
            KnowledgeRepository repository,
            List<SourceRegistry> sources,
            KnowledgeProjector projector,
            KnowledgeEvidenceValidator validator,
            SecretDetector secretDetector,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
        this.projector = Objects.requireNonNull(projector, "projector");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.secretDetector = Objects.requireNonNull(secretDetector, "secretDetector");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public List<IngestionResult> synchronize() {
        ArrayList<SourcePage> pages = new ArrayList<>();
        Instant now = clock.instant();
        for (SourceRegistry source : sources) {
            List<SourcePage> snapshot = source.scan();
            pages.addAll(snapshot);
            var observedPageIds = snapshot.stream()
                    .map(SourcePage::pageId)
                    .collect(java.util.stream.Collectors.toSet());
            repository.documents().stream()
                    .filter(document -> document.sourceId().equals(source.sourceId()))
                    .filter(document -> document.status() != KnowledgeStatus.DELETED)
                    .filter(document -> !observedPageIds.contains(document.pageId()))
                    .map(document -> new SourcePage(
                            source.sourceId(), document.pageId(), document.title(), "",
                            "0".repeat(64), now, document.acl(), true, false,
                            document.metadata()))
                    .forEach(pages::add);
        }
        pages.sort(Comparator.comparing(SourcePage::sourceId).thenComparing(SourcePage::pageId));
        return pages.stream().map(this::ingest).toList();
    }

    public IngestionResult ingest(SourcePage page) {
        Instant now = clock.instant();
        if (page.tombstone()) {
            repository.tombstone(page.sourceId(), page.pageId(), now);
            return result(page, IngestionOutcome.TOMBSTONED, null, null, "source tombstone");
        }
        if (secretDetector.containsSecret(page)) {
            return result(page, IngestionOutcome.REJECTED, null, null,
                    "secret-like content blocked before persistence");
        }

        BuildStart start = repository.beginBuild(page, now);
        if (start.skipped()) {
            return result(page, IngestionOutcome.SKIPPED,
                    start.document().id(), start.version().id(), "content hash unchanged");
        }
        try {
            KnowledgeBuild build = projector.project(start.document(), start.version(), page);
            validator.validate(start.document(), start.version(), build);
            repository.publish(start.version().id(), build, now);
            return result(page, IngestionOutcome.PUBLISHED,
                    start.document().id(), start.version().id(), "version published");
        } catch (RuntimeException error) {
            repository.failBuild(start.version().id(), safeMessage(error), now);
            return result(page, IngestionOutcome.FAILED,
                    start.document().id(), start.version().id(), safeMessage(error));
        }
    }

    private static IngestionResult result(SourcePage page, IngestionOutcome outcome,
                                          String documentId, String versionId, String detail) {
        return new IngestionResult(
                page.sourceId(), page.pageId(), outcome, documentId, versionId, detail);
    }

    private static String safeMessage(RuntimeException error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}

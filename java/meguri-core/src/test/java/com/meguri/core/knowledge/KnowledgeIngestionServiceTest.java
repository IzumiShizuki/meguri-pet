package com.meguri.core.knowledge;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeIngestionServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-28T08:00:00Z");
    private static final KnowledgeAcl ACL = new KnowledgeAcl("meguri", Set.of("user:izumi"));

    @Test
    void publishesCompleteVersionAndSkipsUnchangedHash() {
        InMemoryKnowledgeRepository repository = new InMemoryKnowledgeRepository();
        NotionSourceAdapter source = source(page("page-1", content("first")));
        KnowledgeIngestionService service = service(repository, source, new DeterministicKnowledgeProjector());

        List<IngestionResult> first = service.synchronize();
        List<IngestionResult> second = service.synchronize();

        assertThat(first).extracting(IngestionResult::outcome)
                .containsExactly(IngestionOutcome.PUBLISHED);
        assertThat(second).extracting(IngestionResult::outcome)
                .containsExactly(IngestionOutcome.SKIPPED);
        KnowledgeDocument document = repository.findDocument("notion", "page-1").orElseThrow();
        KnowledgeDocumentVersion active = repository.findActiveVersion(document.id()).orElseThrow();
        assertThat(document.status()).isEqualTo(KnowledgeStatus.ACTIVE);
        assertThat(document.metadata().sourceUri()).isEqualTo("notion://page-1");
        assertThat(document.metadata().canonicalUri()).isEqualTo("notion://page-1");
        assertThat(active.status()).isEqualTo(KnowledgeStatus.ACTIVE);
        assertThat(active.parserRevision()).isEqualTo("meguri-source-v1");
        assertThat(active.chunkerRevision()).isEqualTo("meguri-parent-child-v1");
        assertThat(active.embeddingRevision())
                .isEqualTo(DeterministicSearchProjector.EMBEDDING_MODEL);
        assertThat(repository.versions(document.id())).hasSize(1);
        assertThat(repository.chunks(active.id()))
                .extracting(KnowledgeChunk::role)
                .contains(ChunkRole.PARENT, ChunkRole.CHILD);
        assertThat(repository.chunks(active.id())).allSatisfy(chunk -> {
            assertThat(chunk.metadata().contentHash()).hasSize(64);
            assertThat(chunk.metadata().tokenCount()).isPositive();
            assertThat(chunk.metadata().sourceStart()).isNotNull();
            assertThat(chunk.metadata().sourceEnd()).isNotNull();
        });
        assertThat(repository.entities(active.id())).hasSize(2);
        assertThat(repository.relations(active.id())).hasSize(1);
    }

    @Test
    void preservesHierarchicalSectionPathsAndExactSourceAnchors() {
        InMemoryKnowledgeRepository repository = new InMemoryKnowledgeRepository();
        String pageContent = """
                # Root

                Intro paragraph.

                ## Child

                Child evidence.
                """;
        SourcePage page = new SourcePage(
                "notion", "anchored", "Anchored", pageContent,
                "a".repeat(64), NOW, ACL, false, false);
        KnowledgeIngestionService service = new KnowledgeIngestionService(
                repository, List.of(), new DeterministicKnowledgeProjector(),
                new KnowledgeEvidenceValidator(), new SecretDetector(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        IngestionResult result = service.ingest(page);

        KnowledgeDocument document = repository
                .findDocument("notion", "anchored").orElseThrow();
        KnowledgeDocumentVersion version = repository
                .findActiveVersion(document.id()).orElseThrow();
        List<KnowledgeChunk> children = repository.chunks(version.id()).stream()
                .filter(chunk -> chunk.role() == ChunkRole.CHILD)
                .toList();
        List<KnowledgeChunk> parents = repository.chunks(version.id()).stream()
                .filter(chunk -> chunk.role() == ChunkRole.PARENT)
                .toList();
        assertThat(result.outcome()).isEqualTo(IngestionOutcome.PUBLISHED);
        assertThat(parents).extracting(KnowledgeChunk::content)
                .containsExactly(
                        "# Root\n\nIntro paragraph.",
                        "## Child\n\nChild evidence.");
        assertThat(children).extracting(chunk -> chunk.metadata().sectionPath())
                .containsExactly(
                        List.of("Root"),
                        List.of("Root"),
                        List.of("Root", "Child"),
                        List.of("Root", "Child"));
        assertThat(children.subList(0, 2))
                .allMatch(chunk -> chunk.parentChunkId().equals(parents.getFirst().id()));
        assertThat(children.subList(2, 4))
                .allMatch(chunk -> chunk.parentChunkId().equals(parents.getLast().id()));
        assertThat(children).allSatisfy(chunk -> {
            int start = chunk.metadata().sourceStart();
            int end = chunk.metadata().sourceEnd();
            assertThat(pageContent.strip().substring(start, end))
                    .isEqualTo(chunk.content());
        });
    }

    @Test
    void failedBuildKeepsPreviousActiveVersion() {
        InMemoryKnowledgeRepository repository = new InMemoryKnowledgeRepository();
        NotionSourceAdapter source = source(page("page-1", content("stable")));
        KnowledgeIngestionService service = service(repository, source, new DeterministicKnowledgeProjector());
        service.synchronize();
        String activeId = repository.findDocument("notion", "page-1")
                .flatMap(document -> repository.findActiveVersion(document.id()))
                .orElseThrow().id();

        source.put(page("page-1", content("broken")));
        KnowledgeProjector failing = (document, version, page) -> {
            throw new IllegalStateException("injected projection failure");
        };
        List<IngestionResult> results = service(repository, source, failing).synchronize();

        KnowledgeDocument document = repository.findDocument("notion", "page-1").orElseThrow();
        assertThat(results.getFirst().outcome()).isEqualTo(IngestionOutcome.FAILED);
        assertThat(repository.findActiveVersion(document.id())).get()
                .extracting(KnowledgeDocumentVersion::id).isEqualTo(activeId);
        assertThat(repository.versions(document.id()))
                .extracting(KnowledgeDocumentVersion::status)
                .containsExactlyInAnyOrder(KnowledgeStatus.ACTIVE, KnowledgeStatus.FAILED);
    }

    @Test
    void atomicallySwitchesActiveVersionAndSupersedesOldOne() {
        InMemoryKnowledgeRepository repository = new InMemoryKnowledgeRepository();
        NotionSourceAdapter source = source(page("page-1", content("v1")));
        KnowledgeIngestionService service = service(repository, source, new DeterministicKnowledgeProjector());
        service.synchronize();
        KnowledgeDocument document = repository.findDocument("notion", "page-1").orElseThrow();
        String firstId = repository.findActiveVersion(document.id()).orElseThrow().id();

        source.put(new NotionPage("page-1", "Meguri", content("v2"),
                NOW.plusSeconds(60), false, false));
        service.synchronize();

        KnowledgeDocumentVersion active = repository.findActiveVersion(document.id()).orElseThrow();
        assertThat(active.id()).isNotEqualTo(firstId);
        assertThat(repository.versions(document.id()))
                .filteredOn(version -> version.id().equals(firstId))
                .extracting(KnowledgeDocumentVersion::status)
                .containsExactly(KnowledgeStatus.SUPERSEDED);
        assertThat(repository.versions(document.id()))
                .filteredOn(version -> version.status() == KnowledgeStatus.ACTIVE)
                .hasSize(1);
    }

    @Test
    void rejectsGraphWithMismatchedAclOrMissingEvidence() {
        InMemoryKnowledgeRepository repository = new InMemoryKnowledgeRepository();
        NotionSourceAdapter source = source(page("page-1", content("graph")));
        KnowledgeAcl otherAcl = new KnowledgeAcl("meguri", Set.of("user:other"));
        KnowledgeProjector invalid = (document, version, page) -> {
            KnowledgeChunk parent = KnowledgeChunk.parent(
                    "parent", document.id(), version.id(), 0, "context", ACL, NOW);
            KnowledgeChunk child = KnowledgeChunk.child(
                    "child", document.id(), version.id(), "parent", 1, "evidence", ACL, NOW);
            KnowledgeEntity entity = new KnowledgeEntity(
                    "entity", document.id(), version.id(), "system", "Meguri",
                    "missing-evidence", otherAcl, NOW);
            return new KnowledgeBuild(List.of(parent, child), List.of(entity), List.of());
        };

        List<IngestionResult> results = service(repository, source, invalid).synchronize();

        KnowledgeDocument document = repository.findDocument("notion", "page-1").orElseThrow();
        assertThat(results.getFirst().outcome()).isEqualTo(IngestionOutcome.FAILED);
        assertThat(repository.findActiveVersion(document.id())).isEmpty();
        assertThat(repository.versions(document.id()).getFirst().status())
                .isEqualTo(KnowledgeStatus.FAILED);
    }

    @Test
    void tombstonesDeletedOrRemovedAllowlistPagesWithoutLosingHistory() {
        InMemoryKnowledgeRepository repository = new InMemoryKnowledgeRepository();
        NotionSourceAdapter source = source(page("page-1", content("kept history")));
        KnowledgeIngestionService service = service(repository, source, new DeterministicKnowledgeProjector());
        service.synchronize();
        KnowledgeDocument document = repository.findDocument("notion", "page-1").orElseThrow();
        String versionId = repository.findActiveVersion(document.id()).orElseThrow().id();

        source.setAllowlist(Set.of());
        List<IngestionResult> results = service.synchronize();

        KnowledgeDocument deleted = repository.findDocument("notion", "page-1").orElseThrow();
        assertThat(results.getFirst().outcome()).isEqualTo(IngestionOutcome.TOMBSTONED);
        assertThat(deleted.status()).isEqualTo(KnowledgeStatus.DELETED);
        assertThat(repository.findActiveVersion(document.id())).isEmpty();
        assertThat(repository.versions(document.id()).getFirst().status())
                .isEqualTo(KnowledgeStatus.DELETED);
        assertThat(repository.chunks(versionId)).isNotEmpty();
    }

    @Test
    void reconcilesRemovedAllowlistAfterAdapterRestart() {
        InMemoryKnowledgeRepository repository = new InMemoryKnowledgeRepository();
        NotionSourceAdapter original = source(page("page-1", content("before restart")));
        service(repository, original, new DeterministicKnowledgeProjector()).synchronize();

        NotionSourceAdapter restarted = new NotionSourceAdapter(
                "notion", ACL, List.of(), Set.of());
        List<IngestionResult> results =
                service(repository, restarted, new DeterministicKnowledgeProjector()).synchronize();

        assertThat(results).extracting(IngestionResult::outcome)
                .containsExactly(IngestionOutcome.TOMBSTONED);
        assertThat(repository.findDocument("notion", "page-1").orElseThrow().status())
                .isEqualTo(KnowledgeStatus.DELETED);
    }

    @Test
    void tombstonesPageMarkedDeletedByNotion() {
        InMemoryKnowledgeRepository repository = new InMemoryKnowledgeRepository();
        NotionSourceAdapter source = source(page("page-1", content("before deletion")));
        KnowledgeIngestionService service =
                service(repository, source, new DeterministicKnowledgeProjector());
        service.synchronize();
        source.put(new NotionPage(
                "page-1", "Meguri", "", NOW.plusSeconds(30), true, false));

        List<IngestionResult> results = service.synchronize();

        assertThat(results).extracting(IngestionResult::outcome)
                .containsExactly(IngestionOutcome.TOMBSTONED);
        assertThat(repository.findDocument("notion", "page-1").orElseThrow().status())
                .isEqualTo(KnowledgeStatus.DELETED);
    }

    @Test
    void childIsRecallableAndRestoresOnlyItsParentContext() {
        InMemoryKnowledgeRepository repository = new InMemoryKnowledgeRepository();
        NotionSourceAdapter source = source(page("page-1", content("context")));
        service(repository, source, new DeterministicKnowledgeProjector()).synchronize();
        KnowledgeDocument document = repository.findDocument("notion", "page-1").orElseThrow();
        KnowledgeDocumentVersion active = repository.findActiveVersion(document.id()).orElseThrow();

        List<KnowledgeChunk> recalled = repository.recallableChildren(active.id(), ACL, NOW);
        KnowledgeChunk child = recalled.getFirst();
        KnowledgeChunk parent = repository.restoreParent(child.id(), ACL, NOW).orElseThrow();

        assertThat(recalled).allMatch(chunk -> chunk.role() == ChunkRole.CHILD);
        assertThat(parent.role()).isEqualTo(ChunkRole.PARENT);
        assertThat(parent.id()).isEqualTo(child.parentChunkId());
        assertThat(parent.documentVersionId()).isEqualTo(child.documentVersionId());
        assertThat(parent.acl()).isEqualTo(child.acl());
        assertThat(parent.validFrom()).isEqualTo(child.validFrom());
        assertThat(parent.validUntil()).isEqualTo(child.validUntil());
    }

    @Test
    void blocksSecretPatternsBeforeAnyDocumentIsPersisted() {
        InMemoryKnowledgeRepository repository = new InMemoryKnowledgeRepository();
        NotionSourceAdapter source = source(new NotionPage(
                "page-secret", "Secrets", "token = sk-1234567890abcdefghijklmnop",
                NOW, false, false));

        List<IngestionResult> results = service(
                repository, source, new DeterministicKnowledgeProjector()).synchronize();

        assertThat(results.getFirst().outcome()).isEqualTo(IngestionOutcome.REJECTED);
        assertThat(repository.documents()).isEmpty();
    }

    @Test
    void blocksSecretPatternsInSourceMetadataBeforePersistence() {
        InMemoryKnowledgeRepository repository = new InMemoryKnowledgeRepository();
        KnowledgeSourceMetadata metadata = new KnowledgeSourceMetadata(
                "notion://page/metadata-secret",
                "https://example.test/page?api_key=1234567890abcdef",
                "en", "notion", null, List.of());
        SourcePage page = new SourcePage(
                "notion", "metadata-secret", "Metadata", "safe body",
                "c".repeat(64), NOW, ACL, false, false, metadata);
        KnowledgeIngestionService service = new KnowledgeIngestionService(
                repository, List.of(), new DeterministicKnowledgeProjector(),
                new KnowledgeEvidenceValidator(), new SecretDetector(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        IngestionResult result = service.ingest(page);

        assertThat(result.outcome()).isEqualTo(IngestionOutcome.REJECTED);
        assertThat(repository.documents()).isEmpty();
    }

    @Test
    void rejectsOutOfOrderPublicationSoActiveVersionCannotMoveBackward() {
        InMemoryKnowledgeRepository repository = new InMemoryKnowledgeRepository();
        SourcePage olderPage = source(page("page-1", content("older"))).scan().getFirst();
        BuildStart older = repository.beginBuild(olderPage, NOW);
        SourcePage newerPage = new SourcePage(
                olderPage.sourceId(), olderPage.pageId(), olderPage.title(), content("newer"),
                "b".repeat(64), NOW.plusSeconds(30), ACL, false, false);
        BuildStart newer = repository.beginBuild(newerPage, NOW.plusSeconds(30));
        DeterministicKnowledgeProjector projector = new DeterministicKnowledgeProjector();

        repository.publish(newer.version().id(),
                projector.project(newer.document(), newer.version(), newerPage), NOW.plusSeconds(30));

        assertThatThrownBy(() -> repository.publish(
                older.version().id(),
                projector.project(older.document(), older.version(), olderPage),
                NOW.plusSeconds(60)))
                .hasMessageContaining("stale");
        assertThat(repository.findActiveVersion(older.document().id())).get()
                .extracting(KnowledgeDocumentVersion::id)
                .isEqualTo(newer.version().id());
    }

    @Test
    void projectsConservativePlainTextRelationsWithSameVersionEvidence() {
        InMemoryKnowledgeRepository repository =
                new InMemoryKnowledgeRepository();
        NotionSourceAdapter source = source(page(
                "plain-relations",
                """
                Alice owns Project Aurora.

                服务A依赖数据库B。

                团队甲与团队乙合作。
                """));

        service(repository, source, new DeterministicKnowledgeProjector())
                .synchronize();

        KnowledgeDocument document = repository
                .findDocument("notion", "plain-relations")
                .orElseThrow();
        KnowledgeDocumentVersion active = repository
                .findActiveVersion(document.id())
                .orElseThrow();
        List<KnowledgeRelation> relations = repository.relations(active.id());
        assertThat(relations)
                .extracting(KnowledgeRelation::relationType)
                .containsExactlyInAnyOrder(
                        "owns", "depends_on", "works_with");
        assertThat(relations).allSatisfy(relation -> {
            assertThat(relation.documentVersionId()).isEqualTo(active.id());
            assertThat(repository.chunks(active.id()))
                    .filteredOn(chunk -> chunk.id().equals(
                            relation.evidenceChunkId()))
                    .singleElement()
                    .satisfies(chunk -> {
                        assertThat(chunk.role()).isEqualTo(ChunkRole.CHILD);
                        assertThat(chunk.acl()).isEqualTo(relation.acl());
                    });
        });
    }

    private static KnowledgeIngestionService service(
            KnowledgeRepository repository,
            SourceRegistry source,
            KnowledgeProjector projector) {
        return new KnowledgeIngestionService(
                repository, List.of(source), projector, new KnowledgeEvidenceValidator(),
                new SecretDetector(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static NotionSourceAdapter source(NotionPage page) {
        return new NotionSourceAdapter("notion", ACL, List.of(page), Set.of(page.pageId()));
    }

    private static NotionPage page(String id, String content) {
        return new NotionPage(id, "Meguri", content, NOW, false, false);
    }

    private static String content(String marker) {
        return """
                # Knowledge Base
                %s parent context.

                %s evidence [[entity:system|Meguri]] [[entity:database|KnowledgeBase]]
                [[relation:Meguri|stores|KnowledgeBase]]
                """.formatted(marker, marker);
    }
}

package com.meguri.core.knowledge;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeSearchProjectionTest {
    private static final Instant NOW = Instant.parse("2026-07-28T12:00:00Z");
    private static final KnowledgeAcl ACL = new KnowledgeAcl("meguri", Set.of("user:izumi"));

    @Test
    void threeArgumentBuildConstructorRemainsSourceCompatible() {
        KnowledgeBuild build = new KnowledgeBuild(List.of(), List.of(), List.of());

        assertThat(build.termProjections()).isEmpty();
        assertThat(build.vectorProjections()).isEmpty();
    }

    @Test
    void deterministicProjectorCreatesBothSearchProjectionsForEveryChild() {
        ProjectionFixture fixture = fixture("Meguri knowledge search keeps exact evidence.");
        KnowledgeBuild first = fixture.projector().project(
                fixture.document(), fixture.version(), fixture.page());
        KnowledgeBuild second = fixture.projector().project(
                fixture.document(), fixture.version(), fixture.page());
        List<String> childIds = first.chunks().stream()
                .filter(chunk -> chunk.role() == ChunkRole.CHILD)
                .map(KnowledgeChunk::id)
                .toList();

        assertThat(first.termProjections()).extracting(KnowledgeTermProjection::chunkId)
                .containsExactlyInAnyOrderElementsOf(childIds);
        assertThat(first.vectorProjections()).extracting(KnowledgeVectorProjection::chunkId)
                .containsExactlyInAnyOrderElementsOf(childIds);
        assertThat(first.termProjections()).isEqualTo(second.termProjections());
        assertThat(first.vectorProjections()).isEqualTo(second.vectorProjections());
        assertThat(first.vectorProjections())
                .allMatch(projection -> projection.embedding().size()
                        == DeterministicSearchProjector.EMBEDDING_DIMENSIONS);
    }

    @Test
    void repositoryPersistsProjectionsAndSupportsBm25AndVectorSearch() {
        InMemoryKnowledgeRepository repository = new InMemoryKnowledgeRepository();
        NotionSourceAdapter source = source("""
                # Search
                alpha generic context

                orchid observatory unique evidence

                routine background text
                """);
        KnowledgeIngestionService service = service(repository, source, new DeterministicKnowledgeProjector());

        assertThat(service.synchronize()).extracting(IngestionResult::outcome)
                .containsExactly(IngestionOutcome.PUBLISHED);
        KnowledgeDocument document = repository.findDocument("notion", "page-search").orElseThrow();
        KnowledgeDocumentVersion active = repository.findActiveVersion(document.id()).orElseThrow();

        assertThat(repository.termProjections(active.id()))
                .hasSameSizeAs(repository.recallableChildren(active.id(), ACL, NOW));
        assertThat(repository.vectorProjections(active.id()))
                .hasSameSizeAs(repository.recallableChildren(active.id(), ACL, NOW));
        assertThat(repository.keywordSearch(active.id(), ACL, NOW, "orchid observatory", 3))
                .isNotEmpty()
                .first()
                .extracting(hit -> hit.chunk().content())
                .asString().contains("orchid observatory");
        List<Double> queryEmbedding =
                DeterministicSearchProjector.embedding("orchid observatory unique evidence");
        assertThat(repository.vectorSearch(active.id(), ACL, NOW, queryEmbedding, 3))
                .isNotEmpty()
                .first()
                .extracting(hit -> hit.chunk().content())
                .asString().contains("orchid observatory");
    }

    @Test
    void missingProjectionFailsBuildAndKeepsPreviousActive() {
        InMemoryKnowledgeRepository repository = new InMemoryKnowledgeRepository();
        NotionSourceAdapter source = source("stable searchable content");
        service(repository, source, new DeterministicKnowledgeProjector()).synchronize();
        KnowledgeDocument document = repository.findDocument("notion", "page-search").orElseThrow();
        String previousActive = repository.findActiveVersion(document.id()).orElseThrow().id();

        source.put(new NotionPage(
                "page-search", "Search", "new content missing vectors",
                NOW.plusSeconds(60), false, false));
        DeterministicKnowledgeProjector delegate = new DeterministicKnowledgeProjector();
        KnowledgeProjector missingVectors = (doc, version, page) -> {
            KnowledgeBuild complete = delegate.project(doc, version, page);
            return new KnowledgeBuild(
                    complete.chunks(), complete.entities(), complete.relations(),
                    complete.termProjections(), List.of());
        };

        assertThat(service(repository, source, missingVectors).synchronize())
                .extracting(IngestionResult::outcome)
                .containsExactly(IngestionOutcome.FAILED);
        assertThat(repository.findActiveVersion(document.id())).get()
                .extracting(KnowledgeDocumentVersion::id)
                .isEqualTo(previousActive);
        assertThat(repository.versions(document.id()))
                .extracting(KnowledgeDocumentVersion::status)
                .contains(KnowledgeStatus.ACTIVE, KnowledgeStatus.FAILED);
    }

    @Test
    void rejectsSearchProjectionWithDifferentAclOrVersion() {
        ProjectionFixture fixture = fixture("scope checked projection");
        KnowledgeBuild complete = fixture.projector().project(
                fixture.document(), fixture.version(), fixture.page());
        KnowledgeTermProjection term = complete.termProjections().getFirst();
        KnowledgeVectorProjection vector = complete.vectorProjections().getFirst();
        KnowledgeEvidenceValidator validator = new KnowledgeEvidenceValidator();

        KnowledgeTermProjection wrongAcl = new KnowledgeTermProjection(
                term.chunkId(), term.documentId(), term.documentVersionId(),
                new KnowledgeAcl("meguri", Set.of("user:other")),
                term.termFrequencies(), term.tokenCount());
        assertThatThrownBy(() -> validator.validate(
                fixture.document(), fixture.version(),
                new KnowledgeBuild(
                        complete.chunks(), complete.entities(), complete.relations(),
                        List.of(wrongAcl), complete.vectorProjections())))
                .hasMessageContaining("term projection")
                .hasMessageContaining("ACL");

        KnowledgeVectorProjection wrongVersion = new KnowledgeVectorProjection(
                vector.chunkId(), vector.documentId(), "other-version", vector.acl(),
                vector.embeddingModel(), vector.embedding());
        assertThatThrownBy(() -> validator.validate(
                fixture.document(), fixture.version(),
                new KnowledgeBuild(
                        complete.chunks(), complete.entities(), complete.relations(),
                        complete.termProjections(), List.of(wrongVersion))))
                .hasMessageContaining("vector projection");
    }

    private static KnowledgeIngestionService service(
            KnowledgeRepository repository, SourceRegistry source, KnowledgeProjector projector) {
        return new KnowledgeIngestionService(
                repository, List.of(source), projector, new KnowledgeEvidenceValidator(),
                new SecretDetector(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static NotionSourceAdapter source(String content) {
        return new NotionSourceAdapter(
                "notion", ACL,
                List.of(new NotionPage("page-search", "Search", content, NOW, false, false)),
                Set.of("page-search"));
    }

    private static ProjectionFixture fixture(String content) {
        KnowledgeDocument document = new KnowledgeDocument(
                "document", "notion", "page", "Page", ACL,
                KnowledgeStatus.BUILDING, NOW, NOW, null);
        KnowledgeDocumentVersion version = new KnowledgeDocumentVersion(
                "version", document.id(), 1, "a".repeat(64), ACL,
                KnowledgeStatus.BUILDING, NOW, NOW, null, null, null, null);
        SourcePage page = source(content).scan().getFirst();
        return new ProjectionFixture(
                document, version, page, new DeterministicKnowledgeProjector());
    }

    private record ProjectionFixture(
            KnowledgeDocument document,
            KnowledgeDocumentVersion version,
            SourcePage page,
            DeterministicKnowledgeProjector projector) {
    }
}

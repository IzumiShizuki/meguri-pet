package com.meguri.core.retrieval;

import com.meguri.core.knowledge.ChunkRole;
import com.meguri.core.knowledge.DeterministicSearchProjector;
import com.meguri.core.knowledge.KnowledgeAcl;
import com.meguri.core.knowledge.KnowledgeChunk;
import com.meguri.core.knowledge.KnowledgeChunkMetadata;
import com.meguri.core.knowledge.KnowledgeDocument;
import com.meguri.core.knowledge.KnowledgeDocumentVersion;
import com.meguri.core.knowledge.KnowledgeEntity;
import com.meguri.core.knowledge.KnowledgeRelation;
import com.meguri.core.knowledge.KnowledgeStatus;
import com.meguri.core.knowledge.KnowledgeTermProjection;
import com.meguri.core.knowledge.KnowledgeVectorProjection;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeRepositoryRetrievalBridgeTest {
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-01-02T00:00:00Z");
    private static final Instant T2 = Instant.parse("2026-01-03T00:00:00Z");
    private static final KnowledgeAcl ACL = new KnowledgeAcl("tenant", Set.of("user"));

    @Test
    void oldSnapshotSurvivesPublicationAndNewTurnSeesOnlyNewVersion() {
        MutableProjection authority = projectionWithV1Active();
        KnowledgeRepositoryRetrievalBridge firstProcess = bridge(authority);
        FrozenKnowledgeSnapshot oldSnapshot = firstProcess.freezeForNewTurn(T1.plusSeconds(1));
        String durableSnapshotId = oldSnapshot.snapshotId();

        authority.publishV2();
        KnowledgeRepositoryRetrievalBridge restartedProcess = bridge(authority.copy());
        FrozenKnowledgeSnapshot restored = restartedProcess.restoreSnapshot(durableSnapshotId);
        RetrievalItem oldItem = restartedProcess.retrieve(
                "legacy alpha", 5, context(durableSnapshotId, T1.plusSeconds(1), "old-trace"))
                .getFirst();

        FrozenKnowledgeSnapshot newSnapshot = restartedProcess.freezeForNewTurn(T2.plusSeconds(1));
        RetrievalItem newItem = restartedProcess.retrieve(
                "current beta", 5,
                context(newSnapshot.snapshotId(), T2.plusSeconds(1), "new-trace"))
                .getFirst();

        assertThat(restored.versionIds()).containsExactly("version-1");
        assertThat(oldItem.content()).isEqualTo("Parent context for legacy alpha.");
        assertThat(oldItem.citation().anchors()).singleElement().satisfies(citation -> {
            assertThat(citation.canonicalUri()).isEqualTo("notion://page");
            assertThat(citation.title()).isEqualTo("Page");
            assertThat(citation.documentVersionId()).isEqualTo("version-1");
            assertThat(citation.chunkId()).isEqualTo("child-1");
            assertThat(citation.sourceStart()).isNull();
            assertThat(citation.sourceEnd()).isNull();
        });
        assertThat(oldItem.evidenceChunkIds()).containsExactly("child-1");
        assertThat(newItem.content()).isEqualTo("Parent context for current beta.");
        assertThat(newItem.citation().anchors()).singleElement()
                .extracting(RetrievalCitation.Anchor::documentVersionId)
                .isEqualTo("version-2");
        assertThat(newSnapshot.versionIds()).containsExactly("version-2");
    }

    @Test
    void explicitSupersededVersionSetRestoresWithoutSnapshotCache() {
        MutableProjection authority = projectionWithV1Active();
        authority.publishV2();
        KnowledgeRepositoryRetrievalBridge bridge = bridge(authority);

        FrozenKnowledgeSnapshot explicit = bridge.snapshotFromVersions(Set.of("version-1"));
        RetrievalItem item = bridge.retrieve(
                "legacy alpha", 3,
                context("not-used", T1.plusSeconds(1), "explicit-trace"),
                Set.of("version-1")).getFirst();

        assertThat(FrozenKnowledgeSnapshot.restore(explicit.snapshotId()).versionIds())
                .containsExactly("version-1");
        assertThat(item.content()).contains("legacy alpha");
        assertThat(item.rankTrace().ranks())
                .containsKeys(RankSignal.KEYWORD, RankSignal.VECTOR);
    }

    @Test
    void aclVersionParentAndSnapshotIntegrityFailClosed() {
        MutableProjection authority = projectionWithV1Active();
        KnowledgeRepositoryRetrievalBridge bridge = bridge(authority);
        FrozenKnowledgeSnapshot snapshot = bridge.freezeForNewTurn(T1.plusSeconds(1));

        RetrievalContext denied = new RetrievalContext(
                "other-user", Set.of(), snapshot.snapshotId(), 1,
                T1.plusSeconds(1), T2, "denied");
        assertThat(bridge.retrieve("legacy alpha", 3, denied)).isEmpty();
        assertThat(bridge.visibility(
                GraphVisibilityPort.ResourceKind.EVIDENCE_CHUNK, "child-1", denied))
                .isEqualTo(GraphVisibilityPort.Visibility.ACL_DENIED);

        authority.assets.get("version-1").chunks.removeIf(chunk -> chunk.id().equals("parent-1"));
        assertThat(bridge(authority).retrieve(
                "legacy alpha", 3,
                context(snapshot.snapshotId(), T1.plusSeconds(1), "missing-parent")))
                .isEmpty();

        String corrupted = snapshot.snapshotId().substring(0, snapshot.snapshotId().length() - 1)
                + (snapshot.snapshotId().endsWith("0") ? "1" : "0");
        assertThatThrownBy(() -> FrozenKnowledgeSnapshot.restore(corrupted))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("digest");

        MutableProjection changedVersion = projectionWithV1Active();
        FrozenKnowledgeSnapshot beforeChange = bridge(changedVersion)
                .freezeForNewTurn(T1.plusSeconds(1));
        changedVersion.versions.put("version-1", version(
                "version-1", 1, "c".repeat(64),
                KnowledgeStatus.ACTIVE, T1, null));
        assertThatThrownBy(() -> bridge(changedVersion).restoreSnapshot(beforeChange.snapshotId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("metadata mismatch");

        MutableProjection missingEvidence = projectionWithV1Active();
        FrozenKnowledgeSnapshot evidenceSnapshot = bridge(missingEvidence)
                .freezeForNewTurn(T1.plusSeconds(1));
        missingEvidence.assets.get("version-1").chunks
                .removeIf(chunk -> chunk.id().equals("child-1"));
        RetrievalContext evidenceContext = context(
                evidenceSnapshot.snapshotId(), T1.plusSeconds(1), "missing-evidence");
        assertThat(bridge(missingEvidence).resolve(
                "Alice", List.of("Alice"), evidenceContext)).isEmpty();
        assertThat(bridge(missingEvidence).visibility(
                GraphVisibilityPort.ResourceKind.ENTITY, "entity-alice-v1", evidenceContext))
                .isEqualTo(GraphVisibilityPort.Visibility.INACTIVE);
    }

    @Test
    void graphPortsResolveAliasesTraverseAndExpandValidatedEvidence() {
        MutableProjection authority = projectionWithV1Active();
        KnowledgeRepositoryRetrievalBridge bridge = bridge(authority);
        FrozenKnowledgeSnapshot snapshot = bridge.freezeForNewTurn(T1.plusSeconds(1));
        RetrievalContext context = context(snapshot.snapshotId(), T1.plusSeconds(1), "graph-trace");

        List<EntityAliasResolverPort.EntityResolution> aliases = bridge.resolve(
                "How is Alice connected?", List.of("Alice"), context);
        List<GraphPath> paths = bridge.findPaths(
                aliases.stream().map(EntityAliasResolverPort.EntityResolution::entityId).toList(),
                3, 5, context);
        Map<String, KnowledgeGraphStorePort.EvidenceChunk> evidence = bridge.expandEvidence(
                paths.getFirst().edges().getFirst().evidenceChunkIds(), context);

        assertThat(aliases).extracting(EntityAliasResolverPort.EntityResolution::entityId)
                .containsExactly("entity-alice-v1");
        assertThat(paths).singleElement().satisfies(path -> {
            assertThat(path.hops()).isEqualTo(1);
            assertThat(path.nodeIds()).containsExactly("entity-alice-v1", "entity-bob-v1");
            assertThat(path.edges().getFirst().relation()).isEqualTo("works_with");
        });
        assertThat(evidence).containsKey("child-1");
        assertThat(bridge.visibility(
                GraphVisibilityPort.ResourceKind.ENTITY, "entity-alice-v1", context))
                .isEqualTo(GraphVisibilityPort.Visibility.VISIBLE);
        assertThat(bridge.visibility(
                GraphVisibilityPort.ResourceKind.EDGE, "relation-v1", context))
                .isEqualTo(GraphVisibilityPort.Visibility.VISIBLE);
    }

    @Test
    void authoritativeSearchUsesPublishedProjectionsAndRejectsIncompletePublication() {
        MutableProjection base = projectionWithV1Active();
        KnowledgeSearchProjectionPort.Projection legacy =
                base.loadVersions(Set.of("version-1"));
        KnowledgeChunk child = legacy.chunks().stream()
                .filter(chunk -> chunk.role() == ChunkRole.CHILD)
                .findFirst()
                .orElseThrow();
        KnowledgeTermProjection terms = new KnowledgeTermProjection(
                child.id(), child.documentId(), child.documentVersionId(), child.acl(),
                Map.of("published-only-term", 1), 1);
        KnowledgeVectorProjection vector = new KnowledgeVectorProjection(
                child.id(), child.documentId(), child.documentVersionId(), child.acl(),
                DeterministicSearchProjector.EMBEDDING_MODEL,
                DeterministicSearchProjector.embedding("published-only-term"));

        KnowledgeSearchProjectionPort complete = authoritative(
                base, legacy, List.of(terms), List.of(vector));
        KnowledgeRepositoryRetrievalBridge bridge = new KnowledgeRepositoryRetrievalBridge(
                complete, "tenant",
                text -> DeterministicSearchProjector.embedding(text).stream()
                        .mapToDouble(Double::doubleValue).toArray(),
                new WeightedRrfFusion());
        FrozenKnowledgeSnapshot snapshot = bridge.freezeForNewTurn(T1.plusSeconds(1));

        assertThat(bridge.retrieve(
                "published-only-term", 3,
                context(snapshot.snapshotId(), T1.plusSeconds(1), "projection-trace")))
                .singleElement()
                .satisfies(item -> assertThat(item.evidenceChunkIds())
                        .containsExactly(child.id()));

        KnowledgeSearchProjectionPort incomplete = authoritative(
                base, legacy, List.of(), List.of(vector));
        KnowledgeRepositoryRetrievalBridge incompleteBridge =
                new KnowledgeRepositoryRetrievalBridge(
                        incomplete, "tenant",
                        text -> DeterministicSearchProjector.embedding(text).stream()
                                .mapToDouble(Double::doubleValue).toArray(),
                        new WeightedRrfFusion());
        FrozenKnowledgeSnapshot incompleteSnapshot =
                incompleteBridge.freezeForNewTurn(T1.plusSeconds(1));

        assertThatThrownBy(() -> incompleteBridge.retrieve(
                "published-only-term", 3,
                context(incompleteSnapshot.snapshotId(), T1.plusSeconds(1), "incomplete")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("incomplete");
    }

    @Test
    void nativeDatabaseRanksRemainBoundToFrozenVersionAndAcl() {
        MutableProjection base = projectionWithV1Active();
        KnowledgeSearchProjectionPort nativeProjection = nativeProjection(
                base, List.of("child-1"), List.of("child-1"));
        KnowledgeRepositoryRetrievalBridge bridge = bridge(nativeProjection);
        FrozenKnowledgeSnapshot snapshot = bridge.freezeForNewTurn(T1.plusSeconds(1));

        assertThat(bridge.retrieve(
                "native ranking", 3,
                context(snapshot.snapshotId(), T1.plusSeconds(1), "native")))
                .singleElement()
                .satisfies(item -> assertThat(item.evidenceChunkIds())
                        .containsExactly("child-1"));

        KnowledgeRepositoryRetrievalBridge malicious = bridge(nativeProjection(
                base, List.of("outside-frozen-scope"), List.of()));
        FrozenKnowledgeSnapshot maliciousSnapshot =
                malicious.freezeForNewTurn(T1.plusSeconds(1));
        assertThatThrownBy(() -> malicious.retrieve(
                "native ranking", 3,
                context(maliciousSnapshot.snapshotId(), T1.plusSeconds(1), "malicious")))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("escaped");
    }

    @Test
    void keywordAndVectorNativeFailuresDegradeIndependently() {
        MutableProjection base = projectionWithV1Active();
        java.util.concurrent.atomic.AtomicInteger vectorCalls =
                new java.util.concurrent.atomic.AtomicInteger();
        KnowledgeSearchProjectionPort keywordFailure = nativeProjection(
                base,
                () -> { throw new IllegalStateException("keyword index down"); },
                () -> {
                    vectorCalls.incrementAndGet();
                    return List.of("child-1");
                });
        KnowledgeRepositoryRetrievalBridge bridge = bridge(keywordFailure);
        FrozenKnowledgeSnapshot snapshot = bridge.freezeForNewTurn(T1.plusSeconds(1));

        RetrievalProviderResult result = bridge.retrieveWithDiagnostics(
                "legacy alpha", 3,
                context(snapshot.snapshotId(), T1.plusSeconds(1), "keyword-failure"));

        assertThat(vectorCalls).hasValue(1);
        assertThat(result.items()).extracting(RetrievalItem::sourceId)
                .contains("child-1");
        assertThat(result.degradations()).containsExactly(
                "knowledge_keyword_native_failure");

        KnowledgeRepositoryRetrievalBridge vectorizationFailure =
                new KnowledgeRepositoryRetrievalBridge(
                        nativeProjection(base, List.of("child-1"), List.of("child-1")),
                        "tenant", text -> { throw new IllegalStateException("embedding down"); },
                        new WeightedRrfFusion());
        FrozenKnowledgeSnapshot vectorSnapshot =
                vectorizationFailure.freezeForNewTurn(T1.plusSeconds(1));
        RetrievalProviderResult vectorResult =
                vectorizationFailure.retrieveWithDiagnostics(
                        "legacy alpha", 3,
                        context(vectorSnapshot.snapshotId(), T1.plusSeconds(1), "vector-failure"));

        assertThat(vectorResult.items()).extracting(RetrievalItem::sourceId)
                .contains("child-1");
        assertThat(vectorResult.degradations()).containsExactly(
                "knowledge_vector_failure");
    }

    @Test
    void parentRestorationKeepsEvidenceAndTrimsOversizedSectionByTokenBudget() {
        MutableProjection authority = projectionWithV1Active();
        String before = "before ".repeat(20).strip();
        String evidence = "needle evidence";
        String after = "after ".repeat(20).strip();
        String parentContent = String.join("\n\n", before, evidence, after);
        int evidenceStart = parentContent.indexOf(evidence);
        KnowledgeChunk parent = new KnowledgeChunk(
                "parent-budget", "document", "version-1", ChunkRole.PARENT,
                null, 0, parentContent, ACL, T0, KnowledgeChunk.END_OF_TIME,
                KnowledgeChunkMetadata.anchored(
                        parentContent, List.of("Budget"), "Budget",
                        0, parentContent.length()),
                KnowledgeStatus.ACTIVE);
        KnowledgeChunk child = new KnowledgeChunk(
                "child-budget", "document", "version-1", ChunkRole.CHILD,
                parent.id(), 1, evidence, ACL, T0, KnowledgeChunk.END_OF_TIME,
                KnowledgeChunkMetadata.anchored(
                        evidence, List.of("Budget"), "Budget",
                        evidenceStart, evidenceStart + evidence.length()),
                KnowledgeStatus.ACTIVE);
        authority.assets.put("version-1", new Assets(
                new ArrayList<>(List.of(parent, child)),
                new ArrayList<>(), new ArrayList<>()));
        KnowledgeRepositoryRetrievalBridge bridge =
                new KnowledgeRepositoryRetrievalBridge(
                        authority, "tenant",
                        KnowledgeVectorizer.deterministicHashing(64),
                        new WeightedRrfFusion(), 8);
        FrozenKnowledgeSnapshot snapshot =
                bridge.freezeForNewTurn(T1.plusSeconds(1));

        RetrievalItem item = bridge.retrieve(
                "needle", 1,
                context(snapshot.snapshotId(), T1.plusSeconds(1), "budget"))
                .getFirst();

        assertThat(item.content()).isEqualTo(evidence);
        assertThat(item.tokenCount()).isLessThanOrEqualTo(8);
        assertThat(item.degradations()).containsExactly("parent_context_trimmed");
        assertThat(item.citation().anchors()).singleElement().satisfies(citation -> {
            assertThat(citation.sourceStart()).isEqualTo(evidenceStart);
            assertThat(citation.sourceEnd()).isEqualTo(evidenceStart + evidence.length());
        });
    }

    private static KnowledgeSearchProjectionPort nativeProjection(
            MutableProjection base,
            List<String> keywordRanks,
            List<String> vectorRanks) {
        return nativeProjection(base, () -> keywordRanks, () -> vectorRanks);
    }

    private static KnowledgeSearchProjectionPort nativeProjection(
            MutableProjection base,
            java.util.function.Supplier<List<String>> keywordRanks,
            java.util.function.Supplier<List<String>> vectorRanks) {
        return new KnowledgeSearchProjectionPort() {
            @Override
            public List<KnowledgeDocumentVersion> activeVersions() {
                return base.activeVersions();
            }

            @Override
            public Projection loadVersions(Set<String> versionIds) {
                return base.loadVersions(versionIds);
            }

            @Override
            public Optional<List<String>> nativeKeywordRanks(
                    Set<String> versionIds,
                    Set<String> aclHashes,
                    Instant validAt,
                    String query,
                    int limit) {
                return Optional.of(keywordRanks.get());
            }

            @Override
            public Optional<List<String>> nativeVectorRanks(
                    Set<String> versionIds,
                    Set<String> aclHashes,
                    Instant validAt,
                    List<Double> queryEmbedding,
                    int limit) {
                return Optional.of(vectorRanks.get());
            }
        };
    }

    private static KnowledgeSearchProjectionPort authoritative(
            MutableProjection base,
            KnowledgeSearchProjectionPort.Projection legacy,
            List<KnowledgeTermProjection> terms,
            List<KnowledgeVectorProjection> vectors) {
        return new KnowledgeSearchProjectionPort() {
            @Override
            public List<KnowledgeDocumentVersion> activeVersions() {
                return base.activeVersions();
            }

            @Override
            public Projection loadVersions(Set<String> versionIds) {
                return new Projection(
                        legacy.documents(), legacy.versions(), legacy.chunks(),
                        legacy.entities(), legacy.relations(), terms, vectors, true);
            }
        };
    }

    private static KnowledgeRepositoryRetrievalBridge bridge(
            KnowledgeSearchProjectionPort projection) {
        return new KnowledgeRepositoryRetrievalBridge(
                projection, "tenant", KnowledgeVectorizer.deterministicHashing(64),
                new WeightedRrfFusion());
    }

    private static RetrievalContext context(
            String snapshotId, Instant validAt, String traceId) {
        return new RetrievalContext(
                "user", Set.of(), snapshotId, 1,
                validAt, validAt.plusSeconds(30), traceId);
    }

    private static MutableProjection projectionWithV1Active() {
        KnowledgeDocument document = new KnowledgeDocument(
                "document", "notion", "page", "Page", ACL,
                KnowledgeStatus.ACTIVE, T0, T1, null);
        KnowledgeDocumentVersion version = version(
                "version-1", 1, "a".repeat(64),
                KnowledgeStatus.ACTIVE, T1, null);
        Assets assets = assets(
                "version-1", "1", "legacy alpha",
                "entity-alice-v1", "entity-bob-v1", "relation-v1");
        return new MutableProjection(document, Map.of("version-1", version),
                Map.of("version-1", assets), "version-1");
    }

    private static KnowledgeDocumentVersion version(
            String id, long number, String hash, KnowledgeStatus status,
            Instant publishedAt, Instant supersededAt) {
        return new KnowledgeDocumentVersion(
                id, "document", number, hash, ACL, status,
                publishedAt.minusSeconds(60), publishedAt.minusSeconds(60),
                publishedAt, supersededAt, null, null);
    }

    private static Assets assets(
            String versionId, String suffix, String phrase,
            String aliceId, String bobId, String relationId) {
        KnowledgeChunk parent = KnowledgeChunk.parent(
                "parent-" + suffix, "document", versionId, 0,
                "Parent context for " + phrase + ".", ACL, T0)
                .withStatus(KnowledgeStatus.ACTIVE);
        KnowledgeChunk child = KnowledgeChunk.child(
                "child-" + suffix, "document", versionId, parent.id(), 1,
                phrase + " evidence links Alice and Bob.", ACL, T0)
                .withStatus(KnowledgeStatus.ACTIVE);
        KnowledgeEntity alice = new KnowledgeEntity(
                aliceId, "document", versionId, "person", "Alice",
                child.id(), ACL, T0).withStatus(KnowledgeStatus.ACTIVE);
        KnowledgeEntity bob = new KnowledgeEntity(
                bobId, "document", versionId, "person", "Bob",
                child.id(), ACL, T0).withStatus(KnowledgeStatus.ACTIVE);
        KnowledgeRelation relation = new KnowledgeRelation(
                relationId, "document", versionId, alice.id(),
                "works_with", bob.id(), child.id(), ACL, T0)
                .withStatus(KnowledgeStatus.ACTIVE);
        return new Assets(
                new ArrayList<>(List.of(parent, child)),
                new ArrayList<>(List.of(alice, bob)),
                new ArrayList<>(List.of(relation)));
    }

    private static final class MutableProjection implements KnowledgeSearchProjectionPort {
        private final KnowledgeDocument document;
        private final Map<String, KnowledgeDocumentVersion> versions;
        private final Map<String, Assets> assets;
        private String activeVersionId;

        private MutableProjection(
                KnowledgeDocument document,
                Map<String, KnowledgeDocumentVersion> versions,
                Map<String, Assets> assets,
                String activeVersionId) {
            this.document = document;
            this.versions = new LinkedHashMap<>(versions);
            this.assets = new LinkedHashMap<>(assets);
            this.activeVersionId = activeVersionId;
        }

        private void publishV2() {
            versions.put("version-1", version(
                    "version-1", 1, "a".repeat(64),
                    KnowledgeStatus.SUPERSEDED, T1, T2));
            versions.put("version-2", version(
                    "version-2", 2, "b".repeat(64),
                    KnowledgeStatus.ACTIVE, T2, null));
            assets.put("version-2", assets(
                    "version-2", "2", "current beta",
                    "entity-alice-v2", "entity-bob-v2", "relation-v2"));
            activeVersionId = "version-2";
        }

        private MutableProjection copy() {
            Map<String, Assets> copiedAssets = assets.entrySet().stream().collect(
                    java.util.stream.Collectors.toMap(
                            Map.Entry::getKey, entry -> entry.getValue().copy(),
                            (left, right) -> left, LinkedHashMap::new));
            return new MutableProjection(document, versions, copiedAssets, activeVersionId);
        }

        @Override
        public List<KnowledgeDocumentVersion> activeVersions() {
            return List.of(versions.get(activeVersionId));
        }

        @Override
        public Projection loadVersions(Set<String> versionIds) {
            List<KnowledgeDocumentVersion> selectedVersions = versionIds.stream()
                    .map(versions::get).filter(java.util.Objects::nonNull).toList();
            List<KnowledgeChunk> chunks = new ArrayList<>();
            List<KnowledgeEntity> entities = new ArrayList<>();
            List<KnowledgeRelation> relations = new ArrayList<>();
            for (String versionId : versionIds) {
                Assets selected = assets.get(versionId);
                if (selected == null) continue;
                chunks.addAll(selected.chunks);
                entities.addAll(selected.entities);
                relations.addAll(selected.relations);
            }
            return new Projection(
                    selectedVersions.isEmpty() ? List.of() : List.of(document),
                    selectedVersions, chunks, entities, relations);
        }
    }

    private static final class Assets {
        private final List<KnowledgeChunk> chunks;
        private final List<KnowledgeEntity> entities;
        private final List<KnowledgeRelation> relations;

        private Assets(
                List<KnowledgeChunk> chunks,
                List<KnowledgeEntity> entities,
                List<KnowledgeRelation> relations) {
            this.chunks = chunks;
            this.entities = entities;
            this.relations = relations;
        }

        private Assets copy() {
            return new Assets(
                    new ArrayList<>(chunks),
                    new ArrayList<>(entities),
                    new ArrayList<>(relations));
        }
    }
}

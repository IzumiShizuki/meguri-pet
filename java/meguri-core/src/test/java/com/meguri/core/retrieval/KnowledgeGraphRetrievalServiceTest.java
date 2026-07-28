package com.meguri.core.retrieval;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeGraphRetrievalServiceTest {
    @Test
    void acceptsAndOrdersBoundedOneToThreeHopPathsWithExpandedEvidence() {
        FakeGraphStore store = new FakeGraphStore();
        store.paths = List.of(path(3, 0.7), path(1, 0.9), path(2, 0.8));
        KnowledgeGraphRetrievalService service = service(store, visible());

        GraphRetrievalResult result = service.retrieve(graphPlan(), context(), 10);

        assertThat(result.status()).isEqualTo(GraphRetrievalResult.Status.SUCCESS);
        assertThat(result.items()).extracting(item -> item.graphPath().hops())
                .containsExactly(1, 2, 3);
        assertThat(result.items()).allSatisfy(item -> {
            assertThat(item.evidenceChunkIds()).isNotEmpty();
            assertThat(item.content()).contains("evidence");
        });
        assertThat(store.requestedHops).isEqualTo(3);
    }

    @Test
    void rejectsPathWhenAnyEdgeHasNoEvidence() {
        FakeGraphStore store = new FakeGraphStore();
        GraphPath valid = path(1, 1);
        GraphPath.Edge edge = valid.edges().getFirst();
        store.paths = List.of(new GraphPath(valid.nodeIds(), List.of(new GraphPath.Edge(
                edge.edgeId(), edge.fromId(), edge.relation(), edge.toId(), List.of(), 1)), 1));

        GraphRetrievalResult result = service(store, visible())
                .retrieve(graphPlan(), context(), 5);

        assertThat(result.fallbackRequired()).isTrue();
        assertThat(result.degradations()).contains("graph_evidence_missing");
    }

    @Test
    void rejectsAclAndVersionMismatches() {
        FakeGraphStore store = new FakeGraphStore();
        store.paths = List.of(path(1, 1));
        GraphVisibilityPort edgeDenied = (kind, id, context) ->
                kind == GraphVisibilityPort.ResourceKind.EDGE
                        ? GraphVisibilityPort.Visibility.ACL_DENIED
                        : GraphVisibilityPort.Visibility.VISIBLE;
        GraphVisibilityPort evidenceWrongVersion = (kind, id, context) ->
                kind == GraphVisibilityPort.ResourceKind.EVIDENCE_CHUNK
                        ? GraphVisibilityPort.Visibility.VERSION_MISMATCH
                        : GraphVisibilityPort.Visibility.VISIBLE;
        GraphVisibilityPort inactiveEntity = (kind, id, context) ->
                kind == GraphVisibilityPort.ResourceKind.ENTITY && id.startsWith("node:")
                        ? GraphVisibilityPort.Visibility.INACTIVE
                        : GraphVisibilityPort.Visibility.VISIBLE;

        assertThat(service(store, edgeDenied).retrieve(graphPlan(), context(), 5).degradations())
                .contains("graph_edge_visibility_mismatch");
        assertThat(service(store, evidenceWrongVersion).retrieve(graphPlan(), context(), 5).degradations())
                .contains("graph_evidence_visibility_mismatch");
        assertThat(service(store, inactiveEntity).retrieve(graphPlan(), context(), 5).degradations())
                .contains("graph_path_entity_visibility_mismatch");
    }

    @Test
    void unresolvedAliasDeterministicallyRequestsHybridFallback() {
        FakeGraphStore store = new FakeGraphStore();
        KnowledgeGraphRetrievalService service = new KnowledgeGraphRetrievalService(
                (query, mentions, context) -> List.of(), store, visible());

        GraphRetrievalResult result = service.retrieve(graphPlan(), context(), 5);

        assertThat(result.fallbackRequired()).isTrue();
        assertThat(result.degradations()).containsExactly("graph_entity_unresolved");
        assertThat(store.findCalls).isZero();
    }

    @Test
    void filtersExactRelationIntentBeforeExpandingEvidence() {
        FakeGraphStore store = new FakeGraphStore();
        store.paths = List.of(path("related_to"), path("depends_on"));

        GraphRetrievalResult result = service(store, visible()).retrieve(
                graphPlan(QueryRewriteResult.GraphIntent.RELATION, "depends_on"), context(), 5);

        assertThat(result.items()).singleElement().satisfies(item ->
                assertThat(item.graphPath().edges()).extracting(GraphPath.Edge::relation)
                        .containsExactly("depends_on"));
        assertThat(result.degradations()).contains("graph_relation_intent_mismatch");
        assertThat(store.expandedChunkIds).containsExactly("chunk:depends_on");
    }

    @Test
    void timelineIntentOnlyKeepsTemporalPathsAndStillValidatesAclAndEvidence() {
        FakeGraphStore store = new FakeGraphStore();
        store.paths = List.of(path("related_to"), path("happened_before"));
        GraphVisibilityPort evidenceDenied = (kind, id, context) ->
                kind == GraphVisibilityPort.ResourceKind.EVIDENCE_CHUNK
                        ? GraphVisibilityPort.Visibility.ACL_DENIED
                        : GraphVisibilityPort.Visibility.VISIBLE;

        GraphRetrievalResult result = service(store, evidenceDenied).retrieve(
                graphPlan(QueryRewriteResult.GraphIntent.TIMELINE, ""), context(), 5);

        assertThat(result.fallbackRequired()).isTrue();
        assertThat(result.degradations()).contains("graph_evidence_visibility_mismatch");
        assertThat(store.expandedChunkIds).containsExactly("chunk:happened_before");
    }

    @Test
    void callChainAllowsMixedInvocationRelationsAcrossHops() {
        FakeGraphStore store = new FakeGraphStore();
        store.paths = List.of(path(List.of("calls", "triggers")), path("belongs_to"));

        GraphRetrievalResult result = service(store, visible()).retrieve(
                graphPlan(QueryRewriteResult.GraphIntent.CALL_CHAIN, "calls"), context(), 5);

        assertThat(result.items()).singleElement().satisfies(item ->
                assertThat(item.graphPath().edges()).extracting(GraphPath.Edge::relation)
                        .containsExactly("calls", "triggers"));
    }

    @Test
    void noIntentKeepsAllRelationsForBackwardCompatibility() {
        FakeGraphStore store = new FakeGraphStore();
        store.paths = List.of(path("custom_relation"), path("related_to"));

        GraphRetrievalResult result = service(store, visible()).retrieve(
                graphPlan(QueryRewriteResult.GraphIntent.NONE, ""), context(), 5);

        assertThat(result.items()).hasSize(2);
        assertThat(result.degradations()).doesNotContain("graph_relation_intent_mismatch");
    }

    private static KnowledgeGraphRetrievalService service(
            FakeGraphStore store, GraphVisibilityPort visibility) {
        return new KnowledgeGraphRetrievalService(
                (query, mentions, context) -> List.of(
                        new EntityAliasResolverPort.EntityResolution("Meguri", "entity:meguri", 1)),
                store, visibility);
    }

    private static GraphVisibilityPort visible() {
        return (kind, id, context) -> GraphVisibilityPort.Visibility.VISIBLE;
    }

    static RetrievalPlan graphPlan() {
        return graphPlan(QueryRewriteResult.GraphIntent.RELATION, "");
    }

    static RetrievalPlan graphPlan(QueryRewriteResult.GraphIntent intent, String relationType) {
        return new RetrievalPlan(
                RetrievalMode.FAST,
                new QueryRewriteResult("relationship", "relationship", List.of("Meguri"),
                        intent != QueryRewriteResult.GraphIntent.NONE, relationType, intent),
                Set.of(SourceType.KNOWLEDGE), Map.of(SourceType.KNOWLEDGE, 10),
                Map.of(SourceType.KNOWLEDGE, 1), true, 3, 10,
                Instant.now().plusSeconds(10));
    }

    static RetrievalContext context() {
        return new RetrievalContext(
                "user", Set.of("private"), "snapshot", 7,
                Instant.now(), Instant.now().plusSeconds(5), "trace");
    }

    private static GraphPath path(int hops, double score) {
        return path(java.util.Collections.nCopies(hops, "related_to"), score);
    }

    private static GraphPath path(String relation) {
        return path(List.of(relation), 1);
    }

    private static GraphPath path(List<String> relations) {
        return path(relations, 1);
    }

    private static GraphPath path(List<String> relations, double score) {
        int hops = relations.size();
        List<String> nodes = new ArrayList<>();
        List<GraphPath.Edge> edges = new ArrayList<>();
        for (int i = 0; i <= hops; i++) nodes.add("node:" + hops + ":" + i);
        for (int i = 0; i < hops; i++) {
            edges.add(new GraphPath.Edge(
                    "edge:" + hops + ":" + i, nodes.get(i), relations.get(i), nodes.get(i + 1),
                    List.of(relations.size() == 1
                            ? "chunk:" + relations.get(i) : "chunk:" + hops + ":" + i), score));
        }
        return new GraphPath(nodes, edges, score);
    }

    private static final class FakeGraphStore implements KnowledgeGraphStorePort {
        private List<GraphPath> paths = List.of();
        private int requestedHops;
        private int findCalls;
        private List<String> expandedChunkIds = List.of();

        @Override
        public List<GraphPath> findPaths(
                List<String> ids, int maxHops, int limit, RetrievalContext context) {
            requestedHops = maxHops;
            findCalls++;
            return paths;
        }

        @Override
        public Map<String, EvidenceChunk> expandEvidence(
                Collection<String> chunkIds, RetrievalContext context) {
            expandedChunkIds = List.copyOf(chunkIds);
            Map<String, EvidenceChunk> result = new LinkedHashMap<>();
            for (String id : chunkIds) {
                result.put(id, new EvidenceChunk(
                        id, "evidence " + id, "kb:" + id, 0.9, 5, context.validAt()));
            }
            return result;
        }
    }
}

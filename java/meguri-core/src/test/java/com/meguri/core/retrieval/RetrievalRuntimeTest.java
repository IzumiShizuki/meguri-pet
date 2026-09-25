package com.meguri.core.retrieval;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalRuntimeTest {
    @Test
    void graphFailureFallsBackToHybridExactlyOnce() {
        AtomicInteger hybridCalls = new AtomicInteger();
        KnowledgeGraphRetrievalService graph = new KnowledgeGraphRetrievalService(
                (query, mentions, context) -> {
                    throw new IllegalStateException("down");
                },
                emptyStore(), visible());
        InMemoryRetrievalTraceRepository traces = new InMemoryRetrievalTraceRepository();

        try (RetrievalRuntime runtime = runtime(
                graph, Map.of(), (query, limit, context) -> {
                    hybridCalls.incrementAndGet();
                    return List.of(item(SourceType.KNOWLEDGE, "hybrid", context.traceId()));
                }, traces, Duration.ofMillis(200))) {
            RetrievalBundle bundle = runtime.retrieve("relationship", RetrievalMode.FAST, context());

            assertThat(hybridCalls).hasValue(1);
            assertThat(bundle.items()).extracting(RetrievalItem::sourceId).containsExactly("hybrid");
            assertThat(bundle.lanes().get(SourceType.KNOWLEDGE).status())
                    .isEqualTo(RetrievalLaneResult.Status.DEGRADED);
            assertThat(bundle.degradations()).contains("graph_failure");
        }
    }

    @Test
    void graphTimeoutFallsBackWithoutDelayingIndependentLanes() {
        AtomicInteger loreCalls = new AtomicInteger();
        CountDownLatch graphInterrupted = new CountDownLatch(1);
        KnowledgeGraphRetrievalService graph = new KnowledgeGraphRetrievalService(
                (query, mentions, context) -> {
                    try {
                        Thread.sleep(2_000);
                    } catch (InterruptedException error) {
                        graphInterrupted.countDown();
                        throw new java.util.concurrent.CancellationException("cancelled");
                    }
                    return List.of(new EntityAliasResolverPort.EntityResolution("x", "x", 1));
                },
                emptyStore(), visible());
        Map<SourceType, RetrievalProvider> providers = Map.of(
                SourceType.LORE, (query, limit, context) -> {
                    loreCalls.incrementAndGet();
                    return List.of(item(SourceType.LORE, "lore", context.traceId()));
                });

        long started = System.nanoTime();
        try (RetrievalRuntime runtime = runtime(
                graph, providers,
                (query, limit, context) -> List.of(
                        item(SourceType.KNOWLEDGE, "hybrid", context.traceId())),
                new InMemoryRetrievalTraceRepository(), Duration.ofMillis(30))) {
            RetrievalBundle bundle = runtime.retrieve("relationship", RetrievalMode.FAST, context());

            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(180));
            assertThat(loreCalls).hasValue(1);
            assertThat(bundle.items()).extracting(RetrievalItem::sourceId)
                    .contains("lore", "hybrid");
            assertThat(bundle.degradations()).contains("graph_timeout");
            assertThat(await(graphInterrupted)).isTrue();
        }
    }

    @Test
    void oneLaneFailureDoesNotDiscardOtherLaneAndTraceReplaysExactly() throws Exception {
        Map<SourceType, RetrievalProvider> providers = Map.of(
                SourceType.LORE, (query, limit, context) -> {
                    throw new IllegalStateException("lore unavailable");
                },
                SourceType.MEMORY, (query, limit, context) -> List.of(
                        item(SourceType.MEMORY, "memory-1", context.traceId()),
                        item(SourceType.MEMORY, "memory-2", context.traceId()),
                        item(SourceType.MEMORY, "memory-3", context.traceId()),
                        item(SourceType.MEMORY, "memory-4", context.traceId())));
        InMemoryRetrievalTraceRepository traces = new InMemoryRetrievalTraceRepository();

        try (RetrievalRuntime runtime = runtime(
                null, providers,
                (query, limit, context) -> List.of(
                        item(SourceType.KNOWLEDGE, "hybrid", context.traceId())),
                traces, Duration.ofMillis(200))) {
            RetrievalContext context = context();
            RetrievalBundle bundle = runtime.retrieve("ordinary", RetrievalMode.FAST, context);
            RetrievalTrace trace = traces.find(context.traceId()).orElseThrow();

            assertThat(bundle.lanes().get(SourceType.LORE).status())
                    .isEqualTo(RetrievalLaneResult.Status.UNAVAILABLE);
            assertThat(bundle.items()).extracting(RetrievalItem::sourceId)
                    .contains("memory-1", "memory-2", "memory-3", "hybrid")
                    .doesNotContain("memory-4");
            assertThat(trace.plan()).isEqualTo(bundle.plan());
            assertThat(trace.snapshotId()).isEqualTo("snapshot");
            assertThat(trace.revision()).isEqualTo(7);
            assertThat(trace.validAt()).isEqualTo(context.validAt());
            assertThat(trace.algorithmRevision())
                    .isEqualTo(RetrievalTrace.ALGORITHM_REVISION);
            assertThat(trace.ranks()).containsKeys(
                    "MEMORY:memory-1", "MEMORY:memory-2", "MEMORY:memory-3",
                    "KNOWLEDGE:hybrid");
            assertThat(trace.candidates())
                    .filteredOn(candidate -> candidate.sourceId().equals("memory-4"))
                    .singleElement()
                    .satisfies(candidate -> {
                        assertThat(candidate.selected()).isFalse();
                        assertThat(candidate.decisionReason())
                                .isEqualTo("bundle_budget_or_seat_limit");
                    });
            assertThat(trace.replayBundle()).isEqualTo(bundle);
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
            String json = mapper.writeValueAsString(trace);
            assertThat(json).contains("\"citation\":{\"anchors\":");
            assertThat(mapper.readValue(json, RetrievalTrace.class)).isEqualTo(trace);

            com.fasterxml.jackson.databind.node.ObjectNode legacy =
                    (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(json);
            legacy.remove(java.util.List.of(
                    "validAt", "algorithmRevision", "candidates"));
            replaceCitationsWithLegacyStrings(legacy);
            RetrievalTrace restoredLegacy = mapper.treeToValue(legacy, RetrievalTrace.class);
            assertThat(restoredLegacy.validAt()).isEqualTo(Instant.EPOCH);
            assertThat(restoredLegacy.algorithmRevision()).isEqualTo("legacy-unversioned");
            assertThat(restoredLegacy.items()).allSatisfy(item ->
                    assertThat(item.citation().displayReferences())
                            .isEqualTo("cite:legacy"));
        }
    }

    @Test
    void laneTimeoutInterruptsTheUnderlyingProviderTask() {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        RetrievalPlanner planner = (query, mode, deadline) -> new RetrievalPlan(
                mode, new QueryRewriteResult(query, query, List.of(), false),
                Set.of(SourceType.LORE), Map.of(SourceType.LORE, 1),
                Map.of(SourceType.LORE, 1), false, 1, 1, deadline);
        RetrievalProvider blocking = (query, limit, context) -> {
            started.countDown();
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException error) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return List.of();
        };

        try (RetrievalRuntime runtime = new RetrievalRuntime(
                planner, new RetrievalGate(), Map.of(SourceType.LORE, blocking),
                (query, limit, context) -> List.of(), null,
                new BundleAssembler(), new InMemoryRetrievalTraceRepository(),
                java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor(),
                Duration.ofMillis(30), Duration.ofMillis(30), true)) {
            RetrievalBundle bundle = runtime.retrieve(
                    "slow lane", RetrievalMode.FAST, context());

            assertThat(await(started)).isTrue();
            assertThat(await(interrupted)).isTrue();
            assertThat(bundle.lanes().get(SourceType.LORE).degradations())
                    .containsExactly("lane_timeout");
        }
    }

    private static RetrievalRuntime runtime(
            KnowledgeGraphRetrievalService graph,
            Map<SourceType, RetrievalProvider> providers,
            RetrievalProvider hybrid,
            RetrievalTraceRepository traces,
            Duration graphTimeout) {
        RetrievalPlanner planner = (query, mode, deadline) -> {
            boolean graphEnabled = "relationship".equals(query);
            Set<SourceType> sources = query.equals("ordinary")
                    ? Set.of(SourceType.LORE, SourceType.MEMORY, SourceType.KNOWLEDGE)
                    : providers.containsKey(SourceType.LORE)
                            ? Set.of(SourceType.LORE, SourceType.KNOWLEDGE)
                            : Set.of(SourceType.KNOWLEDGE);
            Map<SourceType, Integer> budgets = sources.stream().collect(
                    java.util.stream.Collectors.toMap(source -> source, source -> 3));
            Map<SourceType, Integer> seats = sources.stream().collect(
                    java.util.stream.Collectors.toMap(source -> source, source -> 1));
            return new RetrievalPlan(mode, new QueryRewriteResult(
                    query, query, List.of("x"), graphEnabled),
                    sources, budgets, seats, graphEnabled, 3, 6, deadline);
        };
        return new RetrievalRuntime(
                planner, providers, hybrid, graph, traces,
                Duration.ofSeconds(1), graphTimeout);
    }

    private static RetrievalContext context() {
        return new RetrievalContext(
                "user", Set.of(), "snapshot", 7, Instant.now(),
                Instant.now().plusSeconds(2), "runtime-trace");
    }

    private static RetrievalItem item(SourceType source, String id, String traceId) {
        return new RetrievalItem(
                source, id, id + " content", "cite:" + id, 0.8,
                new RankTrace(Map.of(RankSignal.STRUCTURED, 1), 1),
                2, Instant.EPOCH, null, List.of(), List.of(), traceId);
    }

    private static KnowledgeGraphStorePort emptyStore() {
        return new KnowledgeGraphStorePort() {
            @Override
            public List<GraphPath> findPaths(
                    List<String> ids, int maxHops, int limit, RetrievalContext context) {
                return List.of();
            }

            @Override
            public Map<String, EvidenceChunk> expandEvidence(
                    Collection<String> ids, RetrievalContext context) {
                return Map.of();
            }
        };
    }

    private static GraphVisibilityPort visible() {
        return (kind, id, context) -> GraphVisibilityPort.Visibility.VISIBLE;
    }

    private static boolean await(CountDownLatch latch) {
        try {
            return latch.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void replaceCitationsWithLegacyStrings(
            com.fasterxml.jackson.databind.JsonNode node) {
        if (node.isArray()) {
            node.forEach(RetrievalRuntimeTest::replaceCitationsWithLegacyStrings);
            return;
        }
        if (!node.isObject()) return;
        com.fasterxml.jackson.databind.node.ObjectNode object =
                (com.fasterxml.jackson.databind.node.ObjectNode) node;
        java.util.ArrayList<String> fields = new java.util.ArrayList<>();
        object.fieldNames().forEachRemaining(fields::add);
        for (String field : fields) {
            if ("citation".equals(field)) {
                object.put(field, "cite:legacy");
            } else {
                replaceCitationsWithLegacyStrings(object.get(field));
            }
        }
    }
}

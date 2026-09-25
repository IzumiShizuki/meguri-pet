package com.meguri.core.retrieval;

import com.meguri.core.dto.ExpressionTag;
import com.meguri.core.dto.Mode;
import com.meguri.core.dto.Relationship;
import com.meguri.core.dto.RuntimeState;
import com.meguri.core.dto.TurnRequest;
import com.meguri.core.memory.MemoryGateway;
import com.meguri.core.memory.MemoryRecall;
import com.meguri.core.memory.MemoryWriteResult;
import com.meguri.core.memory.SessionSummaryRequest;
import com.meguri.core.memory.SessionSummaryResult;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class UnifiedRetrievalRuntimeTest {
    @Test
    void usesPerSourceQueriesAndRemovesUnauthorizedSourcesBeforeDispatch() {
        AtomicReference<String> loreQuery = new AtomicReference<>();
        AtomicReference<String> knowledgeQuery = new AtomicReference<>();
        RetrievalPlanner planner = (query, mode, deadline) -> new RetrievalPlan(
                mode, new QueryRewriteResult(query, query, List.of(), false, "",
                QueryRewriteResult.GraphIntent.NONE, Map.of(
                        SourceType.LORE, List.of("lore rewrite"),
                        SourceType.MEMORY, List.of("memory rewrite"),
                        SourceType.KNOWLEDGE, List.of("kb rewrite"),
                        SourceType.WEB, List.of("web rewrite"))),
                Set.of(SourceType.LORE, SourceType.MEMORY, SourceType.KNOWLEDGE, SourceType.WEB),
                Map.of(SourceType.LORE, 1, SourceType.MEMORY, 1,
                        SourceType.KNOWLEDGE, 1, SourceType.WEB, 1),
                Map.of(SourceType.LORE, 1, SourceType.MEMORY, 1,
                        SourceType.KNOWLEDGE, 1, SourceType.WEB, 1),
                false, 1, 4, deadline);
        RetrievalProvider lore = (query, limit, context) -> {
            loreQuery.set(query);
            return List.of(item(SourceType.LORE, "lore", context.traceId()));
        };
        RetrievalProvider knowledge = (query, limit, context) -> {
            knowledgeQuery.set(query);
            return List.of(item(SourceType.KNOWLEDGE, "kb", context.traceId()));
        };
        try (RetrievalRuntime runtime = new RetrievalRuntime(
                planner, new RetrievalGate(), Map.of(SourceType.LORE, lore), knowledge,
                null, new BundleAssembler(), new InMemoryRetrievalTraceRepository(),
                java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor(),
                Duration.ofSeconds(1), Duration.ofMillis(100), true,
                RetrievalAuthorizationPolicy.scopeBased())) {
            RetrievalBundle bundle = runtime.retrieve("original", RetrievalMode.SLOW,
                    context(Set.of()));

            assertThat(loreQuery).hasValue("lore rewrite");
            assertThat(knowledgeQuery).hasValue("kb rewrite");
            assertThat(bundle.plan().sources())
                    .containsExactlyInAnyOrder(SourceType.LORE, SourceType.KNOWLEDGE);
            assertThat(bundle.lanes()).doesNotContainKeys(SourceType.MEMORY, SourceType.WEB);
        }
    }

    @Test
    void legacyLoreAndMemoryAdaptersProduceTypedVersionedRrfItems() {
        RuntimeState state = new RuntimeState("airi", Mode.PRIVATE, Relationship.SIBLING,
                "default", "12:00", false, false, false, List.of(ExpressionTag.NEUTRAL));
        TurnRequest turn = new TurnRequest("user", "airi", "session", "remember")
                .withFormalMemoryAllowed(true);
        RetrievalContext context = new RetrievalContext(
                "user", Set.of("memory:read"), "snapshot", 7, Instant.now(),
                Instant.now().plusSeconds(2), "trace", "tenant", state, turn);

        LoreRetrievalProviderAdapter lore = new LoreRetrievalProviderAdapter(
                (query, runtimeState, limit) -> List.of("lore evidence"));
        MemoryRetrievalProviderAdapter memory = new MemoryRetrievalProviderAdapter(
                memoryGateway(List.of("approved memory")));

        assertTyped(lore.retrieve("q", 2, context).getFirst(), SourceType.LORE);
        assertTyped(memory.retrieve("q", 2, context).getFirst(), SourceType.MEMORY);
    }

    private static void assertTyped(RetrievalItem item, SourceType source) {
        assertThat(item.sourceType()).isEqualTo(source);
        assertThat(item.citation().anchors()).singleElement()
                .satisfies(anchor -> assertThat(anchor.documentVersionId())
                        .isEqualTo("snapshot:7"));
        assertThat(item.rankTrace().rrfScore()).isPositive();
        assertThat(item.tokenCount()).isPositive();
        assertThat(item.traceId()).isEqualTo("trace");
    }

    private static RetrievalContext context(Set<String> scopes) {
        return new RetrievalContext("user", scopes, "snapshot", 1, Instant.now(),
                Instant.now().plusSeconds(2), "trace");
    }

    private static RetrievalItem item(SourceType source, String id, String traceId) {
        return new RetrievalItem(source, id, id, "meguri://" + id, 0.8,
                RankTrace.empty(), 1, Instant.now(), null, List.of(), List.of(), traceId);
    }

    private static MemoryGateway memoryGateway(List<String> values) {
        return new MemoryGateway() {
            @Override public Mono<MemoryRecall> recall(TurnRequest request) {
                return Mono.just(MemoryRecall.available(values));
            }
            @Override public Mono<List<com.meguri.core.dto.MemoryCandidate>> extract(TurnRequest request) {
                return Mono.just(List.of());
            }
            @Override public Mono<MemoryWriteResult> write(
                    TurnRequest request, com.meguri.core.dto.LlmResponse response,
                    String turnId, String traceId) {
                return Mono.empty();
            }
            @Override public Mono<SessionSummaryResult> summarize(SessionSummaryRequest request) {
                return Mono.empty();
            }
        };
    }
}

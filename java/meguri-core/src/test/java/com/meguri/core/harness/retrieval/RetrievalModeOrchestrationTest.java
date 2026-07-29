package com.meguri.core.harness.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.meguri.core.dto.ChatResponse;
import com.meguri.core.dto.EventEnvelope;
import com.meguri.core.dto.LlmResponse;
import com.meguri.core.dto.MemoryCandidate;
import com.meguri.core.dto.RuntimeState;
import com.meguri.core.dto.TurnRequest;
import com.meguri.core.llm.LlmProvider;
import com.meguri.core.memory.MemoryGateway;
import com.meguri.core.memory.MemoryRecall;
import com.meguri.core.memory.MemoryWriteResult;
import com.meguri.core.memory.SessionSummaryRequest;
import com.meguri.core.memory.SessionSummaryResult;
import com.meguri.core.rag.RagProvider;
import com.meguri.core.runtime.ExpressionResolver;
import com.meguri.core.runtime.RuntimeStateMachine;
import com.meguri.core.runtime.TurnOrchestrator;
import com.meguri.core.websearch.WebSearchGateway;
import com.meguri.core.websearch.WebSearchPolicy;
import com.meguri.core.websearch.WebSearchRecall;
import com.meguri.core.websearch.WebSearchResult;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalModeOrchestrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void fastNeverCallsWebGatewayDespiteForgedRequestFieldsAndClientGrants() throws Exception {
        TrackingDependencies dependencies = new TrackingDependencies();
        TurnOrchestrator orchestrator = dependencies.orchestrator();
        TurnRequest request = MAPPER.readValue("""
                {"user_id":"u-fast","client_id":"website","session_id":"s-fast",
                 "message":"look up the latest Meguri release","retrieval_mode":"FAST",
                 "client_capabilities":{"text":true,"web":true},
                 "capability_grants":["web.read"],"allow_web_in_fast":true}
                """, TurnRequest.class);

        try {
            orchestrator.runInline(request).block(Duration.ofSeconds(5));

            assertThat(request.retrievalMode()).isEqualTo(RetrievalMode.FAST);
            assertThat(dependencies.web.policyChecks).hasValue(0);
            assertThat(dependencies.web.searches).hasValue(0);
            assertThat(dependencies.rag.searches).hasValue(1);
            // The request has no authenticated formal-memory grant, so Memory fails closed.
            assertThat(dependencies.memory.recalls).hasValue(0);
            assertThat(dependencies.memory.extractions).hasValue(0);
            assertThat(dependencies.llm.web).isEmpty();
        } finally {
            orchestrator.reset();
        }
    }

    @Test
    void noneSkipsEveryRetrievalLaneAndPassesNoRetrievedContextToLlm() {
        TrackingDependencies dependencies = new TrackingDependencies();
        TurnOrchestrator orchestrator = dependencies.orchestrator();
        TurnRequest request = new TurnRequest(
                "u-none", "website", "s-none", "look up current weather", RetrievalMode.NONE);

        try {
            orchestrator.runInline(request).block(Duration.ofSeconds(5));

            assertThat(dependencies.rag.searches).hasValue(0);
            assertThat(dependencies.memory.recalls).hasValue(0);
            assertThat(dependencies.memory.extractions).hasValue(0);
            assertThat(dependencies.web.policyChecks).hasValue(0);
            assertThat(dependencies.web.searches).hasValue(0);
            assertThat(dependencies.llm.canon).isEmpty();
            assertThat(dependencies.llm.memories).isEmpty();
            assertThat(dependencies.llm.web).isEmpty();
        } finally {
            orchestrator.reset();
        }
    }

    @Test
    void slowUsesExistingWebSearchPolicy() {
        TrackingDependencies dependencies = new TrackingDependencies();
        TurnOrchestrator orchestrator = dependencies.orchestrator();

        try {
            orchestrator.runInline(new TurnRequest(
                    "u-slow", "website", "s-slow-search", "look up the latest Meguri release",
                    RetrievalMode.SLOW)).block(Duration.ofSeconds(5));
            assertThat(dependencies.web.policyChecks).hasValue(1);
            assertThat(dependencies.web.searches).hasValue(1);
            assertThat(dependencies.llm.web).hasSize(1);

            orchestrator.runInline(new TurnRequest(
                    "u-slow", "website", "s-slow-chat", "陪我聊聊天",
                    RetrievalMode.SLOW)).block(Duration.ofSeconds(5));
            assertThat(dependencies.web.policyChecks).hasValue(2);
            assertThat(dependencies.web.searches).hasValue(1);
            assertThat(dependencies.llm.web).isEmpty();
        } finally {
            orchestrator.reset();
        }
    }

    @Test
    void loreFailureIsReportedAsUnavailableWithoutBlockingTheTurn() {
        TrackingDependencies dependencies = new TrackingDependencies();
        dependencies.rag.failure = new IllegalStateException("lore backend is down");
        TurnOrchestrator orchestrator = dependencies.orchestrator();
        TurnRequest request = new TurnRequest(
                "u-lore-down", "website", "s-lore-down", "tell me about Meguri", RetrievalMode.FAST);

        try {
            ChatResponse response = orchestrator.runInline(request).block(Duration.ofSeconds(5));

            assertThat(response).isNotNull();
            assertThat(dependencies.rag.searches).hasValue(1);
            assertThat(dependencies.llm.calls).hasValue(1);
            assertThat(dependencies.llm.canon).isEmpty();

            EventEnvelope completed = orchestrator.eventsFor(request.getSessionId()).stream()
                    .filter(event -> "retrieval.completed".equals(event.getType()))
                    .findFirst()
                    .orElseThrow();
            List<Map<String, Object>> lanes = MAPPER.convertValue(
                    completed.getData().get("lanes"),
                    new TypeReference<>() {});
            assertThat(lanes)
                    .filteredOn(lane -> "lore".equals(lane.get("lane")))
                    .singleElement()
                    .satisfies(lane -> {
                        assertThat(lane.get("status")).isEqualTo("unavailable");
                        assertThat(lane.get("result_count")).isEqualTo(0);
                    });
        } finally {
            orchestrator.reset();
        }
    }

    private static final class TrackingDependencies {
        private final TrackingLlm llm = new TrackingLlm();
        private final TrackingRag rag = new TrackingRag();
        private final TrackingMemory memory = new TrackingMemory();
        private final TrackingWeb web = new TrackingWeb();

        TurnOrchestrator orchestrator() {
            return new TurnOrchestrator(
                    llm, rag, new RuntimeStateMachine(), new ExpressionResolver(), Duration.ZERO,
                    MAPPER, memory, web);
        }
    }

    private static final class TrackingLlm implements LlmProvider {
        private final AtomicInteger calls = new AtomicInteger();
        private volatile List<String> canon = List.of();
        private volatile List<String> memories = List.of();
        private volatile List<String> web = List.of();

        @Override
        public Mono<LlmResponse> respond(
                TurnRequest request, RuntimeState state, List<String> canon,
                List<String> memories, List<String> recentContext) {
            return respond(request, state, canon, memories, recentContext, List.of());
        }

        @Override
        public Mono<LlmResponse> respond(
                TurnRequest request, RuntimeState state, List<String> canon,
                List<String> memories, List<String> recentContext, List<String> webResults) {
            calls.incrementAndGet();
            this.canon = List.copyOf(canon);
            this.memories = List.copyOf(memories);
            this.web = List.copyOf(webResults);
            return Mono.just(new LlmResponse("ok"));
        }
    }

    private static final class TrackingRag implements RagProvider {
        private final AtomicInteger searches = new AtomicInteger();
        private volatile RuntimeException failure;

        @Override
        public List<String> search(String query, RuntimeState state, int limit) {
            searches.incrementAndGet();
            if (failure != null) throw failure;
            return List.of("local lore");
        }
    }

    private static final class TrackingMemory implements MemoryGateway {
        private final AtomicInteger recalls = new AtomicInteger();
        private final AtomicInteger extractions = new AtomicInteger();

        @Override
        public Mono<MemoryRecall> recall(TurnRequest request) {
            recalls.incrementAndGet();
            return Mono.just(MemoryRecall.available(List.of("local memory")));
        }

        @Override
        public Mono<List<MemoryCandidate>> extract(TurnRequest request) {
            extractions.incrementAndGet();
            return Mono.just(List.of());
        }

        @Override
        public Mono<MemoryWriteResult> write(
                TurnRequest request, LlmResponse response, String turnId, String traceId) {
            return Mono.just(MemoryWriteResult.unavailable());
        }

        @Override
        public Mono<SessionSummaryResult> summarize(SessionSummaryRequest request) {
            return Mono.error(new UnsupportedOperationException("not used by retrieval mode tests"));
        }
    }

    private static final class TrackingWeb implements WebSearchGateway {
        private final AtomicInteger policyChecks = new AtomicInteger();
        private final AtomicInteger searches = new AtomicInteger();

        @Override
        public boolean shouldSearch(String message) {
            policyChecks.incrementAndGet();
            return WebSearchPolicy.shouldSearch(message);
        }

        @Override
        public Mono<WebSearchRecall> search(String query, int limit) {
            searches.incrementAndGet();
            return Mono.just(new WebSearchRecall(
                    "ok", "tracking-web",
                    List.of(new WebSearchResult("Meguri", "https://example.test", "latest release"))));
        }

        @Override
        public String providerName() {
            return "tracking-web";
        }
    }
}

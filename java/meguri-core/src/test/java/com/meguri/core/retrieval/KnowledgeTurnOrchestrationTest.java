package com.meguri.core.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.dto.LlmResponse;
import com.meguri.core.dto.RuntimeState;
import com.meguri.core.dto.TurnRequest;
import com.meguri.core.harness.retrieval.RetrievalMode;
import com.meguri.core.knowledge.DeterministicKnowledgeProjector;
import com.meguri.core.knowledge.InMemoryKnowledgeRepository;
import com.meguri.core.knowledge.KnowledgeAcl;
import com.meguri.core.knowledge.KnowledgeEvidenceValidator;
import com.meguri.core.knowledge.KnowledgeIngestionService;
import com.meguri.core.knowledge.SecretDetector;
import com.meguri.core.knowledge.SourcePage;
import com.meguri.core.llm.LlmProvider;
import com.meguri.core.rag.RagProvider;
import com.meguri.core.runtime.ExpressionResolver;
import com.meguri.core.runtime.RuntimeStateMachine;
import com.meguri.core.runtime.TurnOrchestrator;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeTurnOrchestrationTest {
    private static final Instant PUBLISHED_AT =
            Instant.parse("2026-07-01T00:00:00Z");

    @Test
    void frozenKnowledgeReachesLlmWithCitationAndCompleteTypedTrace() {
        InMemoryKnowledgeRepository repository = populatedRepository();
        InMemoryRetrievalTraceRepository traces =
                new InMemoryRetrievalTraceRepository();
        KnowledgeTurnRetrievalRuntime knowledge =
                new KnowledgeRetrievalConfiguration()
                        .meguriKnowledgeTurnRetrievalRuntime(
                                 new KnowledgeRepositoryProjectionAdapter(repository),
                                 traces, 2_000, 500, 512);
        CapturingLlm llm = new CapturingLlm();
        TurnOrchestrator orchestrator = orchestrator(llm);
        orchestrator.configureKnowledgeRetrieval(knowledge);
        TurnRequest request = new TurnRequest(
                "user", "website", "knowledge-session",
                "What is Project Aurora?", RetrievalMode.FAST);

        try {
            orchestrator.runInline(request).block(Duration.ofSeconds(5));

            assertThat(llm.canon)
                    .anySatisfy(line -> {
                        assertThat(line).contains("Project Aurora launches on Friday");
                        assertThat(line).contains("notion://page-aurora");
                    });
            var record = orchestrator.turns().values().iterator().next();
            assertThat(record.getManifest().knowledgeSnapshotId()).startsWith("ks1.");
            assertThat(record.getManifest().knowledgeRevision()).isPositive();
            assertThat(traces.find(record.getTraceId())).isPresent()
                    .get()
                    .satisfies(trace -> {
                        assertThat(trace.snapshotId())
                                .isEqualTo(record.getManifest().knowledgeSnapshotId());
                        assertThat(trace.items()).isNotEmpty();
                        assertThat(trace.ranks()).isNotEmpty();
                    });
            Map<String, Object> retrievalEvent = orchestrator
                    .eventsFor(request.getSessionId()).stream()
                    .filter(event -> "retrieval.completed".equals(event.getType()))
                    .findFirst()
                    .orElseThrow()
                    .getData();
            assertThat(retrievalEvent).containsKey("knowledge_trace");
        } finally {
            orchestrator.reset();
            knowledge.close();
        }
    }

    @Test
    void graphPathAndAclDenialNeverBlockBaseReply() {
        InMemoryKnowledgeRepository repository = populatedRepository();
        InMemoryRetrievalTraceRepository traces =
                new InMemoryRetrievalTraceRepository();
        KnowledgeTurnRetrievalRuntime knowledge =
                new KnowledgeRetrievalConfiguration()
                        .meguriKnowledgeTurnRetrievalRuntime(
                                 new KnowledgeRepositoryProjectionAdapter(repository),
                                 traces, 2_000, 500, 512);
        CapturingLlm allowedLlm = new CapturingLlm();
        TurnOrchestrator allowed = orchestrator(allowedLlm);
        allowed.configureKnowledgeRetrieval(knowledge);
        CapturingLlm deniedLlm = new CapturingLlm();
        TurnOrchestrator denied = orchestrator(deniedLlm);
        denied.configureKnowledgeRetrieval(knowledge);

        try {
            allowed.runInline(new TurnRequest(
                    "user", "website", "allowed-session",
                    "How is Alice related to Project Aurora?", RetrievalMode.FAST))
                    .block(Duration.ofSeconds(5));
            denied.runInline(new TurnRequest(
                    "other-user", "website", "denied-session",
                    "Project Aurora", RetrievalMode.FAST))
                    .block(Duration.ofSeconds(5));

            assertThat(allowedLlm.calls).isEqualTo(1);
            assertThat(allowedLlm.canon).anyMatch(line -> line.contains("Project Aurora"));
            String allowedTraceId =
                    allowed.turns().values().iterator().next().getTraceId();
            assertThat(traces.find(allowedTraceId)).isPresent()
                    .get()
                    .satisfies(trace -> {
                        assertThat(trace.lanes().get(SourceType.KNOWLEDGE).provider())
                                .isEqualTo("graph+hybrid");
                        assertThat(trace.items())
                                .anyMatch(item -> item.graphPath() != null);
                    });
            assertThat(deniedLlm.calls).isEqualTo(1);
            assertThat(deniedLlm.canon).isEmpty();
        } finally {
            allowed.reset();
            denied.reset();
            knowledge.close();
        }
    }

    private static InMemoryKnowledgeRepository populatedRepository() {
        InMemoryKnowledgeRepository repository = new InMemoryKnowledgeRepository();
        KnowledgeIngestionService ingestion = new KnowledgeIngestionService(
                repository,
                List.of(),
                new DeterministicKnowledgeProjector(),
                new KnowledgeEvidenceValidator(),
                new SecretDetector(),
                Clock.fixed(PUBLISHED_AT, ZoneOffset.UTC));
        ingestion.ingest(new SourcePage(
                "notion",
                "page-aurora",
                "Project Aurora",
                "Project Aurora launches on Friday.\n\n"
                        + "[[entity:person|Alice]] owns Project Aurora.",
                "a".repeat(64),
                PUBLISHED_AT.minusSeconds(60),
                new KnowledgeAcl("meguri-local", Set.of("user")),
                false,
                false));
        return repository;
    }

    private static TurnOrchestrator orchestrator(CapturingLlm llm) {
        RagProvider emptyLore = (query, state, limit) -> List.of();
        return new TurnOrchestrator(
                llm, emptyLore, new RuntimeStateMachine(), new ExpressionResolver(),
                Duration.ZERO, new ObjectMapper());
    }

    private static final class CapturingLlm implements LlmProvider {
        private int calls;
        private List<String> canon = List.of();

        @Override
        public Mono<LlmResponse> respond(
                TurnRequest request,
                RuntimeState state,
                List<String> canon,
                List<String> memories,
                List<String> recentContext) {
            return respond(request, state, canon, memories, recentContext, List.of());
        }

        @Override
        public Mono<LlmResponse> respond(
                TurnRequest request,
                RuntimeState state,
                List<String> canon,
                List<String> memories,
                List<String> recentContext,
                List<String> webResults) {
            calls++;
            this.canon = List.copyOf(canon);
            return Mono.just(new LlmResponse("base reply"));
        }
    }
}

package com.meguri.core.training;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.dto.ClientCapabilities;
import com.meguri.core.dto.ExpressionTag;
import com.meguri.core.dto.Intensity;
import com.meguri.core.dto.LlmResponse;
import com.meguri.core.dto.RuntimeState;
import com.meguri.core.dto.TurnRequest;
import com.meguri.core.dto.VoiceStyle;
import com.meguri.core.llm.LlmProvider;
import com.meguri.core.llm.ProviderRequest;
import com.meguri.core.memory.NoopMemoryGateway;
import com.meguri.core.runtime.ExpressionResolver;
import com.meguri.core.runtime.RuntimeStateMachine;
import com.meguri.core.runtime.TurnOrchestrator;
import com.meguri.core.websearch.NoopWebSearchGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TrainingFeedbackServiceTest {
    @TempDir Path tempDir;

    @Test
    void sampledPairIsEmittedAndExplicitSelectionBecomesLocalTrainingCandidate() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Path output = tempDir.resolve("behavior-feedback.jsonl");
        TrainingFeedbackService feedback = new TrainingFeedbackService(mapper, output, 1, 0, () -> 0);
        AtomicInteger calls = new AtomicInteger();
        LlmProvider llm = new LlmProvider() {
            @Override
            public Mono<LlmResponse> respond(TurnRequest request, RuntimeState state,
                                              List<String> canon, List<String> memories,
                                              List<String> recentContext) {
                boolean first = calls.getAndIncrement() == 0;
                return Mono.just(new LlmResponse(
                        first ? "先关心你一下。" : "这件事不可以继续，先停下来，我是担心你。",
                        first ? ExpressionTag.WORRIED : ExpressionTag.ANGRY,
                        first ? Intensity.LOW : Intensity.MEDIUM,
                        VoiceStyle.RESTRAINED,
                        List.of()));
            }
        };
        TurnOrchestrator orchestrator = new TurnOrchestrator(
                llm, (query, state, limit) -> List.of(),
                new RuntimeStateMachine(), new ExpressionResolver(), Duration.ZERO, mapper,
                new NoopMemoryGateway(), new NoopWebSearchGateway(), feedback);
        TurnRequest request = new TurnRequest(
                "user-a", "desktop_pet", "session-a", null, "我又做了危险的事",
                List.of(), new ClientCapabilities(), null, null, false, true);

        var result = orchestrator.runInline(request).block();

        assertThat(result).isNotNull();
        var event = orchestrator.eventsFor("session-a").stream()
                .filter(item -> "training.candidates.ready".equals(item.type()))
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> candidates =
                (List<java.util.Map<String, Object>>) event.data().get("candidates");
        String selectedId = String.valueOf(candidates.get(1).get("candidate_id"));

        var receipt = orchestrator.submitTrainingFeedback(
                new TrainingFeedbackRequest(result.turnId(), selectedId,
                        "这里应当先严肃制止，再表达关心。", true));

        assertThat(receipt.get("status")).isEqualTo("training_candidate_saved");
        assertThat(Files.readString(output)).contains("meguri-behavior-feedback-v2")
                .contains("这里应当先严肃制止")
                .contains(selectedId);
        assertThat(orchestrator.sessionMessages("user-a", "desktop_pet", "session-a").getLast().content())
                .isEqualTo("这件事不可以继续，先停下来，我是担心你。");
        assertThat(orchestrator.eventsFor("session-a").stream().filter(item -> "turn.completed".equals(item.type()))
                .findFirst().orElseThrow().data().get("reply")).isEqualTo("先关心你一下。");
    }

    @Test
    void configuredProbabilitiesControlSamplingWithoutClientAuthority() {
        ObjectMapper mapper = new ObjectMapper();
        TrainingFeedbackService feedback = new TrainingFeedbackService(
                mapper, tempDir.resolve("unused.jsonl"), 0.03, 0.001, () -> 0.002);

        assertThat(feedback.shouldCompare(true)).isTrue();
        assertThat(feedback.shouldCompare(false)).isFalse();
    }

    @Test
    void ordinaryChatNeverSamplesOrArchivesEvenWithLegacyNormalProbabilityEnabled() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Path output = tempDir.resolve("ordinary-chat.jsonl");
        var feedback = new TrainingFeedbackService(mapper, output, 1, 1, () -> 0);
        AtomicInteger calls = new AtomicInteger();
        LlmProvider llm = (request, state, canon, memories, recent) -> {
            calls.incrementAndGet();
            return Mono.just(new LlmResponse("ordinary reply"));
        };
        var orchestrator = new TurnOrchestrator(llm, null,
                new RuntimeStateMachine(), new ExpressionResolver(), Duration.ZERO, mapper,
                new NoopMemoryGateway(), new NoopWebSearchGateway(), feedback);
        var result = orchestrator.runInline(new TurnRequest("synthetic-user", "website", "ordinary", null,
                "hello", List.of(), new ClientCapabilities(), null, null, false, false)).block();
        assertThat(calls).hasValue(1);
        assertThat(feedback.hasComparison(result.turnId())).isFalse();
        assertThat(Files.exists(output)).isFalse();
        assertThat(orchestrator.eventsFor("ordinary")).noneMatch(event -> event.type().startsWith("training."));
        orchestrator.reset();
    }

    @Test
    void optionalArchiveFailureDoesNotFailAnOtherwiseSuccessfulReply() throws Exception {
        Path blocker = tempDir.resolve("blocker");
        Files.writeString(blocker, "not a directory");
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        var feedback = new TrainingFeedbackService(mapper, blocker.resolve("feedback.jsonl"), 0, 0, () -> 1);
        LlmProvider llm = (request, state, canon, memories, recent) -> Mono.just(new LlmResponse("reply remains available"));
        var orchestrator = new TurnOrchestrator(llm, (query, state, limit) -> List.of(),
                new RuntimeStateMachine(), new ExpressionResolver(), Duration.ZERO, mapper,
                new NoopMemoryGateway(), new NoopWebSearchGateway(), feedback);
        var result = orchestrator.runInline(new TurnRequest("user-a", "desktop_pet", "session-a", null,
                "hello", List.of(), new ClientCapabilities(), null, null, false, true)).block();
        assertThat(result).isNotNull();
        assertThat(result.response().reply()).isEqualTo("reply remains available");
        assertThat(orchestrator.eventsFor("session-a").getLast().type()).isEqualTo("turn.completed");
    }

    @Test
    void alternativeProviderFailureKeepsFirstReplyAndRegistersSingleTrainingObservation() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        var feedback = new TrainingFeedbackService(
                mapper, tempDir.resolve("alternative-failure.jsonl"), 1, 0, () -> 0);
        AtomicInteger alternativeCalls = new AtomicInteger();
        LlmProvider llm = new LlmProvider() {
            @Override
            public Mono<LlmResponse> respond(TurnRequest request, RuntimeState state,
                                              List<String> canon, List<String> memories,
                                              List<String> recentContext) {
                return Mono.just(new LlmResponse("first reply remains authoritative"));
            }

            @Override
            public Mono<LlmResponse> respondAlternative(ProviderRequest request) {
                alternativeCalls.incrementAndGet();
                return Mono.error(new IllegalStateException("alternative provider unavailable"));
            }
        };
        var orchestrator = new TurnOrchestrator(llm, (query, state, limit) -> List.of(),
                new RuntimeStateMachine(), new ExpressionResolver(), Duration.ZERO, mapper,
                new NoopMemoryGateway(), new NoopWebSearchGateway(), feedback);
        TurnRequest request = new TurnRequest("user-a", "desktop_pet", "alternative-failure", null,
                "hello", List.of(), new ClientCapabilities(), null, null, false, true);

        var result = orchestrator.runInline(request).block();

        assertThat(request.trainingMode()).isTrue();
        assertThat(alternativeCalls).hasValue(1);
        assertThat(result).isNotNull();
        assertThat(result.response().reply()).isEqualTo("first reply remains authoritative");
        assertThat(feedback.hasComparison(result.turnId())).isTrue();
        assertThat(orchestrator.eventsFor("alternative-failure").stream()
                .filter(event -> "training.candidates.ready".equals(event.type())).count()).isZero();
        assertThat(orchestrator.eventsFor("alternative-failure").getLast().type())
                .isEqualTo("turn.completed");
    }

    @Test
    void alternativeAndSingleObservationArchiveFailureStillDeliversFirstReply() throws Exception {
        Path blocker = tempDir.resolve("archive-blocker");
        Files.writeString(blocker, "not a directory");
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        var feedback = new TrainingFeedbackService(
                mapper, blocker.resolve("feedback.jsonl"), 1, 0, () -> 0);
        LlmProvider llm = new LlmProvider() {
            @Override
            public Mono<LlmResponse> respond(TurnRequest request, RuntimeState state,
                                              List<String> canon, List<String> memories,
                                              List<String> recentContext) {
                return Mono.just(new LlmResponse("reply survives archive failure"));
            }

            @Override
            public Mono<LlmResponse> respondAlternative(ProviderRequest request) {
                return Mono.error(new IllegalStateException("alternative provider unavailable"));
            }
        };
        var orchestrator = new TurnOrchestrator(llm, (query, state, limit) -> List.of(),
                new RuntimeStateMachine(), new ExpressionResolver(), Duration.ZERO, mapper,
                new NoopMemoryGateway(), new NoopWebSearchGateway(), feedback);
        TurnRequest request = new TurnRequest("user-a", "desktop_pet", "archive-failure", null,
                "hello", List.of(), new ClientCapabilities(), null, null, false, true);

        var result = orchestrator.runInline(request).block();

        assertThat(request.trainingMode()).isTrue();
        assertThat(result).isNotNull();
        assertThat(result.response().reply()).isEqualTo("reply survives archive failure");
        assertThat(orchestrator.eventsFor("archive-failure").getLast().type())
                .isEqualTo("turn.completed");
    }

    @Test
    void selectingAnOlderIdenticalReplyCannotReplaceANewerTurn() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        var feedback = new TrainingFeedbackService(mapper, tempDir.resolve("feedback.jsonl"), 1, 0, () -> 0);
        AtomicInteger calls = new AtomicInteger();
        LlmProvider llm = (request, state, canon, memories, recent) -> Mono.just(
                new LlmResponse(calls.getAndIncrement() % 2 == 0 ? "same original" : "alternative"));
        var orchestrator = new TurnOrchestrator(llm, (query, state, limit) -> List.of(),
                new RuntimeStateMachine(), new ExpressionResolver(), Duration.ZERO, mapper,
                new NoopMemoryGateway(), new NoopWebSearchGateway(), feedback);
        var request = new TurnRequest("user-a", "desktop_pet", "session-a", null,
                "hello", List.of(), new ClientCapabilities(), null, null, false, true);
        var first = orchestrator.runInline(request).block();
        var second = orchestrator.runInline(request).block();
        var event = orchestrator.eventsFor("session-a").stream().filter(item ->
                first.turnId().equals(item.turnId()) && "training.candidates.ready".equals(item.type())).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        var candidates = (List<java.util.Map<String, Object>>) event.data().get("candidates");
        orchestrator.submitTrainingFeedback(new TrainingFeedbackRequest(first.turnId(),
                String.valueOf(candidates.get(1).get("candidate_id")), "", true));
        assertThat(orchestrator.sessionMessages("user-a", "desktop_pet", "session-a").getLast().content()).isEqualTo("same original");
        assertThat(orchestrator.eventsFor("session-a").stream().filter(item ->
                second.turnId().equals(item.turnId()) && "turn.completed".equals(item.type())).findFirst().orElseThrow()
                .data().get("reply")).isEqualTo("same original");
    }
}

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
        assertThat(Files.readString(output)).contains("meguri-behavior-feedback-v1")
                .contains("这里应当先严肃制止")
                .contains(selectedId);
        assertThat(orchestrator.sessionMessages("user-a", "desktop_pet", "session-a").getLast().content())
                .isEqualTo("这件事不可以继续，先停下来，我是担心你。");
    }

    @Test
    void configuredProbabilitiesControlSamplingWithoutClientAuthority() {
        ObjectMapper mapper = new ObjectMapper();
        TrainingFeedbackService feedback = new TrainingFeedbackService(
                mapper, tempDir.resolve("unused.jsonl"), 0.03, 0.001, () -> 0.002);

        assertThat(feedback.shouldCompare(true)).isTrue();
        assertThat(feedback.shouldCompare(false)).isFalse();
    }
}

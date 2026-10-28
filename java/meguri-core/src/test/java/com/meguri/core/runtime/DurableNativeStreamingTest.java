package com.meguri.core.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.dto.LlmResponse;
import com.meguri.core.dto.RuntimeState;
import com.meguri.core.dto.TurnRequest;
import com.meguri.core.llm.LlmProvider;
import com.meguri.core.harness.TurnCommand;
import com.meguri.core.memory.NoopMemoryGateway;
import com.meguri.core.training.TrainingFeedbackService;
import com.meguri.core.weather.WeatherConversationService;
import com.meguri.core.websearch.NoopWebSearchGateway;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class DurableNativeStreamingTest {
    @Test
    void sameConversationWaitsForFailureAndBindsNextInputToItsOwnReply() throws Exception {
        var gate = Sinks.<LlmResponse>one();
        var inputs = new java.util.concurrent.CopyOnWriteArrayList<String>();
        LlmProvider provider = (request, state, canon, memories, recent) -> {
            inputs.add(request.getMessage());
            return request.getMessage().equals("first") ? gate.asMono()
                    : Mono.just(new LlmResponse("answer:" + request.getMessage()));
        };
        var runtime = runtime(provider, new InMemoryTurnJournal(mapper()));
        var first = runtime.start(syntheticRequest("serial", "first"));
        await().atMost(Duration.ofSeconds(3)).until(() -> inputs.size() == 1);
        var second = runtime.start(syntheticRequest("serial", "second"));
        // An independent conversation remains available while this one waits.
        var other = runtime.start(syntheticRequest("independent", "other"));
        other.getDone().get(3, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(inputs).containsExactly("first", "other");
        gate.tryEmitError(new IllegalStateException("synthetic provider failure"));
        second.getDone().get(3, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(first.getStatus()).isEqualTo(TurnStatus.FAILED);
        assertThat(second.getResult().response().reply()).isEqualTo("answer:second");
        assertThat(runtime.sessionMessages("synthetic", "website", "serial"))
                .extracting(SessionContextStore.Message::content).containsExactly("first", "second", "answer:second");
        assertThat(runtime.eventsFor("serial").stream().filter(e -> e.type().equals("turn.completed")))
                .singleElement().satisfies(e -> {
                    assertThat(e.getTurnId()).isEqualTo(second.getTurnId());
                    assertThat(e.data().get("reply")).isEqualTo("answer:second");
                });
        runtime.reset();
    }

    private static TurnRequest syntheticRequest(String session, String message) {
        return new TurnRequest("synthetic", "website", session, message, List.of(),
                new com.meguri.core.dto.ClientCapabilities(), null, null, false);
    }

    @Test
    void fallbackProviderKeepsOneCompleteTextDeltaAndDoesNotClaimNativeStreaming() {
        InMemoryTurnJournal journal = new InMemoryTurnJournal(mapper());
        LlmProvider provider = (request, state, canon, memories, recent) ->
                Mono.just(new LlmResponse("complete fallback reply"));
        TurnOrchestrator runtime = runtime(provider, journal);

        TurnRecord record = runtime.start(
                new TurnRequest("user", "website", "fallback-stream", "hello"));
        record.getDone().join();

        assertThat(record.getStatus()).isEqualTo(TurnStatus.COMPLETED);
        assertThat(journal.events("fallback-stream"))
                .filteredOn(event -> event.getType().equals("text.delta"))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getData()).containsEntry("delta", "complete fallback reply")
                            .containsEntry("native", false);
                });
        runtime.reset();
    }

    @Test
    void emptyNativeStreamFailsWithoutPublishingAnEmptyDelta() {
        InMemoryTurnJournal journal = new InMemoryTurnJournal(mapper());
        LlmProvider provider = new DelayedStreamingProvider(false) {
            @Override
            public Flux<String> stream(TurnRequest request, RuntimeState state,
                                       List<String> canon, List<String> memories,
                                       List<String> recentContext, List<String> webResults) {
                return Flux.empty();
            }
        };
        TurnOrchestrator runtime = runtime(provider, journal);

        TurnRecord record = runtime.start(
                new TurnRequest("user", "website", "empty-native-stream", "hello"));
        record.getDone().join();

        assertThat(record.getStatus()).isEqualTo(TurnStatus.FAILED);
        assertThat(journal.events("empty-native-stream"))
                .filteredOn(event -> event.getType().equals("text.delta")).isEmpty();
        assertThat(journal.events("empty-native-stream").stream()
                .map(event -> event.getType())).contains("turn.failed");
        runtime.reset();
    }

    @Test
    void persistsNativeDeltaBeforeProviderCompletesAndBeforeSemanticSideChannel() {
        InMemoryTurnJournal journal = new InMemoryTurnJournal(mapper());
        TurnOrchestrator runtime = runtime(new DelayedStreamingProvider(false), journal);

        TurnRecord record = runtime.start(new TurnRequest("user", "website", "native-stream", "hello"));

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            assertThat(journal.events("native-stream"))
                    .filteredOn(event -> event.getType().equals("text.delta"))
                    .isNotEmpty();
            assertThat(record.getDone()).isNotDone();
        });
        record.getDone().join();

        var types = journal.events("native-stream").stream().map(event -> event.getType()).toList();
        assertThat(types.indexOf("text.delta")).isLessThan(types.indexOf("semantic.completed"));
        assertThat(journal.events("native-stream"))
                .filteredOn(event -> event.getType().equals("text.delta"))
                .allSatisfy(event -> {
                    assertThat(event.getData()).containsEntry("native", true);
                    assertThat(String.valueOf(event.getData().get("delta")).length()).isLessThanOrEqualTo(80);
                });
        assertThat(record.getStatus()).isEqualTo(TurnStatus.COMPLETED);
        runtime.reset();
    }

    @Test
    void providerInterruptionKeepsPartialTextAndUsesStableFailureCode() {
        InMemoryTurnJournal journal = new InMemoryTurnJournal(mapper());
        TurnOrchestrator runtime = runtime(new DelayedStreamingProvider(true), journal);

        TurnRecord record = runtime.start(new TurnRequest("user", "website", "broken-stream", "hello"));
        record.getDone().join();

        assertThat(record.getStatus()).isEqualTo(TurnStatus.FAILED);
        assertThat(record.getFailureCode()).isEqualTo("PROVIDER_STREAM_INTERRUPTED");
        assertThat(journal.events("broken-stream"))
                .extracting(event -> event.getType())
                .contains("text.delta", "turn.failed")
                .doesNotContain("text.completed", "turn.completed");
        assertThat(journal.events("broken-stream").getLast().getData())
                .containsEntry("failure_code", "PROVIDER_STREAM_INTERRUPTED");
        runtime.reset();
    }

    @Test
    void retryCreatesNewTurnAndPreservesFailedPredecessor() {
        InMemoryTurnJournal journal = new InMemoryTurnJournal(mapper());
        TurnOrchestrator runtime = runtime(new DelayedStreamingProvider(true), journal);
        TurnRequest request = new TurnRequest("user", "website", "retry-stream", "hello");
        TurnRecord failed = runtime.start(request, "first-attempt");
        failed.getDone().join();

        TurnRecord retry = runtime.retry(request, "second-attempt", failed.getTurnId());
        TurnRecord replay = runtime.retry(request, "second-attempt", failed.getTurnId());

        assertThat(retry.getTurnId()).isNotEqualTo(failed.getTurnId());
        assertThat(replay).isSameAs(retry);
        assertThat(retry.getRetryOfTurnId()).isEqualTo(failed.getTurnId());
        assertThat(failed.getFailureCode()).isEqualTo("PROVIDER_STREAM_INTERRUPTED");
        retry.getDone().join();
        runtime.reset();
    }

    @Test
    void cancellationWinsAgainstLateProviderCallbacksAndWritesOneTerminalEvent() {
        InMemoryTurnJournal journal = new InMemoryTurnJournal(mapper());
        ControllableStreamingProvider provider = new ControllableStreamingProvider(false);
        TurnOrchestrator runtime = runtime(provider, journal);
        TurnRecord record = runtime.start(new TurnRequest("user", "website", "cancel-race", "hello"));

        provider.emit("before cancel");
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(journal.events("cancel-race"))
                .filteredOn(event -> event.getType().equals("text.delta")).hasSize(1));
        runtime.cancel(record.getTurnId());
        provider.emit("late token");
        provider.complete();
        record.getDone().join();

        assertThat(record.getStatus()).isEqualTo(TurnStatus.CANCELLED);
        assertThat(journal.events("cancel-race"))
                .filteredOn(event -> event.getType().equals("text.delta")).hasSize(1);
        assertThat(journal.events("cancel-race").stream()
                .filter(event -> event.getType().equals("turn.cancelled")
                        || event.getType().equals("turn.failed")
                        || event.getType().equals("turn.completed")))
                .hasSize(1);
        runtime.reset();
    }

    @Test
    void semanticFinalizerFailureDoesNotRewriteCompletedBodyAsFailure() {
        InMemoryTurnJournal journal = new InMemoryTurnJournal(mapper());
        ControllableStreamingProvider provider = new ControllableStreamingProvider(true);
        TurnOrchestrator runtime = runtime(provider, journal);
        TurnRecord record = runtime.start(new TurnRequest("user", "website", "side-channel", "hello"));

        provider.emit("complete body");
        provider.complete();
        record.getDone().join();

        assertThat(record.getStatus()).isEqualTo(TurnStatus.COMPLETED);
        assertThat(record.getResult().response().getReply()).isEqualTo("complete body");
        assertThat(journal.events("side-channel")).extracting(event -> event.getType())
                .contains("text.completed", "turn.completed")
                .doesNotContain("turn.failed");
        runtime.reset();
    }

    @Test
    void deadlineWinsNativeStreamWithStableCodeAndNoLateToken() {
        InMemoryTurnJournal journal = new InMemoryTurnJournal(mapper());
        ControllableStreamingProvider provider = new ControllableStreamingProvider(false);
        TurnOrchestrator runtime = runtime(provider, journal);
        TurnRequest request = new TurnRequest("user", "website", "deadline-race", "hello");
        var snapshot = runtime.submit(new TurnCommand.Start(
                request, "deadline-attempt", Instant.now().plusMillis(250))).block();
        TurnRecord record = journal.turn(snapshot.turnId());

        provider.emit("partial before deadline");
        record.getDone().join();
        provider.emit("late token");

        assertThat(record.getStatus()).isEqualTo(TurnStatus.FAILED);
        assertThat(record.getFailureCode()).isEqualTo("TURN_DEADLINE_EXCEEDED");
        assertThat(journal.events("deadline-race"))
                .filteredOn(event -> event.getType().equals("text.delta")).hasSize(1);
        assertThat(journal.events("deadline-race").getLast().getData())
                .containsEntry("failure_code", "TURN_DEADLINE_EXCEEDED");
        runtime.reset();
    }

    private static TurnOrchestrator runtime(LlmProvider provider, TurnJournal journal) {
        ObjectMapper mapper = mapper();
        return new TurnOrchestrator(
                provider, null, new RuntimeStateMachine(), new ExpressionResolver(),
                Duration.ofMillis(1), mapper, new NoopMemoryGateway(),
                new NoopWebSearchGateway(), TrainingFeedbackService.disabled(mapper),
                WeatherConversationService.disabled(), journal);
    }

    private static ObjectMapper mapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    private static class DelayedStreamingProvider implements LlmProvider {
        private final boolean fail;

        private DelayedStreamingProvider(boolean fail) {
            this.fail = fail;
        }

        @Override
        public Mono<LlmResponse> respond(TurnRequest request, RuntimeState state,
                                         List<String> canon, List<String> memories,
                                         List<String> recentContext) {
            return Mono.error(new AssertionError("native path must not call respond"));
        }

        @Override
        public boolean supportsNativeStreaming() {
            return true;
        }

        @Override
        public Flux<String> stream(TurnRequest request, RuntimeState state,
                                   List<String> canon, List<String> memories,
                                   List<String> recentContext, List<String> webResults) {
            Flux<String> partial = Flux.just("visible before completion")
                    .delayElements(Duration.ofMillis(10));
            if (fail) {
                return partial.concatWith(Mono.delay(Duration.ofMillis(120))
                        .thenMany(Flux.error(new IllegalStateException("provider disconnected"))));
            }
            return partial.concatWith(Mono.delay(Duration.ofMillis(300)).thenMany(Flux.empty()));
        }
    }

    private static final class ControllableStreamingProvider implements LlmProvider {
        private final Sinks.Many<String> tokens = Sinks.many().unicast().onBackpressureBuffer();
        private final boolean failFinalizer;

        private ControllableStreamingProvider(boolean failFinalizer) {
            this.failFinalizer = failFinalizer;
        }

        void emit(String token) {
            tokens.tryEmitNext(token);
        }

        void complete() {
            tokens.tryEmitComplete();
        }

        @Override
        public Mono<LlmResponse> respond(TurnRequest request, RuntimeState state,
                                         List<String> canon, List<String> memories,
                                         List<String> recentContext) {
            return Mono.error(new AssertionError("native path must not call respond"));
        }

        @Override public boolean supportsNativeStreaming() { return true; }

        @Override
        public Flux<String> stream(TurnRequest request, RuntimeState state,
                                   List<String> canon, List<String> memories,
                                   List<String> recentContext, List<String> webResults) {
            return tokens.asFlux();
        }

        @Override
        public Mono<LlmResponse> finalizeStream(String reply, TurnRequest request, RuntimeState state) {
            return failFinalizer
                    ? Mono.error(new IllegalStateException("semantic classifier unavailable"))
                    : Mono.just(new LlmResponse(reply));
        }
    }
}

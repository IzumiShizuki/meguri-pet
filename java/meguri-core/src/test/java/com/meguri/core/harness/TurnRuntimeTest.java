package com.meguri.core.harness;

import com.meguri.core.dto.ClientCapabilities;
import com.meguri.core.dto.EventEnvelope;
import com.meguri.core.dto.TurnRequest;
import com.meguri.core.runtime.TurnEventTypes;
import com.meguri.core.runtime.TurnOrchestrator;
import com.meguri.core.harness.capability.EffectLedger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TurnRuntimeTest {
    private final TurnOrchestrator runtime = new TurnOrchestrator();

    @AfterEach
    void tearDown() {
        runtime.reset();
    }

    @Test
    void freezesManifestAndUsesOneObservableLifecycle() {
        TurnRequest request = request("manifest-session", "hello");
        TurnSnapshot accepted = runtime.submit(new TurnCommand.Start(
                request, "operation-1", Instant.now().plusSeconds(10))).block();

        assertThat(accepted).isNotNull();
        assertThat(accepted.manifest()).isNotNull();
        assertThat(accepted.manifest().protocolVersion()).isEqualTo("1.0");
        assertThat(accepted.manifest().grantedCapabilities())
                .contains("memory.write", "weather.read")
                .doesNotContain("lore.read", "memory.read", "knowledge.read", "web.read");

        StepVerifier.create(runtime.events(new EventCursor(
                        request.getSessionId(), 0L, accepted.turnId())))
                .recordWith(java.util.ArrayList::new)
                .thenConsumeWhile(event -> !TurnEventTypes.isTerminal(event.getType()))
                .expectNextMatches(event -> TurnEventTypes.isTerminal(event.getType()))
                .verifyComplete();

        TurnSnapshot completed = runtime.snapshot(accepted.turnId()).block();
        assertThat(completed).isNotNull();
        assertThat(completed.status()).isEqualTo("completed");
        assertThat(completed.stage()).isEqualTo("completed");
        assertThat(completed.manifest()).isEqualTo(accepted.manifest());
        assertThat(completed.result()).isNotNull();
        assertThat(runtime.effectReceipts(accepted.turnId()))
                .extracting(EffectLedger.Receipt::capabilityId)
                .containsExactly("weather.read");
        assertThat(runtime.effectReceipts(accepted.turnId()))
                .allSatisfy(receipt -> assertThat(receipt.status())
                        .isEqualTo(EffectLedger.Status.COMPLETED));
    }

    @Test
    void replayKeepsEventIdentityStable() {
        TurnSnapshot accepted = runtime.submit(new TurnCommand.Start(
                request("replay-session", "hello"), "operation-2")).block();
        runtime.turn(accepted.turnId()).getDone().join();

        List<EventEnvelope> events = runtime.events(new EventCursor("replay-session", 0L))
                .collectList().block();
        List<String> first = events.stream().map(EventEnvelope::getEventId).toList();
        List<String> second = runtime.events(new EventCursor("replay-session", 0L))
                .map(event -> event.getEventId()).collectList().block();

        assertThat(first).isNotEmpty().isEqualTo(second);
        assertThat(events).extracting(EventEnvelope::getSequence)
                .containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1, events.size()).boxed().toList());
        assertThat(events.stream().filter(event -> TurnEventTypes.isTerminal(event.getType())).toList())
                .hasSize(1);
        assertThat(events.getLast().getType()).isEqualTo("turn.completed");
        assertThat(events).filteredOn(event -> TurnEventTypes.isRequired(event.getType()))
                .allSatisfy(event -> assertThat(event.isRequired()).isTrue());
        assertThat(events).filteredOn(event -> !TurnEventTypes.isRequired(event.getType()))
                .allSatisfy(event -> assertThat(event.isRequired()).isFalse());
        assertThat(events).filteredOn(event -> "turn.stage.changed".equals(event.getType()))
                .extracting(event -> event.getData().get("stage"))
                .containsExactly("retrieving", "generating", "finalizing");
        assertThat(events).filteredOn(event -> "retrieval.completed".equals(event.getType()))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.isRequired()).isFalse();
                    assertThat(event.getData().get("trace_id")).isEqualTo(accepted.manifest() == null
                            ? null : runtime.turn(accepted.turnId()).getTraceId());
                    assertThat(String.valueOf(event.getData())).doesNotContain("hello");
                });
    }

    @Test
    void replayIsIsolatedWhenDifferentUsersReuseTheSameSessionId() {
        TurnSnapshot first = runtime.submit(new TurnCommand.Start(
                request("user-a", "shared-session", "first"), "operation-a")).block();
        TurnSnapshot second = runtime.submit(new TurnCommand.Start(
                request("user-b", "shared-session", "second"), "operation-b")).block();
        runtime.turn(first.turnId()).getDone().join();
        runtime.turn(second.turnId()).getDone().join();

        List<EventEnvelope> firstEvents = runtime.events(new EventCursor(
                        "shared-session", 0L, null, "user-a", "website"))
                .collectList().block();

        assertThat(firstEvents).isNotEmpty()
                .allSatisfy(event -> assertThat(event.getTurnId()).isEqualTo(first.turnId()));
    }

    private static TurnRequest request(String sessionId, String message) {
        return request("u-test", sessionId, message);
    }

    private static TurnRequest request(String userId, String sessionId, String message) {
        return new TurnRequest(userId, "website", sessionId, message,
                List.of(), new ClientCapabilities(true, true, false, false), null, null, true);
    }
}

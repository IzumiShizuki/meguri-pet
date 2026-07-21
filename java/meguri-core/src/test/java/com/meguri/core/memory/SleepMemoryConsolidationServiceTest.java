package com.meguri.core.memory;

import com.meguri.core.dto.LlmResponse;
import com.meguri.core.dto.MemoryCandidate;
import com.meguri.core.dto.TurnRequest;
import com.meguri.core.runtime.SessionContextStore;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SleepMemoryConsolidationServiceTest {
    @Test
    void persistsOnlyBoundedRedactedSessionSummary() {
        SessionContextStore.Snapshot snapshot = new SessionContextStore.Snapshot(
                "user-a", "desktop_pet", "session-a", List.of(
                new SessionContextStore.Message("user", "今天继续做 Meguri"),
                new SessionContextStore.Message("assistant", "好的，我会整理重点"),
                new SessionContextStore.Message("user", "api_key=do-not-store"),
                new SessionContextStore.Message("assistant", "我不会保存凭据")));
        StubMemoryGateway memory = new StubMemoryGateway();
        SleepMemoryConsolidationService service = new SleepMemoryConsolidationService(
                () -> List.of(snapshot), memory, true, ZoneId.of("Asia/Shanghai"), 2, 4,
                Clock.fixed(Instant.parse("2026-07-21T18:10:00Z"), ZoneOffset.UTC));

        SleepMemoryReport report = service.consolidateNow().block();

        assertThat(report.snapshotsSeen()).isEqualTo(1);
        assertThat(report.summariesPersisted()).isEqualTo(1);
        assertThat(report.messagesRedacted()).isEqualTo(1);
        assertThat(memory.last.get().messages()).extracting(SessionContextStore.Message::content)
                .contains("[已省略可能包含敏感凭据的消息]")
                .doesNotContain("api_key=do-not-store");
    }

    private static final class StubMemoryGateway implements MemoryGateway {
        private final AtomicReference<SessionSummaryRequest> last = new AtomicReference<>();

        @Override public Mono<MemoryRecall> recall(TurnRequest request) { return Mono.just(MemoryRecall.unavailable()); }
        @Override public Mono<List<MemoryCandidate>> extract(TurnRequest request) { return Mono.just(List.of()); }
        @Override public Mono<MemoryWriteResult> write(TurnRequest request, LlmResponse response, String turnId, String traceId) {
            return Mono.just(MemoryWriteResult.unavailable());
        }
        @Override public Mono<SessionSummaryResult> summarize(SessionSummaryRequest request) {
            last.set(request);
            return Mono.just(new SessionSummaryResult("persisted", request.userId(), request.clientId(),
                    request.sessionId(), "摘要", request.messages().size()));
        }
    }
}

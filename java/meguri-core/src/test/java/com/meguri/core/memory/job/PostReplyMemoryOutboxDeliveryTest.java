package com.meguri.core.memory.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.adapter.domain.ReplayPolicy;
import com.meguri.core.dto.ChatResponse;
import com.meguri.core.dto.EventEnvelope;
import com.meguri.core.dto.EventMetadata;
import com.meguri.core.dto.ExpressionTag;
import com.meguri.core.dto.Intensity;
import com.meguri.core.dto.LlmResponse;
import com.meguri.core.dto.MemoryStatus;
import com.meguri.core.dto.Mode;
import com.meguri.core.dto.Relationship;
import com.meguri.core.dto.ResolvedExpression;
import com.meguri.core.dto.RuntimeState;
import com.meguri.core.dto.TurnRequest;
import com.meguri.core.runtime.InMemoryTurnJournal;
import com.meguri.core.runtime.TurnRecord;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PostReplyMemoryOutboxDeliveryTest {
    private static final Instant NOW = Instant.parse("2026-07-29T00:00:00Z");

    @Test
    void completedEventCreatesOneDeterministicMemoryJobAcrossRedelivery() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        InMemoryTurnJournal journal = new InMemoryTurnJournal(mapper);
        InMemoryPostReplyMemoryJobStore store = new InMemoryPostReplyMemoryJobStore();
        PostReplyMemoryJobEnqueuer enqueuer = new PostReplyMemoryJobEnqueuer(
                store, mapper, Clock.fixed(NOW, ZoneOffset.UTC));
        PostReplyMemoryOutboxDelivery delivery =
                new PostReplyMemoryOutboxDelivery(journal, enqueuer);
        TurnRecord record = completedRecord(journal, MemoryStatus.PENDING);
        EventEnvelope event = completedEvent(record);

        delivery.deliver(event.getEventId(), event);
        delivery.deliver(event.getEventId(), event);

        PostReplyMemoryJobStore.EnqueueResult duplicate = enqueuer.enqueueFromEvent(
                event.getEventId(), record.getTurnId(), record.getRequest(),
                record.getResult().getResponse(), record.getTraceId(), false,
                PostReplyMemoryJob.CancellationPolicy.PROCESS_COMPLETED_REPLY);
        assertThat(duplicate.created()).isFalse();
        assertThat(duplicate.job().jobId()).startsWith("memory-event-");
        assertThat(duplicate.job().turnId()).isEqualTo(record.getTurnId());
    }

    @Test
    void unavailableMemoryCompletionDoesNotCreateAJob() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        InMemoryTurnJournal journal = new InMemoryTurnJournal(mapper);
        InMemoryPostReplyMemoryJobStore store = new InMemoryPostReplyMemoryJobStore();
        PostReplyMemoryJobEnqueuer enqueuer = new PostReplyMemoryJobEnqueuer(
                store, mapper, Clock.fixed(NOW, ZoneOffset.UTC));
        TurnRecord record = completedRecord(journal, MemoryStatus.UNAVAILABLE);

        new PostReplyMemoryOutboxDelivery(journal, enqueuer)
                .deliver(completedEvent(record).getEventId(), completedEvent(record));

        PostReplyMemoryJobStore.EnqueueResult first = enqueuer.enqueueFromEvent(
                "event-probe", record.getTurnId(), record.getRequest(),
                record.getResult().getResponse(), record.getTraceId(), false,
                PostReplyMemoryJob.CancellationPolicy.PROCESS_COMPLETED_REPLY);
        assertThat(first.created()).isTrue();
    }

    private static TurnRecord completedRecord(
            InMemoryTurnJournal journal, MemoryStatus memoryStatus) {
        TurnRequest request = new TurnRequest(
                "memory-user", "website", "memory-session", "remember this",
                List.of(), new com.meguri.core.dto.ClientCapabilities(), null, null, true);
        TurnRecord record = journal.create(request, Instant.now().plusSeconds(30));
        RuntimeState state = new RuntimeState(
                "website", Mode.PRIVATE, Relationship.SIBLING, "default",
                "08:00", false, false, false, List.of(ExpressionTag.NEUTRAL));
        ChatResponse response = new ChatResponse(
                record.getTurnId(), request.getSessionId(), new LlmResponse("reply"), state,
                new ResolvedExpression(ExpressionTag.NEUTRAL, Intensity.LOW, "default"),
                memoryStatus, "test-build");
        assertThat(record.tryComplete(response)).isTrue();
        return record;
    }

    private static EventEnvelope completedEvent(TurnRecord record) {
        return new EventEnvelope(
                EventEnvelope.CURRENT_PROTOCOL_VERSION, "event-completed", true, null,
                "turn.completed", record.getTurnId(), record.getRequest().getSessionId(),
                1L, ReplayPolicy.STATE, NOW, Map.of("reply", "reply"),
                new EventMetadata(record.getTraceId(), "test", NOW, "test-build"));
    }
}

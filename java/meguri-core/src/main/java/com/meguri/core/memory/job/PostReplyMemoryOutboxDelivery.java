package com.meguri.core.memory.job;

import com.meguri.core.dto.ChatResponse;
import com.meguri.core.dto.EventEnvelope;
import com.meguri.core.dto.MemoryStatus;
import com.meguri.core.runtime.TurnJournal;
import com.meguri.core.runtime.TurnOutboxDispatcher;
import com.meguri.core.runtime.TurnRecord;
import com.meguri.core.runtime.TurnStatus;

import java.util.Objects;

/** Creates the post-reply Memory Job from the transactional Turn outbox. */
public final class PostReplyMemoryOutboxDelivery implements TurnOutboxDispatcher.Delivery {
    private final TurnJournal journal;
    private final PostReplyMemoryJobEnqueuer enqueuer;

    public PostReplyMemoryOutboxDelivery(
            TurnJournal journal, PostReplyMemoryJobEnqueuer enqueuer) {
        this.journal = Objects.requireNonNull(journal, "journal");
        this.enqueuer = Objects.requireNonNull(enqueuer, "enqueuer");
    }

    @Override
    public void deliver(String eventId, EventEnvelope event) {
        Objects.requireNonNull(event, "event");
        if (!"turn.completed".equals(event.getType())) return;
        if (!event.getEventId().equals(eventId)) {
            throw new IllegalArgumentException("outbox event id does not match its envelope");
        }

        TurnRecord record = journal.turn(event.getTurnId());
        if (record == null) {
            throw new IllegalStateException("completed Turn is not readable from the authority");
        }
        ChatResponse result = record.getResult();
        if (record.getStatus() != TurnStatus.COMPLETED || result == null) {
            throw new IllegalStateException("completed Turn outbox snapshot is not yet readable");
        }
        if (result.getMemoryStatus() != MemoryStatus.PENDING) return;

        enqueuer.enqueueFromEvent(
                eventId, record.getTurnId(), record.getRequest(), result.getResponse(),
                record.getTraceId(), false,
                PostReplyMemoryJob.CancellationPolicy.PROCESS_COMPLETED_REPLY);
    }
}

package com.meguri.core.runtime;

import com.meguri.core.dto.EventEnvelope;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Claims durable event deliveries and records ack/retry/dead-letter with owner CAS. */
public final class TurnOutboxDispatcher implements AutoCloseable {
    private final TurnJournal journal;
    private final String ownerId;
    private final Delivery delivery;
    private final ScheduledExecutorService heartbeats;
    private final Duration lease;
    private final int batchSize;
    private final int deadLetterAfter;
    private final AtomicBoolean closed = new AtomicBoolean();

    public TurnOutboxDispatcher(TurnJournal journal, String ownerId,
                                Consumer<EventEnvelope> delivery) {
        this(journal, ownerId, (eventId, event) -> delivery.accept(event),
                Duration.ofSeconds(30), 64, 8);
    }

    public TurnOutboxDispatcher(TurnJournal journal, String ownerId,
                                Consumer<EventEnvelope> delivery, Duration lease,
                                int batchSize, int deadLetterAfter) {
        this(journal, ownerId, (eventId, event) -> delivery.accept(event),
                lease, batchSize, deadLetterAfter);
    }

    public TurnOutboxDispatcher(TurnJournal journal, String ownerId,
                                Delivery delivery, Duration lease,
                                int batchSize, int deadLetterAfter) {
        this.journal = Objects.requireNonNull(journal, "journal");
        this.ownerId = required(ownerId);
        this.delivery = Objects.requireNonNull(delivery, "delivery");
        this.lease = Objects.requireNonNull(lease, "lease");
        if (lease.isZero() || lease.isNegative() || batchSize < 1 || deadLetterAfter < 1) {
            throw new IllegalArgumentException("dispatcher limits must be positive");
        }
        this.batchSize = batchSize;
        this.deadLetterAfter = deadLetterAfter;
        this.heartbeats = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "turn-outbox-heartbeat-" + this.ownerId);
            thread.setDaemon(true);
            return thread;
        });
    }

    public int dispatchOnce() {
        if (closed.get()) return 0;
        int delivered = 0;
        for (int index = 0; index < batchSize; index++) {
            if (closed.get()) break;
            List<TurnJournal.OutboxMessage> claimed = journal.claimOutbox(ownerId, 1, lease);
            if (claimed.isEmpty()) break;
            TurnJournal.OutboxMessage message = claimed.getFirst();
            AtomicBoolean leaseLost = new AtomicBoolean();
            long heartbeatMillis = Math.max(10L, lease.toMillis() / 3L);
            ScheduledFuture<?> heartbeat = heartbeats.scheduleAtFixedRate(() -> {
                if (!journal.heartbeatOutbox(message.outboxId(), ownerId, lease)) {
                    leaseLost.set(true);
                }
            }, heartbeatMillis, heartbeatMillis, TimeUnit.MILLISECONDS);
            try {
                if (!journal.heartbeatOutbox(message.outboxId(), ownerId, lease)) continue;
                delivery.deliver(message.event().getEventId(), message.event());
                if (!leaseLost.get()
                        && journal.acknowledgeOutbox(message.outboxId(), ownerId)) delivered++;
            } catch (Throwable error) {
                if (!leaseLost.get()) {
                    long exponent = Math.min(6, Math.max(0, message.attempts()));
                    Instant retryAt = Instant.now().plusSeconds(1L << exponent);
                    journal.retryOutbox(message.outboxId(), ownerId, safeMessage(error), retryAt, deadLetterAfter);
                }
            } finally {
                heartbeat.cancel(false);
            }
        }
        return delivered;
    }

    @Override
    public void close() {
        closed.set(true);
        heartbeats.shutdownNow();
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("ownerId must not be blank");
        return value.trim();
    }

    private static String safeMessage(Throwable error) {
        String value = error == null ? null : error.getMessage();
        return value == null || value.isBlank() ? "outbox delivery failed" : value.substring(0, Math.min(1000, value.length()));
    }

    /** The event id is the durable consumer idempotency key for at-least-once delivery. */
    @FunctionalInterface
    public interface Delivery {
        void deliver(String eventId, EventEnvelope event);
    }
}

package com.meguri.core.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.dto.EventEnvelope;
import com.meguri.core.dto.EventMetadata;
import com.meguri.core.dto.TurnRequest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TurnOutboxDispatcherTest {
    @Test
    void acknowledgesClaimedMessageOnlyAfterDelivery() {
        FakeOutboxJournal journal = new FakeOutboxJournal();
        List<EventEnvelope> delivered = new ArrayList<>();
        TurnOutboxDispatcher dispatcher = new TurnOutboxDispatcher(journal, "worker-a", delivered::add);

        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        assertThat(delivered).containsExactly(journal.event);
        assertThat(journal.status).isEqualTo("delivered");
        assertThat(journal.owner).isNull();
        assertThat(journal.lastClaimLimit).isEqualTo(1);
    }

    @Test
    void retriesThenDeadLettersPoisonMessageWithoutWrongOwnerAck() {
        FakeOutboxJournal journal = new FakeOutboxJournal();
        TurnOutboxDispatcher dispatcher = new TurnOutboxDispatcher(
                journal, "worker-a", event -> { throw new IllegalStateException("broker unavailable"); },
                Duration.ofSeconds(5), 1, 2);

        assertThat(dispatcher.dispatchOnce()).isZero();
        assertThat(journal.status).isEqualTo("pending");
        assertThat(journal.attempts).isEqualTo(1);
        assertThat(journal.acknowledgeOutbox(1, "worker-b")).isFalse();

        journal.availableAt = Instant.EPOCH;
        assertThat(dispatcher.dispatchOnce()).isZero();
        assertThat(journal.status).isEqualTo("dead_letter");
        assertThat(journal.attempts).isEqualTo(2);
    }

    @Test
    void keepsLeaseAliveDuringSlowDelivery() {
        FakeOutboxJournal journal = new FakeOutboxJournal();
        TurnOutboxDispatcher dispatcher = new TurnOutboxDispatcher(
                journal, "worker-a", event -> sleep(120), Duration.ofMillis(30), 1, 2);

        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
        assertThat(journal.status).isEqualTo("delivered");
        dispatcher.close();
    }

    @Test
    void expiredClaimCanBeReclaimedButOldOwnerCannotAck() {
        FakeOutboxJournal journal = new FakeOutboxJournal();
        assertThat(journal.claimOutbox("worker-a", 1, Duration.ofMillis(5))).hasSize(1);
        sleep(15);

        assertThat(journal.claimOutbox("worker-b", 1, Duration.ofSeconds(1))).hasSize(1);
        assertThat(journal.acknowledgeOutbox(1, "worker-a")).isFalse();
        assertThat(journal.acknowledgeOutbox(1, "worker-b")).isTrue();
    }

    @Test
    void suppliesStableEventIdAsConsumerIdempotencyKey() {
        FakeOutboxJournal journal = new FakeOutboxJournal();
        List<String> keys = new ArrayList<>();
        TurnOutboxDispatcher dispatcher = new TurnOutboxDispatcher(
                journal, "worker-a", (eventId, event) -> keys.add(eventId),
                Duration.ofSeconds(1), 1, 2);

        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
        assertThat(keys).containsExactly(journal.event.getEventId());
        dispatcher.close();
    }

    @Test
    void deadLetterCanBeInspectedAndExplicitlyRequeued() {
        FakeOutboxJournal journal = new FakeOutboxJournal();
        TurnOutboxDispatcher dispatcher = new TurnOutboxDispatcher(
                journal, "worker-a", event -> { throw new IllegalStateException("poison"); },
                Duration.ofSeconds(1), 1, 1);

        assertThat(dispatcher.dispatchOnce()).isZero();
        assertThat(journal.deadLetterOutbox(10)).singleElement()
                .satisfies(message -> assertThat(message.event()).isEqualTo(journal.event));
        assertThat(journal.requeueOutbox(1, Instant.EPOCH)).isTrue();
        assertThat(journal.status).isEqualTo("pending");
        assertThat(journal.attempts).isZero();
    }

    private static final class FakeOutboxJournal implements TurnJournal {
        private final InMemoryTurnJournal delegate =
                new InMemoryTurnJournal(new ObjectMapper().findAndRegisterModules());
        private final EventEnvelope event;
        private String status = "pending";
        private String owner;
        private int attempts;
        private Instant availableAt = Instant.EPOCH;
        private Instant leaseUntil = Instant.EPOCH;
        private int lastClaimLimit;

        private FakeOutboxJournal() {
            TurnRecord record = delegate.create(
                    new TurnRequest("user", "website", "outbox", "hello"),
                    Instant.now().plusSeconds(30));
            event = delegate.append(record, "turn.started", Map.of(),
                    new EventMetadata(record.getTraceId(), "test", Instant.now(), "test"));
        }

        @Override
        public List<OutboxMessage> claimOutbox(String ownerId, int limit, Duration lease) {
            lastClaimLimit = limit;
            boolean pending = "pending".equals(status) && !availableAt.isAfter(Instant.now());
            boolean expired = "claimed".equals(status) && leaseUntil.isBefore(Instant.now());
            if (!pending && !expired) return List.of();
            status = "claimed";
            owner = ownerId;
            leaseUntil = Instant.now().plus(lease);
            attempts++;
            return List.of(new OutboxMessage(1, event, attempts));
        }

        @Override public boolean heartbeatOutbox(long id, String ownerId, Duration lease) {
            if (id != 1 || !"claimed".equals(status) || !ownerId.equals(owner)
                    || !leaseUntil.isAfter(Instant.now())) return false;
            leaseUntil = Instant.now().plus(lease);
            return true;
        }

        @Override public List<OutboxMessage> deadLetterOutbox(int limit) {
            return "dead_letter".equals(status)
                    ? List.of(new OutboxMessage(1, event, attempts)) : List.of();
        }

        @Override public boolean requeueOutbox(long id, Instant next) {
            if (id != 1 || !"dead_letter".equals(status)) return false;
            status = "pending";
            attempts = 0;
            availableAt = next;
            return true;
        }

        @Override public boolean acknowledgeOutbox(long id, String ownerId) {
            if (id != 1 || !"claimed".equals(status) || !ownerId.equals(owner)
                    || !leaseUntil.isAfter(Instant.now())) return false;
            status = "delivered";
            owner = null;
            return true;
        }

        @Override
        public boolean retryOutbox(long id, String ownerId, String error,
                                   Instant next, int deadLetterAfter) {
            if (id != 1 || !"claimed".equals(status) || !ownerId.equals(owner)
                    || !leaseUntil.isAfter(Instant.now())) return false;
            status = attempts >= deadLetterAfter ? "dead_letter" : "pending";
            owner = null;
            availableAt = next;
            return true;
        }

        @Override public Acceptance accept(TurnRequest r, String key, Instant deadline) { return delegate.accept(r, key, deadline); }
        @Override public TurnRecord create(TurnRequest r, Instant deadline) { return delegate.create(r, deadline); }
        @Override public TurnRecord turn(String id) { return delegate.turn(id); }
        @Override public Map<String, TurnRecord> turns() { return delegate.turns(); }
        @Override public List<EventEnvelope> events(String sessionId) { return delegate.events(sessionId); }
        @Override public long lastSequence(String sessionId) { return delegate.lastSequence(sessionId); }
        @Override public EventEnvelope append(TurnRecord r, String t, Map<String, Object> d, EventMetadata m) { return delegate.append(r, t, d, m); }
        @Override public void clear() { delegate.clear(); }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }
}

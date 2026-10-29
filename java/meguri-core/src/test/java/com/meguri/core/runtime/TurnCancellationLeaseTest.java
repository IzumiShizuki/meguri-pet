package com.meguri.core.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.dto.ClientCapabilities;
import com.meguri.core.dto.EventEnvelope;
import com.meguri.core.dto.EventMetadata;
import com.meguri.core.dto.LlmResponse;
import com.meguri.core.dto.TurnRequest;
import com.meguri.core.harness.TurnCommand;
import com.meguri.core.llm.LlmProvider;
import com.meguri.core.memory.NoopMemoryGateway;
import com.meguri.core.training.TrainingFeedbackService;
import com.meguri.core.weather.WeatherConversationService;
import com.meguri.core.websearch.NoopWebSearchGateway;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TurnCancellationLeaseTest {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @Test
    void cancellationBeforeClaimDoesNotWriteAnUnownedTerminalAndNextTurnStillRuns() throws Exception {
        LeaseJournal journal = new LeaseJournal(true);
        List<String> providerInputs = new CopyOnWriteArrayList<>();
        LlmProvider provider = (request, state, canon, memories, recent) -> {
            providerInputs.add(request.getMessage());
            return Mono.just(new LlmResponse("answer:" + request.getMessage()));
        };
        TurnOrchestrator runtime = runtime(provider, journal);
        try {
            TurnRecord cancelled = runtime.start(request("before-claim", "cancel-me"));
            await(journal.claimEntered, "worker did not reach its claim");

            assertThatCode(() -> runtime.cancel(cancelled.getTurnId())).doesNotThrowAnyException();
            assertThatCode(() -> runtime.cancel(cancelled.getTurnId())).doesNotThrowAnyException();
            assertThat(cancelled.isCancelRequested()).isTrue();
            assertThat(cancelled.isTerminal()).isFalse();
            assertThat(terminalEvents(journal, cancelled)).isEmpty();

            journal.allowClaim.countDown();
            cancelled.getDone().get(3, TimeUnit.SECONDS);
            assertCancelledOnce(journal, cancelled);
            assertThat(providerInputs).isEmpty();
            TurnRecord next = runtime.start(request("before-claim", "next-input"));
            next.getDone().get(3, TimeUnit.SECONDS);
            assertThat(next.getStatus()).isEqualTo(TurnStatus.COMPLETED);
            assertThat(next.getResult().response().reply()).isEqualTo("answer:next-input");
            assertThat(providerInputs).containsExactly("next-input");
            assertThat(journal.unownedWrites.get()).isZero();
        } finally {
            journal.allowClaim.countDown();
            runtime.reset();
        }
    }

    @Test
    void repeatedActiveCancellationDisposesProviderAndDoesNotLeakItsLateReply() throws Exception {
        LeaseJournal journal = new LeaseJournal(false);
        CountDownLatch subscribed = new CountDownLatch(1);
        CountDownLatch disposed = new CountDownLatch(1);
        AtomicReference<MonoSink<LlmResponse>> pending = new AtomicReference<>();
        LlmProvider provider = (request, state, canon, memories, recent) -> {
            if (!request.getMessage().equals("cancel-active")) {
                return Mono.just(new LlmResponse("answer:" + request.getMessage()));
            }
            return Mono.<LlmResponse>create(sink -> {
                pending.set(sink);
                sink.onCancel(disposed::countDown);
                subscribed.countDown();
            });
        };
        TurnOrchestrator runtime = runtime(provider, journal);
        try {
            TurnRecord cancelled = runtime.start(request("active-cancel", "cancel-active"));
            await(subscribed, "provider did not subscribe");
            runtime.cancel(cancelled.getTurnId());
            runtime.cancel(cancelled.getTurnId());
            cancelled.getDone().get(3, TimeUnit.SECONDS);
            await(disposed, "cancelled provider subscription was not disposed");
            pending.get().success(new LlmResponse("stale cancelled reply"));

            TurnRecord next = runtime.start(request("active-cancel", "fresh-reply"));
            next.getDone().get(3, TimeUnit.SECONDS);
            assertCancelledOnce(journal, cancelled);
            assertThat(journal.events("active-cancel"))
                    .filteredOn(event -> event.getTurnId().equals(cancelled.getTurnId()))
                    .extracting(EventEnvelope::getType)
                    .doesNotContain("text.completed", "turn.completed");
            assertThat(next.getResult().response().reply()).isEqualTo("answer:fresh-reply");
            assertThat(journal.unownedWrites.get()).isZero();
        } finally {
            runtime.reset();
        }
    }

    @Test
    void providerCancellationAndObserverCannotReleaseLeaseBeforeTerminalCommit() throws Exception {
        LeaseJournal journal = new LeaseJournal(false);
        journal.coordinateCancellationRace = true;
        CountDownLatch subscribed = new CountDownLatch(1);
        AtomicReference<MonoSink<LlmResponse>> pending = new AtomicReference<>();
        LlmProvider provider = (request, state, canon, memories, recent) -> {
            if (!request.getMessage().equals("cancel-race")) {
                return Mono.just(new LlmResponse("answer:" + request.getMessage()));
            }
            return Mono.create(sink -> {
                pending.set(sink);
                subscribed.countDown();
            });
        };
        TurnOrchestrator runtime = runtime(provider, journal);
        try (var providerSignals = Executors.newSingleThreadExecutor()) {
            try {
                TurnRecord cancelled = runtime.start(request("dual-cancel", "cancel-race"));
                await(subscribed, "provider did not subscribe");
                runtime.cancel(cancelled.getTurnId());
                var providerError = providerSignals.submit(() -> pending.get().error(
                        new java.util.concurrent.CancellationException("fixture provider observed cancellation")));
                await(journal.cancelAppendEntered, "provider error did not begin terminal append");
                await(journal.cancelObserverArrived, "polling observer did not observe cancellation intent");

                // Both paths have now observed the same cancellation. The first
                // terminal append is paused before the journal acquires its lock.
                // The second must not win on an uncommitted in-memory terminal.
                assertThatThrownBy(() -> journal.released
                        .computeIfAbsent(cancelled.getTurnId(), ignored -> new CompletableFuture<>())
                        .get(100, TimeUnit.MILLISECONDS))
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
                assertThat(cancelled.getDone().isDone()).isFalse();
                assertThat(terminalEvents(journal, cancelled)).isEmpty();
                journal.allowCancelAppend.countDown();
                providerError.get(3, TimeUnit.SECONDS);
                cancelled.getDone().get(3, TimeUnit.SECONDS);
                journal.released.get(cancelled.getTurnId()).get(3, TimeUnit.SECONDS);
                assertCancelledOnce(journal, cancelled);
                assertThat(journal.lifecycle).containsSubsequence(
                        "claim:" + cancelled.getTurnId(), "turn.cancelled:" + cancelled.getTurnId(),
                        "release:" + cancelled.getTurnId());

                TurnRecord next = runtime.start(request("dual-cancel", "fresh"));
                next.getDone().get(3, TimeUnit.SECONDS);
                assertThat(next.getResult().response().reply()).isEqualTo("answer:fresh");
                assertThat(journal.unownedWrites.get()).isZero();
            } finally {
                journal.allowCancelAppend.countDown();
                runtime.reset();
            }
        }
    }

    @Test
    void cancelledQueuedTurnClaimsLeaseBeforeTerminalAndSkipsProvider() throws Exception {
        LeaseJournal journal = new LeaseJournal(false);
        CountDownLatch firstSubscribed = new CountDownLatch(1);
        AtomicReference<MonoSink<LlmResponse>> pending = new AtomicReference<>();
        List<String> inputs = new CopyOnWriteArrayList<>();
        LlmProvider provider = (request, state, canon, memories, recent) -> {
            inputs.add(request.getMessage());
            if (!request.getMessage().equals("first")) return Mono.just(new LlmResponse(request.getMessage()));
            return Mono.create(sink -> {
                pending.set(sink);
                firstSubscribed.countDown();
            });
        };
        TurnOrchestrator runtime = runtime(provider, journal);
        try {
            TurnRecord first = runtime.start(request("queued-cancel", "first"));
            await(firstSubscribed, "first provider did not subscribe");
            TurnRecord queued = runtime.start(request("queued-cancel", "queued"));
            assertThat(journal.owners).doesNotContainKey(queued.getTurnId());
            runtime.cancel(queued.getTurnId());
            runtime.cancel(queued.getTurnId());
            assertThat(terminalEvents(journal, queued)).isEmpty();
            pending.get().success(new LlmResponse("first-done"));
            first.getDone().get(3, TimeUnit.SECONDS);
            queued.getDone().get(3, TimeUnit.SECONDS);
            assertCancelledOnce(journal, queued);
            assertThat(inputs).containsExactly("first");
            assertThat(journal.claimed).contains(queued.getTurnId());
            assertThat(journal.unownedWrites.get()).isZero();
        } finally {
            runtime.reset();
        }
    }

    @Test
    void activeDeadlinePersistsBeforeLeaseReleaseAndNextTurnIgnoresLateProviderReply() throws Exception {
        LeaseJournal journal = new LeaseJournal(false);
        CountDownLatch subscribed = new CountDownLatch(1);
        CountDownLatch disposed = new CountDownLatch(1);
        AtomicReference<MonoSink<LlmResponse>> pending = new AtomicReference<>();
        LlmProvider provider = (request, state, canon, memories, recent) -> {
            if (!request.getMessage().equals("never-finishes")) {
                return Mono.just(new LlmResponse("answer:" + request.getMessage()));
            }
            return Mono.<LlmResponse>create(sink -> {
                pending.set(sink);
                sink.onCancel(disposed::countDown);
                subscribed.countDown();
            });
        };
        TurnOrchestrator runtime = runtime(provider, journal);
        try {
            var snapshot = runtime.submit(new TurnCommand.Start(
                    request("active-deadline", "never-finishes"), "deadline",
                    Instant.now().plusMillis(500))).block(Duration.ofSeconds(3));
            TurnRecord expired = journal.turn(snapshot.turnId());
            await(subscribed, "provider did not subscribe before the deadline");
            expired.getDone().get(3, TimeUnit.SECONDS);
            await(disposed, "deadline did not cancel provider work");
            assertDeadlineOnce(journal, expired);
            pending.get().success(new LlmResponse("stale expired reply"));

            TurnRecord next = runtime.start(request("active-deadline", "fresh-after-deadline"));
            next.getDone().get(3, TimeUnit.SECONDS);
            assertThat(next.getStatus()).isEqualTo(TurnStatus.COMPLETED);
            assertThat(next.getResult().response().reply()).isEqualTo("answer:fresh-after-deadline");
            assertThat(journal.events("active-deadline"))
                    .filteredOn(event -> event.getTurnId().equals(expired.getTurnId()))
                    .extracting(EventEnvelope::getType)
                    .doesNotContain("text.completed", "turn.completed");
            assertThat(journal.unownedWrites.get()).isZero();
        } finally {
            runtime.reset();
        }
    }

    @Test
    void queuedDeadlineClaimsBeforeFailureAndDoesNotAdmitNextTurnAheadOfPredecessor() throws Exception {
        LeaseJournal journal = new LeaseJournal(false);
        CountDownLatch firstSubscribed = new CountDownLatch(1);
        AtomicReference<MonoSink<LlmResponse>> pending = new AtomicReference<>();
        List<String> inputs = new CopyOnWriteArrayList<>();
        LlmProvider provider = (request, state, canon, memories, recent) -> {
            inputs.add(request.getMessage());
            if (!request.getMessage().equals("first")) {
                return Mono.just(new LlmResponse("answer:" + request.getMessage()));
            }
            return Mono.create(sink -> {
                pending.set(sink);
                firstSubscribed.countDown();
            });
        };
        TurnOrchestrator runtime = runtime(provider, journal);
        try {
            TurnRecord first = runtime.start(request("queued-deadline", "first"));
            await(firstSubscribed, "first provider did not subscribe");
            journal.gateFirstFailure.set(true);
            var snapshot = runtime.submit(new TurnCommand.Start(
                    request("queued-deadline", "must-not-run"), "queued-deadline",
                    Instant.now().plusMillis(250))).block(Duration.ofSeconds(3));
            TurnRecord expired = journal.turn(snapshot.turnId());
            await(journal.failureEntered, "expired turn did not begin terminal persistence");
            // The deadline is already past. Persistence may still be in flight;
            // its lease must not be released by a second, 1 ms deadline timer.
            assertThatThrownBy(() -> journal.released
                    .computeIfAbsent(expired.getTurnId(), ignored -> new CompletableFuture<>())
                    .get(100, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            assertThat(expired.getDone().isDone()).isFalse();
            journal.allowFailure.countDown();
            expired.getDone().get(3, TimeUnit.SECONDS);
            assertDeadlineOnce(journal, expired);
            assertThat(first.isTerminal()).isFalse();
            assertThat(inputs).containsExactly("first");

            TurnRecord next = runtime.start(request("queued-deadline", "next"));
            assertThat(journal.claimed).doesNotContain(next.getTurnId());
            pending.get().success(new LlmResponse("first-done"));
            first.getDone().get(3, TimeUnit.SECONDS);
            next.getDone().get(3, TimeUnit.SECONDS);
            assertThat(next.getResult().response().reply()).isEqualTo("answer:next");
            assertThat(inputs).containsExactly("first", "next");
            assertThat(journal.events("queued-deadline"))
                    .filteredOn(event -> event.getType().equals("turn.completed"))
                    .extracting(EventEnvelope::getTurnId)
                    .containsExactly(first.getTurnId(), next.getTurnId());
            assertThat(journal.unownedWrites.get()).isZero();
        } finally {
            journal.allowFailure.countDown();
            runtime.reset();
        }
    }

    private static TurnRequest request(String session, String message) {
        return new TurnRequest("fictional", "website", session, message,
                List.of(), new ClientCapabilities(), null, null, false);
    }

    private static TurnOrchestrator runtime(LlmProvider provider, TurnJournal journal) {
        return new TurnOrchestrator(provider, null, new RuntimeStateMachine(), new ExpressionResolver(),
                Duration.ZERO, MAPPER, new NoopMemoryGateway(), new NoopWebSearchGateway(),
                TrainingFeedbackService.disabled(MAPPER), WeatherConversationService.disabled(), journal);
    }

    private static List<String> terminalEvents(LeaseJournal journal, TurnRecord record) {
        return journal.events(record.getRequest().getSessionId()).stream()
                .filter(event -> event.getTurnId().equals(record.getTurnId()))
                .map(EventEnvelope::getType)
                .filter(type -> List.of("turn.completed", "turn.cancelled", "turn.failed").contains(type))
                .toList();
    }

    private static void assertCancelledOnce(LeaseJournal journal, TurnRecord record) {
        assertThat(record.getStatus()).isEqualTo(TurnStatus.CANCELLED);
        assertThat(terminalEvents(journal, record)).containsExactly("turn.cancelled");
    }

    private static void assertDeadlineOnce(LeaseJournal journal, TurnRecord record) throws Exception {
        journal.released.computeIfAbsent(record.getTurnId(), ignored -> new CompletableFuture<>())
                .get(3, TimeUnit.SECONDS);
        assertThat(record.getStatus()).isEqualTo(TurnStatus.FAILED);
        assertThat(record.getFailureCode()).isEqualTo("TURN_DEADLINE_EXCEEDED");
        assertThat(terminalEvents(journal, record)).containsExactly("turn.failed");
        assertThat(journal.lifecycle).containsSubsequence(
                "claim:" + record.getTurnId(), "turn.failed:" + record.getTurnId(),
                "release:" + record.getTurnId());
    }

    private static void await(CountDownLatch latch, String message) throws InterruptedException {
        assertThat(latch.await(3, TimeUnit.SECONDS)).as(message).isTrue();
    }

    /** Enforces the same ownership contract as PostgreSQL, with a controlled claim gate. */
    private static final class LeaseJournal implements TurnJournal {
        private final InMemoryTurnJournal delegate = new InMemoryTurnJournal(MAPPER);
        private final Map<String, String> owners = new ConcurrentHashMap<>();
        private final List<String> claimed = new CopyOnWriteArrayList<>();
        private final List<String> lifecycle = new CopyOnWriteArrayList<>();
        private final Map<String, CompletableFuture<Void>> released = new ConcurrentHashMap<>();
        private final AtomicInteger unownedWrites = new AtomicInteger();
        private final AtomicBoolean gateFirstClaim;
        private final AtomicBoolean gateFirstFailure = new AtomicBoolean();
        private final CountDownLatch claimEntered = new CountDownLatch(1);
        private final CountDownLatch allowClaim = new CountDownLatch(1);
        private final CountDownLatch failureEntered = new CountDownLatch(1);
        private final CountDownLatch allowFailure = new CountDownLatch(1);
        private volatile boolean coordinateCancellationRace;
        private final AtomicBoolean gateCancelAppend = new AtomicBoolean(true);
        private final CountDownLatch cancelAppendEntered = new CountDownLatch(1);
        private final CountDownLatch allowCancelAppend = new CountDownLatch(1);
        private final CountDownLatch cancelObserverArrived = new CountDownLatch(1);

        private LeaseJournal(boolean gateFirstClaim) { this.gateFirstClaim = new AtomicBoolean(gateFirstClaim); }
        @Override public Acceptance accept(TurnRequest request, String key, Instant deadline) { return delegate.accept(request, key, deadline); }
        @Override public TurnRecord create(TurnRequest request, Instant deadline) { return delegate.create(request, deadline); }
        @Override public TurnRecord turn(String id) { return delegate.turn(id); }
        @Override public Map<String, TurnRecord> turns() { return delegate.turns(); }
        @Override public List<EventEnvelope> events(String session) { return delegate.events(session); }
        @Override public long lastSequence(String session) { return delegate.lastSequence(session); }
        @Override public void clear() { delegate.clear(); owners.clear(); }
        @Override public EventEnvelope append(TurnRecord record, String type, Map<String, Object> data, EventMetadata metadata) {
            return delegate.append(record, type, data, metadata);
        }
        @Override public boolean claimExecution(TurnRecord record, String owner, Duration lease) {
            if (gateFirstClaim.compareAndSet(true, false)) {
                claimEntered.countDown();
                try { await(allowClaim, "test did not release execution claim"); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
            }
            claimed.add(record.getTurnId());
            lifecycle.add("claim:" + record.getTurnId());
            return owners.putIfAbsent(record.getTurnId(), owner) == null;
        }
        @Override public boolean heartbeatExecution(TurnRecord record, String owner, Duration lease) {
            return owner.equals(owners.get(record.getTurnId()));
        }
        @Override public boolean refreshCancellation(TurnRecord record) {
            if (!record.isCancelRequested()) return false;
            if (coordinateCancellationRace) {
                try { await(cancelAppendEntered, "provider path did not reach terminal append"); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
                cancelObserverArrived.countDown();
            }
            synchronized (record) { return record.isCancelRequested(); }
        }
        @Override public void releaseExecution(TurnRecord record, String owner) {
            synchronized (record) {
                owners.remove(record.getTurnId(), owner);
                lifecycle.add("release:" + record.getTurnId());
                released.computeIfAbsent(record.getTurnId(), ignored -> new CompletableFuture<>()).complete(null);
            }
        }
        @Override public void persistExecution(TurnRecord record, String owner) { requireOwner(record, owner); }
        @Override public EventEnvelope appendExecution(TurnRecord record, String owner, String type,
                                                       Map<String, Object> data, EventMetadata metadata) {
            if (coordinateCancellationRace && type.equals("turn.cancelled")
                    && gateCancelAppend.compareAndSet(true, false)) {
                cancelAppendEntered.countDown();
                try { await(allowCancelAppend, "test did not release cancellation append"); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
            }
            synchronized (record) {
                requireOwner(record, owner);
                if (type.equals("turn.failed") && gateFirstFailure.compareAndSet(true, false)) {
                    failureEntered.countDown();
                    try { await(allowFailure, "test did not release terminal persistence"); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
                    requireOwner(record, owner);
                }
                lifecycle.add(type + ":" + record.getTurnId());
                return delegate.append(record, type, data, metadata);
            }
        }
        private void requireOwner(TurnRecord record, String owner) {
            if (!owner.equals(owners.get(record.getTurnId()))) {
                unownedWrites.incrementAndGet();
                throw new IllegalStateException("turn execution lease lost for " + record.getTurnId());
            }
        }
    }
}

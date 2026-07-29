package com.meguri.core.memory.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.dto.LlmResponse;
import com.meguri.core.dto.MemoryCandidate;
import com.meguri.core.dto.TurnRequest;
import com.meguri.core.dto.ClientCapabilities;
import com.meguri.core.memory.MemoryGateway;
import com.meguri.core.memory.MemoryRecall;
import com.meguri.core.memory.MemoryWriteResult;
import com.meguri.core.memory.SessionSummaryRequest;
import com.meguri.core.memory.SessionSummaryResult;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class PostReplyMemoryJobWorkerTest {
    @Test
    void concurrentEnqueueCreatesOneDurableJob() throws Exception {
        InMemoryPostReplyMemoryJobStore store = new InMemoryPostReplyMemoryJobStore();
        MutableClock clock = new MutableClock();
        PostReplyMemoryJobEnqueuer enqueuer = new PostReplyMemoryJobEnqueuer(store, new ObjectMapper(), clock);
        var executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<PostReplyMemoryJobStore.EnqueueResult>> calls = new ArrayList<>();
            for (int i = 0; i < 32; i++) calls.add(() -> enqueuer.enqueue(
                    "turn-1", request(), response(), "trace-1", false,
                    PostReplyMemoryJob.CancellationPolicy.PROCESS_COMPLETED_REPLY));
            var results = executor.invokeAll(calls).stream().map(future -> {
                try { return future.get(); } catch (Exception error) { throw new AssertionError(error); }
            }).toList();

            assertThat(results).filteredOn(PostReplyMemoryJobStore.EnqueueResult::created).hasSize(1);
            assertThat(results).extracting(result -> result.job().jobId()).containsOnly(results.getFirst().job().jobId());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void expiredLeaseIsReclaimedAfterRestart() {
        InMemoryPostReplyMemoryJobStore store = new InMemoryPostReplyMemoryJobStore();
        MutableClock clock = new MutableClock();
        PostReplyMemoryJob job = enqueue(store, clock, false,
                PostReplyMemoryJob.CancellationPolicy.PROCESS_COMPLETED_REPLY);

        assertThat(store.claim("dead-worker", 1, Duration.ofSeconds(10), clock.instant())).hasSize(1);
        clock.advance(Duration.ofSeconds(11));
        List<PostReplyMemoryJob> reclaimed = store.claim("replacement", 1, Duration.ofSeconds(10), clock.instant());

        assertThat(reclaimed).singleElement().satisfies(value -> {
            assertThat(value.jobId()).isEqualTo(job.jobId());
            assertThat(value.ownerId()).isEqualTo("replacement");
            assertThat(value.attempts()).isEqualTo(2);
        });
    }

    @Test
    void failuresBackoffThenReachDeadLetterWithoutTouchingTurnState() {
        InMemoryPostReplyMemoryJobStore store = new InMemoryPostReplyMemoryJobStore();
        MutableClock clock = new MutableClock();
        PostReplyMemoryJob job = enqueue(store, clock, false,
                PostReplyMemoryJob.CancellationPolicy.PROCESS_COMPLETED_REPLY);
        CountingGateway gateway = new CountingGateway(Mono.error(new IllegalStateException("bridge down")));
        String terminalTurnState = "COMPLETED";

        try (PostReplyMemoryJobWorker worker = worker(store, gateway, clock, 2)) {
            assertThat(worker.runOnce()).isZero();
            assertThat(store.find(job.jobId()).orElseThrow().status()).isEqualTo(PostReplyMemoryJob.Status.PENDING);
            clock.advance(Duration.ofSeconds(1));
            assertThat(worker.runOnce()).isEqualTo(1);
        }

        assertThat(store.find(job.jobId()).orElseThrow().status()).isEqualTo(PostReplyMemoryJob.Status.DEAD_LETTER);
        assertThat(gateway.writes).isEqualTo(2);
        assertThat(terminalTurnState).isEqualTo("COMPLETED");
    }

    @Test
    void acknowledgedJobNeverWritesTwice() {
        InMemoryPostReplyMemoryJobStore store = new InMemoryPostReplyMemoryJobStore();
        MutableClock clock = new MutableClock();
        PostReplyMemoryJob job = enqueue(store, clock, false,
                PostReplyMemoryJob.CancellationPolicy.PROCESS_COMPLETED_REPLY);
        CountingGateway gateway = new CountingGateway(Mono.just(success()));

        try (PostReplyMemoryJobWorker worker = worker(store, gateway, clock, 3)) {
            assertThat(worker.runOnce()).isEqualTo(1);
            assertThat(worker.runOnce()).isZero();
        }

        assertThat(gateway.writes).isEqualTo(1);
        assertThat(store.find(job.jobId()).orElseThrow().status()).isEqualTo(PostReplyMemoryJob.Status.SUCCEEDED);
    }

    @Test
    void completedReplyCancellationUsesPersistedPolicy() {
        InMemoryPostReplyMemoryJobStore store = new InMemoryPostReplyMemoryJobStore();
        MutableClock clock = new MutableClock();
        PostReplyMemoryJob process = enqueue(store, clock, true,
                PostReplyMemoryJob.CancellationPolicy.PROCESS_COMPLETED_REPLY);
        PostReplyMemoryJob skip = new PostReplyMemoryJob("skip-job", "turn-skip", "digest-skip", "trace",
                request(), response(), PostReplyMemoryJob.CancellationPolicy.SKIP_IF_CANCELLED, true,
                PostReplyMemoryJob.Status.PENDING, 0, clock.instant(), null, null, null,
                clock.instant(), clock.instant());
        store.enqueue(skip);
        CountingGateway gateway = new CountingGateway(Mono.just(success()));

        try (PostReplyMemoryJobWorker worker = worker(store, gateway, clock, 3)) {
            assertThat(worker.runOnce()).isEqualTo(2);
        }

        assertThat(gateway.writes).isEqualTo(1);
        assertThat(store.find(process.jobId()).orElseThrow().status()).isEqualTo(PostReplyMemoryJob.Status.SUCCEEDED);
        assertThat(store.find(skip.jobId()).orElseThrow().status()).isEqualTo(PostReplyMemoryJob.Status.SKIPPED);
    }

    @Test
    void workerHasNoTurnRuntimeDependencyAndUnauthorizedMemoryIsSkipped() {
        assertThat(Arrays.stream(PostReplyMemoryJobWorker.class.getDeclaredFields())
                .map(field -> field.getType().getName()))
                .noneMatch(name -> name.startsWith("com.meguri.core.runtime"));

        InMemoryPostReplyMemoryJobStore store = new InMemoryPostReplyMemoryJobStore();
        MutableClock clock = new MutableClock();
        TurnRequest unauthorized = new TurnRequest("user", "website", "session", "hello");
        PostReplyMemoryJob job = new PostReplyMemoryJobEnqueuer(store, new ObjectMapper(), clock).enqueue(
                "turn-private", unauthorized, response(), "trace", false,
                PostReplyMemoryJob.CancellationPolicy.PROCESS_COMPLETED_REPLY).job();
        CountingGateway gateway = new CountingGateway(Mono.just(success()));

        try (PostReplyMemoryJobWorker worker = worker(store, gateway, clock, 3)) {
            assertThat(worker.runOnce()).isEqualTo(1);
        }

        assertThat(gateway.writes).isZero();
        assertThat(store.find(job.jobId()).orElseThrow().status()).isEqualTo(PostReplyMemoryJob.Status.SKIPPED);
    }

    private static PostReplyMemoryJob enqueue(InMemoryPostReplyMemoryJobStore store, Clock clock,
                                               boolean cancelled,
                                               PostReplyMemoryJob.CancellationPolicy policy) {
        return new PostReplyMemoryJobEnqueuer(store, new ObjectMapper(), clock).enqueue(
                "turn-1", request(), response(), "trace-1", cancelled, policy).job();
    }

    private static PostReplyMemoryJobWorker worker(InMemoryPostReplyMemoryJobStore store,
                                                    MemoryGateway gateway, Clock clock, int attempts) {
        return new PostReplyMemoryJobWorker(store, gateway, clock, "worker", Duration.ofSeconds(30),
                Duration.ofSeconds(2), 8, attempts, Duration.ofSeconds(1));
    }

    private static TurnRequest request() {
        return new TurnRequest("user", "website", "session", "hello", List.of(),
                new ClientCapabilities(), null, null, true);
    }
    private static LlmResponse response() { return new LlmResponse("completed reply"); }
    private static MemoryWriteResult success() {
        return new MemoryWriteResult("pending", List.of(), List.of("candidate-1"), List.of(), List.of());
    }

    private static final class CountingGateway implements MemoryGateway {
        private final Mono<MemoryWriteResult> result;
        private int writes;
        private CountingGateway(Mono<MemoryWriteResult> result) { this.result = result; }
        @Override public Mono<MemoryRecall> recall(TurnRequest request) { return Mono.just(MemoryRecall.unavailable()); }
        @Override public Mono<List<MemoryCandidate>> extract(TurnRequest request) { return Mono.just(List.of()); }
        @Override public Mono<MemoryWriteResult> write(TurnRequest request, LlmResponse response,
                                                       String turnId, String traceId) {
            writes++;
            return result;
        }
        @Override public Mono<SessionSummaryResult> summarize(SessionSummaryRequest request) {
            return Mono.just(SessionSummaryResult.unavailable(request.userId(), request.clientId(), request.sessionId()));
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-07-29T00:00:00Z");
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}

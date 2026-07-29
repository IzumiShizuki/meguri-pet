package com.meguri.core.memory.job;

import com.meguri.core.memory.MemoryGateway;
import com.meguri.core.memory.MemoryWriteResult;
import com.meguri.core.dto.LlmResponse;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Independent worker: it has no reference to a turn journal and cannot alter reply terminal state. */
public final class PostReplyMemoryJobWorker implements AutoCloseable {
    private static final Set<String> SUCCESS = Set.of(
            "ok", "written", "persisted", "success", "pending", "unchanged");

    private final PostReplyMemoryJobStore store;
    private final MemoryGateway gateway;
    private final Clock clock;
    private final String ownerId;
    private final Duration lease;
    private final Duration writeTimeout;
    private final int batchSize;
    private final int maxAttempts;
    private final Duration initialBackoff;
    private final ScheduledExecutorService heartbeats;
    private final AtomicBoolean closed = new AtomicBoolean();

    public PostReplyMemoryJobWorker(PostReplyMemoryJobStore store, MemoryGateway gateway, Clock clock,
                                    String ownerId) {
        this(store, gateway, clock, ownerId, Duration.ofSeconds(30), Duration.ofSeconds(20),
                32, 8, Duration.ofSeconds(1));
    }

    public PostReplyMemoryJobWorker(PostReplyMemoryJobStore store, MemoryGateway gateway, Clock clock,
                                    String ownerId, Duration lease, Duration writeTimeout,
                                    int batchSize, int maxAttempts, Duration initialBackoff) {
        this.store = Objects.requireNonNull(store, "store");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ownerId = required(ownerId);
        this.lease = positive(lease, "lease");
        this.writeTimeout = positive(writeTimeout, "writeTimeout");
        this.initialBackoff = positive(initialBackoff, "initialBackoff");
        if (batchSize < 1 || maxAttempts < 1) throw new IllegalArgumentException("worker limits must be positive");
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.heartbeats = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "post-reply-memory-heartbeat-" + this.ownerId);
            thread.setDaemon(true);
            return thread;
        });
    }

    public int runOnce() {
        if (closed.get()) return 0;
        int terminal = 0;
        for (int index = 0; index < batchSize; index++) {
            if (closed.get()) break;
            List<PostReplyMemoryJob> jobs = store.claim(
                    ownerId, 1, lease, clock.instant());
            if (jobs.isEmpty()) break;
            PostReplyMemoryJob job = jobs.getFirst();
            if (process(job)) terminal++;
        }
        return terminal;
    }

    private boolean process(PostReplyMemoryJob job) {
        if (!job.shouldWrite()) {
            return store.acknowledge(job.jobId(), ownerId, PostReplyMemoryJob.Status.SKIPPED, clock.instant());
        }
        AtomicBoolean leaseLost = new AtomicBoolean();
        long heartbeatMillis = Math.max(10L, lease.toMillis() / 3L);
        ScheduledFuture<?> heartbeat = heartbeats.scheduleAtFixedRate(() -> {
            if (!store.heartbeat(job.jobId(), ownerId, lease, clock.instant())) leaseLost.set(true);
        }, heartbeatMillis, heartbeatMillis, TimeUnit.MILLISECONDS);
        try {
            if (!store.heartbeat(job.jobId(), ownerId, lease, clock.instant())) return false;
            Instant deadline = clock.instant().plus(writeTimeout);
            LlmResponse writeResponse = job.response();
            if (writeResponse.getMemoryCandidates().isEmpty()) {
                var candidates = gateway.extract(job.request()).block(remaining(deadline));
                if (candidates == null) {
                    throw new IllegalStateException("memory gateway returned no extracted candidates");
                }
                writeResponse = new LlmResponse(
                        writeResponse.getReply(), writeResponse.getExpressionTag(),
                        writeResponse.getExpressionIntensity(), writeResponse.getVoiceStyle(),
                        candidates);
            }
            MemoryWriteResult result = gateway.write(
                            job.request(), writeResponse, job.turnId(), job.traceId())
                    .block(remaining(deadline));
            if (result == null || !SUCCESS.contains(result.status().toLowerCase(Locale.ROOT))) {
                throw new IllegalStateException("memory gateway returned "
                        + (result == null ? "no result" : result.status()));
            }
            return !leaseLost.get() && store.acknowledge(job.jobId(), ownerId,
                    PostReplyMemoryJob.Status.SUCCEEDED, clock.instant());
        } catch (Throwable error) {
            if (leaseLost.get()) return false;
            boolean deadLetter = job.attempts() >= maxAttempts;
            Instant retryAt = clock.instant().plus(backoff(job.attempts()));
            store.retry(job.jobId(), ownerId, safe(error), retryAt, deadLetter, clock.instant());
            return deadLetter;
        } finally {
            heartbeat.cancel(false);
        }
    }

    private Duration backoff(int attempts) {
        int exponent = Math.min(20, Math.max(0, attempts - 1));
        long multiplier = 1L << exponent;
        try { return initialBackoff.multipliedBy(multiplier); }
        catch (ArithmeticException ignored) { return Duration.ofDays(1); }
    }

    private Duration remaining(Instant deadline) {
        Duration value = Duration.between(clock.instant(), deadline);
        return value.isNegative() || value.isZero() ? Duration.ofMillis(1) : value;
    }

    @Override public void close() {
        closed.set(true);
        heartbeats.shutdownNow();
    }

    private static String safe(Throwable error) {
        String value = error == null ? null : error.getMessage();
        if (value == null || value.isBlank()) value = error == null ? "memory write failed" : error.getClass().getSimpleName();
        return value.substring(0, Math.min(2000, value.length()));
    }
    private static String required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("ownerId must not be blank");
        return value.trim();
    }
    private static Duration positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }
}

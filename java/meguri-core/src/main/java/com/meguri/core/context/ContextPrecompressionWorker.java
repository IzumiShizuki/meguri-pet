package com.meguri.core.context;

import com.meguri.core.runtime.SessionContextStore;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Claims durable precompression jobs and materializes idempotent derived summaries. */
public final class ContextPrecompressionWorker implements AutoCloseable {
    private final SessionContextStore sessions;
    private final ContextRuntimePersistence persistence;
    private final Clock clock;
    private final Duration lease;
    private final Duration retryBackoff;
    private final int batchSize;
    private final int maxAttempts;
    private final int maximumSummaryCharacters;
    private final ContextRefactoringStrategy strategy;
    private final boolean structuredCompressionEnabled;
    private final String workerId;
    private final ScheduledExecutorService heartbeats;

    public ContextPrecompressionWorker(
            SessionContextStore sessions,
            ContextRuntimePersistence persistence,
            Clock clock,
            Duration lease,
            Duration retryBackoff,
            int batchSize,
            int maxAttempts,
            int maximumSummaryCharacters) {
        this(sessions, persistence, clock, lease, retryBackoff, batchSize,
                maxAttempts, maximumSummaryCharacters,
                new DeterministicContextRefactoringStrategy(maximumSummaryCharacters), false);
    }

    public ContextPrecompressionWorker(
            SessionContextStore sessions,
            ContextRuntimePersistence persistence,
            Clock clock,
            Duration lease,
            Duration retryBackoff,
            int batchSize,
            int maxAttempts,
            int maximumSummaryCharacters,
            ContextRefactoringStrategy strategy,
            boolean structuredCompressionEnabled) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.lease = positive(lease, "lease");
        this.retryBackoff = positive(retryBackoff, "retryBackoff");
        this.batchSize = positive(batchSize, "batchSize");
        this.maxAttempts = positive(maxAttempts, "maxAttempts");
        this.maximumSummaryCharacters = positive(
                maximumSummaryCharacters, "maximumSummaryCharacters");
        this.strategy = Objects.requireNonNull(strategy, "strategy");
        this.structuredCompressionEnabled = structuredCompressionEnabled;
        this.workerId = "context-precompression-" + UUID.randomUUID();
        this.heartbeats = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "context-precompression-heartbeat-" + workerId);
            thread.setDaemon(true);
            return thread;
        });
    }

    public Result runOnce() {
        Instant now = clock.instant();
        List<ContextRuntimePersistence.PrecompressionJob> recoverable =
                persistence.recoverablePrecompressionJobs(now).stream()
                        .limit(batchSize)
                        .toList();
        int claimed = 0;
        int completed = 0;
        int failed = 0;
        for (ContextRuntimePersistence.PrecompressionJob candidate : recoverable) {
            String claimToken = UUID.randomUUID().toString();
            var claim = persistence.claimPrecompression(
                    candidate.jobId(), workerId, claimToken, clock.instant().plus(lease));
            if (claim.isEmpty()) continue;
            claimed++;
            ContextRuntimePersistence.PrecompressionJob job = claim.orElseThrow();
            AtomicBoolean owned = new AtomicBoolean(true);
            long heartbeatMillis = Math.max(10L, lease.toMillis() / 3L);
            ScheduledFuture<?> heartbeat = heartbeats.scheduleAtFixedRate(() -> {
                if (!persistence.heartbeatPrecompression(
                        job.jobId(), workerId, claimToken, clock.instant().plus(lease))) {
                    owned.set(false);
                }
            }, heartbeatMillis, heartbeatMillis, TimeUnit.MILLISECONDS);
            try {
                if (!persistence.heartbeatPrecompression(
                        job.jobId(), workerId, claimToken, clock.instant().plus(lease))) {
                    owned.set(false);
                    continue;
                }
                String summaryId = process(job);
                if (owned.get() && persistence.completePrecompression(
                        job.jobId(), workerId, claimToken, summaryId)) {
                    completed++;
                }
            } catch (RuntimeException error) {
                boolean terminal = error instanceof PermanentFailure
                        || job.attempts() >= maxAttempts;
                long multiplier = 1L << Math.min(Math.max(0, job.attempts() - 1), 8);
                if (owned.get() && persistence.retryPrecompression(
                        job.jobId(), workerId, claimToken,
                        clock.instant().plus(retryBackoff.multipliedBy(multiplier)), terminal)) {
                    failed++;
                }
            } finally {
                heartbeat.cancel(false);
            }
        }
        return new Result(recoverable.size(), claimed, completed, failed);
    }

    private String process(ContextRuntimePersistence.PrecompressionJob job) {
        SessionContextStore.GraphSnapshot graph = sessions.reloadGraph(
                required(job.userId(), "userId"), required(job.clientId(), "clientId"),
                required(job.conversationId(), "conversationId"));
        if (graph.revision() < job.graphRevision()) {
            throw new IllegalStateException("context graph has not reached the queued revision");
        }
        String modelRevision = "context-precompression-v2:"
                + job.modelId() + ":" + job.strategyRevision() + ":" + job.jobId();
        var existing = graph.summaries().stream()
                .filter(summary -> modelRevision.equals(summary.modelRevision()))
                .findFirst();
        if (existing.isPresent()) return existing.orElseThrow().summaryId();

        Map<String, SessionContextStore.MessageNode> byId = new LinkedHashMap<>();
        graph.allNodes().forEach(node -> byId.put(node.messageId(), node));
        List<String> requested = job.sourceMessageIds();
        if (requested.isEmpty()) throw new PermanentFailure("precompression has no sources");
        int retainedTail = requested.size() > 4 ? 4 : 0;
        List<String> sourceIds = List.copyOf(requested.subList(
                0, requested.size() - retainedTail));
        List<SessionContextStore.MessageNode> messages = new ArrayList<>();
        for (String sourceId : sourceIds) {
            SessionContextStore.MessageNode node = byId.get(sourceId);
            if (node == null) throw new PermanentFailure("precompression source is missing");
            messages.add(node);
        }
        String content;
        StructuredContextSummary structured = null;
        if (structuredCompressionEnabled) {
            ContextRefactoringStrategy.Output output = strategy.refactor(
                    new ContextRefactoringStrategy.Input(messages, job.modelId()));
            content = output.compactContent();
            structured = output.structured();
        } else {
            content = extractiveSummary(messages, maximumSummaryCharacters);
        }
        try {
            return sessions.addSummary(
                    job.userId(), job.clientId(), job.conversationId(), sourceIds,
                    content, modelRevision, structured).summaryId();
        } catch (IllegalArgumentException staleBranch) {
            throw new PermanentFailure("precompression branch is no longer active", staleBranch);
        }
    }

    private static String extractiveSummary(
            List<SessionContextStore.MessageNode> messages, int maximumCharacters) {
        StringBuilder summary = new StringBuilder("Earlier conversation (derived summary):\n");
        for (SessionContextStore.MessageNode message : messages) {
            String line = message.role() + ": " + message.content().strip() + "\n";
            if (summary.length() + line.length() > maximumCharacters) {
                int remaining = maximumCharacters - summary.length();
                if (remaining > 1) summary.append(line, 0, Math.min(remaining - 1, line.length()));
                summary.append('…');
                break;
            }
            summary.append(line);
        }
        return summary.toString().strip();
    }

    @Override
    public void close() {
        heartbeats.shutdownNow();
    }

    public record Result(int recoverable, int claimed, int completed, int failed) { }

    private static Duration positive(Duration value, String field) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    private static int positive(int value, String field) {
        if (value < 1) throw new IllegalArgumentException(field + " must be positive");
        return value;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new PermanentFailure(field + " is required");
        }
        return value.trim();
    }

    private static final class PermanentFailure extends IllegalStateException {
        private PermanentFailure(String message) {
            super(message);
        }

        private PermanentFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

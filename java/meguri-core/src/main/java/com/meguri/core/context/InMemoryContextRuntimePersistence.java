package com.meguri.core.context;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Thread-safe test/offline projection with the same idempotency and lease rules as PostgreSQL. */
public final class InMemoryContextRuntimePersistence implements ContextRuntimePersistence {
    private final Map<String, ContextBuildTrace> traces = new LinkedHashMap<>();
    private final Map<String, TopicSegment> segments = new LinkedHashMap<>();
    private final Map<String, PrecompressionJob> jobs = new LinkedHashMap<>();
    private final Map<String, String> jobIdsByKey = new LinkedHashMap<>();
    private final Map<String, Claim> claims = new LinkedHashMap<>();

    @Override
    public synchronized void saveTrace(ContextBuildTrace trace) {
        ContextBuildTrace previous = traces.putIfAbsent(trace.traceId(), trace);
        if (previous != null && !previous.equals(trace)) throw new IllegalStateException("trace id conflict");
    }

    @Override
    public synchronized Optional<ContextBuildTrace> findTrace(String traceId) {
        return Optional.ofNullable(traces.get(traceId));
    }

    @Override
    public synchronized TopicSegment saveTopicSegment(TopicSegment segment) {
        segments.put(segment.segmentId(), segment);
        return segment;
    }

    @Override
    public synchronized List<TopicSegment> findTopicSegments(String conversationId) {
        return segments.values().stream()
                .filter(segment -> segment.conversationId().equals(conversationId)).toList();
    }

    @Override
    public synchronized PrecompressionJob enqueuePrecompression(PrecompressionJob job) {
        String existingId = jobIdsByKey.get(job.idempotencyKey());
        if (existingId != null) return jobs.get(existingId);
        jobs.put(job.jobId(), job);
        jobIdsByKey.put(job.idempotencyKey(), job.jobId());
        return job;
    }

    @Override
    public synchronized List<PrecompressionJob> recoverablePrecompressionJobs(Instant now) {
        List<PrecompressionJob> result = new ArrayList<>();
        for (PrecompressionJob job : jobs.values()) {
            if (job.status() == JobStatus.PENDING && !job.availableAt().isAfter(now)
                    || job.status() == JobStatus.RUNNING && job.leaseUntil() != null && !job.leaseUntil().isAfter(now)) {
                result.add(job);
            }
        }
        return List.copyOf(result);
    }

    @Override
    public synchronized Optional<PrecompressionJob> claimPrecompression(
            String jobId, String ownerId, String claimToken, Instant leaseUntil) {
        PrecompressionJob job = jobs.get(jobId);
        Instant now = Instant.now();
        if (job == null || job.status() == JobStatus.COMPLETED || job.status() == JobStatus.FAILED
                || job.status() == JobStatus.RUNNING && job.leaseUntil() != null && job.leaseUntil().isAfter(now)) {
            return Optional.empty();
        }
        PrecompressionJob claimed = new PrecompressionJob(
                job.jobId(), job.idempotencyKey(), job.userId(), job.clientId(),
                job.conversationId(), job.graphRevision(), job.sourceMessageIds(), job.modelId(),
                job.strategyRevision(),
                JobStatus.RUNNING, job.attempts() + 1, job.availableAt(), leaseUntil,
                job.summaryId(), job.createdAt());
        jobs.put(jobId, claimed);
        claims.put(jobId, new Claim(ownerId, claimToken));
        return Optional.of(claimed);
    }

    @Override
    public synchronized boolean heartbeatPrecompression(
            String jobId, String ownerId, String claimToken, Instant leaseUntil) {
        PrecompressionJob job = ownedRunningJob(jobId, ownerId, claimToken, Instant.now());
        if (job == null) return false;
        jobs.put(jobId, copy(job, JobStatus.RUNNING, job.availableAt(), leaseUntil,
                job.summaryId()));
        return true;
    }

    @Override
    public synchronized boolean completePrecompression(
            String jobId, String ownerId, String claimToken, String summaryId) {
        PrecompressionJob job = jobs.get(jobId);
        if (ownedRunningJob(jobId, ownerId, claimToken, Instant.now()) == null) return false;
        jobs.put(jobId, copy(job, JobStatus.COMPLETED, job.availableAt(), null, summaryId));
        claims.remove(jobId);
        return true;
    }

    @Override
    public synchronized boolean retryPrecompression(
            String jobId, String ownerId, String claimToken,
            Instant availableAt, boolean terminal) {
        PrecompressionJob job = jobs.get(jobId);
        if (ownedRunningJob(jobId, ownerId, claimToken, Instant.now()) == null) return false;
        jobs.put(jobId, copy(job, terminal ? JobStatus.FAILED : JobStatus.PENDING,
                availableAt, null, job.summaryId()));
        claims.remove(jobId);
        return true;
    }

    private PrecompressionJob ownedRunningJob(
            String jobId, String ownerId, String claimToken, Instant now) {
        PrecompressionJob job = jobs.get(jobId);
        Claim claim = claims.get(jobId);
        if (job == null || job.status() != JobStatus.RUNNING || claim == null
                || !java.util.Objects.equals(claim.ownerId(), ownerId)
                || !java.util.Objects.equals(claim.token(), claimToken)
                || job.leaseUntil() == null || !job.leaseUntil().isAfter(now)) {
            return null;
        }
        return job;
    }

    private static PrecompressionJob copy(
            PrecompressionJob job, JobStatus status, Instant availableAt, Instant leaseUntil,
            String summaryId) {
        return new PrecompressionJob(
                job.jobId(), job.idempotencyKey(), job.userId(), job.clientId(),
                job.conversationId(), job.graphRevision(), job.sourceMessageIds(), job.modelId(),
                job.strategyRevision(),
                status, job.attempts(), availableAt, leaseUntil,
                summaryId, job.createdAt());
    }

    private record Claim(String ownerId, String token) { }
}

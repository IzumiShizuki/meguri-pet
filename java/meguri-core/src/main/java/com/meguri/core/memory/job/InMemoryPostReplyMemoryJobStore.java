package com.meguri.core.memory.job;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Thread-safe reference implementation with the same lease and idempotency rules as PostgreSQL. */
public final class InMemoryPostReplyMemoryJobStore implements PostReplyMemoryJobStore {
    private final Map<String, PostReplyMemoryJob> jobs = new LinkedHashMap<>();
    private final Map<String, String> idempotencyIndex = new LinkedHashMap<>();

    @Override
    public synchronized EnqueueResult enqueue(PostReplyMemoryJob job) {
        String key = key(job.turnId(), job.responseDigest());
        String existingId = idempotencyIndex.get(key);
        if (existingId != null) return new EnqueueResult(jobs.get(existingId), false);
        jobs.put(job.jobId(), job);
        idempotencyIndex.put(key, job.jobId());
        return new EnqueueResult(job, true);
    }

    @Override
    public synchronized List<PostReplyMemoryJob> claim(String ownerId, int limit, Duration lease, Instant now) {
        requireClaim(ownerId, limit, lease, now);
        List<PostReplyMemoryJob> eligible = jobs.values().stream()
                .filter(job -> eligible(job, now))
                .sorted(Comparator.comparing(PostReplyMemoryJob::availableAt)
                        .thenComparing(PostReplyMemoryJob::createdAt))
                .limit(limit)
                .toList();
        List<PostReplyMemoryJob> claimed = new ArrayList<>(eligible.size());
        for (PostReplyMemoryJob job : eligible) {
            PostReplyMemoryJob next = copy(job, PostReplyMemoryJob.Status.RUNNING,
                    job.attempts() + 1, job.availableAt(), ownerId, now.plus(lease),
                    job.lastError(), now);
            jobs.put(next.jobId(), next);
            claimed.add(next);
        }
        return List.copyOf(claimed);
    }

    @Override
    public synchronized boolean heartbeat(String jobId, String ownerId, Duration lease, Instant now) {
        PostReplyMemoryJob job = jobs.get(jobId);
        if (!owned(job, ownerId, now)) return false;
        jobs.put(jobId, copy(job, job.status(), job.attempts(), job.availableAt(), ownerId,
                now.plus(lease), job.lastError(), now));
        return true;
    }

    @Override
    public synchronized boolean acknowledge(String jobId, String ownerId,
                                             PostReplyMemoryJob.Status terminalStatus, Instant now) {
        if (terminalStatus != PostReplyMemoryJob.Status.SUCCEEDED
                && terminalStatus != PostReplyMemoryJob.Status.SKIPPED) {
            throw new IllegalArgumentException("acknowledgement status must be terminal success or skipped");
        }
        PostReplyMemoryJob job = jobs.get(jobId);
        if (!owned(job, ownerId, now)) return false;
        jobs.put(jobId, copy(job, terminalStatus, job.attempts(), job.availableAt(),
                null, null, job.lastError(), now));
        return true;
    }

    @Override
    public synchronized boolean retry(String jobId, String ownerId, String error,
                                      Instant availableAt, boolean deadLetter, Instant now) {
        PostReplyMemoryJob job = jobs.get(jobId);
        if (!owned(job, ownerId, now)) return false;
        PostReplyMemoryJob.Status status = deadLetter
                ? PostReplyMemoryJob.Status.DEAD_LETTER : PostReplyMemoryJob.Status.PENDING;
        jobs.put(jobId, copy(job, status, job.attempts(), availableAt, null, null, error, now));
        return true;
    }

    @Override
    public synchronized Optional<PostReplyMemoryJob> find(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    private static boolean eligible(PostReplyMemoryJob job, Instant now) {
        return job.status() == PostReplyMemoryJob.Status.PENDING && !job.availableAt().isAfter(now)
                || job.status() == PostReplyMemoryJob.Status.RUNNING
                && job.leaseUntil() != null && !job.leaseUntil().isAfter(now);
    }

    private static boolean owned(PostReplyMemoryJob job, String ownerId, Instant now) {
        return job != null && job.status() == PostReplyMemoryJob.Status.RUNNING
                && ownerId != null && ownerId.equals(job.ownerId())
                && job.leaseUntil() != null && job.leaseUntil().isAfter(now);
    }

    private static void requireClaim(String ownerId, int limit, Duration lease, Instant now) {
        if (ownerId == null || ownerId.isBlank() || limit < 1 || lease == null
                || lease.isZero() || lease.isNegative() || now == null) {
            throw new IllegalArgumentException("valid owner, limit, lease and now are required");
        }
    }

    private static String key(String turnId, String digest) { return turnId + "\u0000" + digest; }

    private static PostReplyMemoryJob copy(PostReplyMemoryJob job, PostReplyMemoryJob.Status status,
                                           int attempts, Instant availableAt, String ownerId,
                                           Instant leaseUntil, String error, Instant updatedAt) {
        return new PostReplyMemoryJob(job.jobId(), job.turnId(), job.responseDigest(), job.traceId(),
                job.request(), job.response(), job.cancellationPolicy(), job.cancelledAfterReply(),
                status, attempts, availableAt, ownerId, leaseUntil, error, job.createdAt(), updatedAt);
    }
}

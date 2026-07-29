package com.meguri.core.memory.job;

import com.meguri.core.dto.LlmResponse;
import com.meguri.core.dto.TurnRequest;

import java.time.Instant;
import java.util.Objects;

/** Durable, immutable snapshot used after the reply has reached a terminal state. */
public record PostReplyMemoryJob(
        String jobId,
        String turnId,
        String responseDigest,
        String traceId,
        TurnRequest request,
        LlmResponse response,
        CancellationPolicy cancellationPolicy,
        boolean cancelledAfterReply,
        Status status,
        int attempts,
        Instant availableAt,
        String ownerId,
        Instant leaseUntil,
        String lastError,
        Instant createdAt,
        Instant updatedAt) {

    public PostReplyMemoryJob {
        jobId = required(jobId, "jobId");
        turnId = required(turnId, "turnId");
        responseDigest = required(responseDigest, "responseDigest");
        traceId = required(traceId, "traceId");
        request = Objects.requireNonNull(request, "request");
        response = Objects.requireNonNull(response, "response");
        cancellationPolicy = Objects.requireNonNull(cancellationPolicy, "cancellationPolicy");
        status = Objects.requireNonNull(status, "status");
        if (attempts < 0) throw new IllegalArgumentException("attempts must not be negative");
        availableAt = Objects.requireNonNull(availableAt, "availableAt");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        ownerId = optional(ownerId);
        lastError = optional(lastError);
        if (status == Status.RUNNING && (ownerId == null || leaseUntil == null)) {
            throw new IllegalArgumentException("running jobs require an owner and lease");
        }
    }

    public boolean shouldWrite() {
        return request.formalMemoryAllowed()
                && (!cancelledAfterReply || cancellationPolicy == CancellationPolicy.PROCESS_COMPLETED_REPLY);
    }

    public enum Status { PENDING, RUNNING, SUCCEEDED, SKIPPED, DEAD_LETTER }

    /** Cancellation after persisted text does not discard memory unless explicitly requested. */
    public enum CancellationPolicy { PROCESS_COMPLETED_REPLY, SKIP_IF_CANCELLED }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

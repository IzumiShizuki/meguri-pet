package com.meguri.core.context;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Durable projections used by context assembly without mutating raw conversation facts. */
public interface ContextRuntimePersistence {
    void saveTrace(ContextBuildTrace trace);

    Optional<ContextBuildTrace> findTrace(String traceId);

    TopicSegment saveTopicSegment(TopicSegment segment);

    List<TopicSegment> findTopicSegments(String conversationId);

    PrecompressionJob enqueuePrecompression(PrecompressionJob job);

    List<PrecompressionJob> recoverablePrecompressionJobs(Instant now);

    Optional<PrecompressionJob> claimPrecompression(
            String jobId, String ownerId, String claimToken, Instant leaseUntil);

    boolean heartbeatPrecompression(
            String jobId, String ownerId, String claimToken, Instant leaseUntil);

    boolean completePrecompression(
            String jobId, String ownerId, String claimToken, String summaryId);

    boolean retryPrecompression(
            String jobId, String ownerId, String claimToken,
            Instant availableAt, boolean terminal);

    record ContextBuildTrace(
            String traceId,
            String conversationId,
            long graphRevision,
            String requestDigest,
            ContextBundle bundle,
            Instant createdAt) { }

    enum TopicStatus { ACTIVE, CANDIDATE, MERGED }

    record TopicSegment(
            String segmentId,
            String conversationId,
            String label,
            TopicStatus status,
            double confidence,
            String reason,
            Instant createdAt) { }

    enum JobStatus { PENDING, RUNNING, COMPLETED, FAILED }

    record PrecompressionJob(
            String jobId,
            String idempotencyKey,
            String userId,
            String clientId,
            String conversationId,
            long graphRevision,
            List<String> sourceMessageIds,
            String modelId,
            JobStatus status,
             int attempts,
             Instant availableAt,
             Instant leaseUntil,
             String summaryId,
            Instant createdAt) {
        public PrecompressionJob {
            sourceMessageIds = sourceMessageIds == null
                    ? List.of() : List.copyOf(sourceMessageIds);
        }
    }
}

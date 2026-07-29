package com.meguri.core.memory.job;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.dto.LlmResponse;
import com.meguri.core.dto.TurnRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** The only API the canonical turn pipeline needs after durable text completion. */
public final class PostReplyMemoryJobEnqueuer {
    private final PostReplyMemoryJobStore store;
    private final ObjectMapper mapper;
    private final Clock clock;

    public PostReplyMemoryJobEnqueuer(PostReplyMemoryJobStore store, ObjectMapper mapper, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public PostReplyMemoryJobStore.EnqueueResult enqueue(
            String turnId, TurnRequest request, LlmResponse response, String traceId,
            boolean cancelledAfterReply, PostReplyMemoryJob.CancellationPolicy cancellationPolicy) {
        return enqueueWithId(UUID.randomUUID().toString(), turnId, request, response,
                traceId, cancelledAfterReply, cancellationPolicy);
    }

    /** Uses the durable Turn event as the stable source key for at-least-once delivery. */
    public PostReplyMemoryJobStore.EnqueueResult enqueueFromEvent(
            String eventId, String turnId, TurnRequest request, LlmResponse response,
            String traceId, boolean cancelledAfterReply,
            PostReplyMemoryJob.CancellationPolicy cancellationPolicy) {
        return enqueueWithId(eventJobId(eventId), turnId, request, response,
                traceId, cancelledAfterReply, cancellationPolicy);
    }

    private PostReplyMemoryJobStore.EnqueueResult enqueueWithId(
            String jobId, String turnId, TurnRequest request, LlmResponse response,
            String traceId, boolean cancelledAfterReply,
            PostReplyMemoryJob.CancellationPolicy cancellationPolicy) {
        Instant now = clock.instant();
        String digest = digest(response);
        PostReplyMemoryJob job = new PostReplyMemoryJob(jobId, turnId, digest,
                traceId, request, response, cancellationPolicy, cancelledAfterReply,
                PostReplyMemoryJob.Status.PENDING, 0, now, null, null, null, now, now);
        return store.enqueue(job);
    }

    public String digest(LlmResponse response) {
        try {
            byte[] canonical = mapper.writeValueAsBytes(Objects.requireNonNull(response, "response"));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (JsonProcessingException | NoSuchAlgorithmException error) {
            throw new IllegalStateException("could not calculate response digest", error);
        }
    }

    private static String eventJobId(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId must not be blank");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(eventId.trim().getBytes(StandardCharsets.UTF_8));
            return "memory-event-" + HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}

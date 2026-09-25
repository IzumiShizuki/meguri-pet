package com.meguri.core.memory.job;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PostReplyMemoryJobStore {
    EnqueueResult enqueue(PostReplyMemoryJob job);

    List<PostReplyMemoryJob> claim(String ownerId, int limit, Duration lease, Instant now);

    boolean heartbeat(String jobId, String ownerId, Duration lease, Instant now);

    boolean acknowledge(String jobId, String ownerId, PostReplyMemoryJob.Status terminalStatus, Instant now);

    boolean retry(String jobId, String ownerId, String error, Instant availableAt,
                  boolean deadLetter, Instant now);

    Optional<PostReplyMemoryJob> find(String jobId);

    record EnqueueResult(PostReplyMemoryJob job, boolean created) { }
}

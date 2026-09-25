package com.meguri.core.memory;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

/** Aggregate-only audit report; it intentionally exposes no session contents. */
public record SleepMemoryReport(
        @JsonProperty("ran_at") OffsetDateTime ranAt,
        @JsonProperty("snapshots_seen") int snapshotsSeen,
        @JsonProperty("summaries_persisted") int summariesPersisted,
        @JsonProperty("summaries_unavailable") int summariesUnavailable,
        @JsonProperty("messages_redacted") int messagesRedacted,
        @JsonProperty("structured_candidates") int structuredCandidates,
        @JsonProperty("candidates_queued") int candidatesQueued) {
    public SleepMemoryReport(OffsetDateTime ranAt, int snapshotsSeen, int summariesPersisted,
                             int summariesUnavailable, int messagesRedacted) {
        this(ranAt, snapshotsSeen, summariesPersisted, summariesUnavailable, messagesRedacted, 0, 0);
    }

    public static SleepMemoryReport empty(OffsetDateTime ranAt) {
        return new SleepMemoryReport(ranAt, 0, 0, 0, 0, 0, 0);
    }
}

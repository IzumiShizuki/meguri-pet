package com.meguri.core.billing;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Safe aggregate-only billing result produced by the Qianji sync companion. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BillingBriefing(
        String status,
        @JsonProperty("target_date") String targetDate,
        @JsonProperty("generated_at") String generatedAt,
        @JsonProperty("sync_imported_count") int syncImportedCount,
        @JsonProperty("sync_duplicate_count") int syncDuplicateCount,
        @JsonProperty("sync_skipped_count") int syncSkippedCount,
        String briefing,
        List<BillingArtifact> artifacts,
        String error) {
    public static BillingBriefing unavailable(String error) {
        return new BillingBriefing("unavailable", null, null, 0, 0, 0, null, List.of(), error);
    }
}

package com.meguri.core.daily;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Validated local report envelope accepted by the remote Core inbox. */
public record DailyReportUpload(
        @JsonProperty("schema_version") @NotNull @Min(1) @Max(1) Integer schemaVersion,
        @JsonProperty("report_id") @NotBlank
        @Pattern(regexp = "^[a-z0-9][a-z0-9_-]{0,31}:[0-9]{4}-[0-9]{2}-[0-9]{2}$") String reportId,
        @NotBlank @Pattern(regexp = "^[a-z0-9][a-z0-9_-]{0,31}$") String kind,
        @NotNull LocalDate date,
        @NotBlank @Size(max = 160) String title,
        @NotBlank @Size(max = 4000) String summary,
        @JsonProperty("delivery_text") @NotBlank @Size(max = 8000) String deliveryText,
        @JsonProperty("delivery_speech_text") @NotBlank @Size(max = 4000) String deliverySpeechText,
        @JsonProperty("generated_at") @NotNull OffsetDateTime generatedAt,
        @JsonProperty("published_at") @NotNull OffsetDateTime publishedAt,
        @JsonProperty("data_source") @NotBlank @Size(max = 64) String dataSource,
        @JsonProperty("sync_status") @NotBlank @Size(max = 64) String syncStatus,
        @JsonProperty("unique_videos") @Min(0) @Max(1_000_000) int uniqueVideos,
        @JsonProperty("total_visits") @Min(0) @Max(1_000_000) int totalVisits,
        @JsonProperty("markdown_sha256") @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String markdownSha256,
        @NotBlank @Size(max = 262_144) String markdown) {
}

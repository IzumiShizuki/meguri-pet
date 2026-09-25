package com.meguri.core.daily;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Stored report metadata returned to desktop publishers and AstrBot pollers. */
public record DailyReportReceipt(
        @JsonProperty("schema_version") int schemaVersion,
        @JsonProperty("report_id") String reportId,
        String kind,
        LocalDate date,
        String title,
        String summary,
        @JsonProperty("delivery_text") String deliveryText,
        @JsonProperty("delivery_speech_text") String deliverySpeechText,
        @JsonProperty("generated_at") OffsetDateTime generatedAt,
        @JsonProperty("published_at") OffsetDateTime publishedAt,
        @JsonProperty("received_at") OffsetDateTime receivedAt,
        @JsonProperty("data_source") String dataSource,
        @JsonProperty("sync_status") String syncStatus,
        @JsonProperty("unique_videos") int uniqueVideos,
        @JsonProperty("total_visits") int totalVisits,
        @JsonProperty("render_payload") JsonNode renderPayload,
        @JsonProperty("markdown_sha256") String markdownSha256,
        @JsonProperty("markdown_href") String markdownHref) {
}

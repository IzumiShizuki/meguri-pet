package com.meguri.core.weather;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

/** A bounded proactive notice produced by a work-hour weather check. */
public record WeatherNotice(
        String id,
        @JsonProperty("created_at") OffsetDateTime createdAt,
        String message,
        WeatherBriefing briefing) {
}

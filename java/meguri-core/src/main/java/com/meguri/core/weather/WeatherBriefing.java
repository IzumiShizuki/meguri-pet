package com.meguri.core.weather;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

/** Bounded weather facts used by the desktop greeting and rain reminder. */
public record WeatherBriefing(
        WeatherLocation location,
        @JsonProperty("fetched_at") OffsetDateTime fetchedAt,
        String date,
        String summary,
        @JsonProperty("temperature_min_c") double temperatureMinC,
        @JsonProperty("temperature_max_c") double temperatureMaxC,
        @JsonProperty("rain_probability_max") int rainProbabilityMax,
        @JsonProperty("rain_soon") boolean rainSoon,
        @JsonProperty("next_rain_at") String nextRainAt,
        @JsonProperty("next_rain_probability") Integer nextRainProbability,
        String briefing) {
}

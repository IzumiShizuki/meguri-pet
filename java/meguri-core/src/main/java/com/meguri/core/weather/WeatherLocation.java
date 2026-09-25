package com.meguri.core.weather;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.DateTimeException;
import java.time.ZoneId;

/** User-selected location persisted by the local runtime. */
public record WeatherLocation(
        String name,
        double latitude,
        double longitude,
        @JsonProperty("timezone") String timezone) {

    public WeatherLocation {
        name = name == null ? "" : name.trim();
        timezone = timezone == null ? "" : timezone.trim();
        if (name.isEmpty()) throw new IllegalArgumentException("location name must not be blank");
        if (!Double.isFinite(latitude) || latitude < -90 || latitude > 90) {
            throw new IllegalArgumentException("latitude must be between -90 and 90");
        }
        if (!Double.isFinite(longitude) || longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("longitude must be between -180 and 180");
        }
        try {
            ZoneId.of(timezone);
        } catch (DateTimeException error) {
            throw new IllegalArgumentException("timezone must be a valid IANA zone", error);
        }
    }
}

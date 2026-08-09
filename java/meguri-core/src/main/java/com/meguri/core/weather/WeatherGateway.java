package com.meguri.core.weather;

import reactor.core.publisher.Mono;

import java.time.LocalDate;

/** Read-only boundary for a weather forecast provider. */
public interface WeatherGateway {
    Mono<WeatherBriefing> fetch(WeatherLocation location);

    /**
     * Fetches one requested local calendar date. Gateways that do not support
     * dated forecasts keep their current-day behavior for compatibility.
     */
    default Mono<WeatherBriefing> fetch(WeatherLocation location, LocalDate targetDate) {
        return fetch(location);
    }
}

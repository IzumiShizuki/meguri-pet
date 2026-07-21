package com.meguri.core.weather;

import reactor.core.publisher.Mono;

/** Read-only boundary for a weather forecast provider. */
public interface WeatherGateway {
    Mono<WeatherBriefing> fetch(WeatherLocation location);
}

package com.meguri.core.weather;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/** Minimal Open-Meteo adapter; it sends only coordinates and timezone. */
public final class OpenMeteoWeatherGateway implements WeatherGateway {
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private final WebClient webClient;
    private final String baseUrl;
    private final Clock clock;
    private final int rainThreshold;
    private final int rainLookaheadHours;

    public OpenMeteoWeatherGateway(WebClient webClient, String baseUrl, Clock clock,
                                   int rainThreshold, int rainLookaheadHours) {
        this.webClient = webClient;
        this.baseUrl = baseUrl;
        this.clock = clock;
        this.rainThreshold = Math.max(1, Math.min(100, rainThreshold));
        this.rainLookaheadHours = Math.max(1, Math.min(12, rainLookaheadHours));
    }

    @Override
    public Mono<WeatherBriefing> fetch(WeatherLocation location) {
        java.net.URI uri = UriComponentsBuilder.fromUriString(baseUrl)
                        .queryParam("latitude", location.latitude())
                        .queryParam("longitude", location.longitude())
                        .queryParam("hourly", "temperature_2m,precipitation_probability,weather_code")
                        .queryParam("daily", "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max")
                        .queryParam("timezone", location.timezone())
                        .queryParam("forecast_days", 2)
                        .build().encode().toUri();
        return webClient.get().uri(uri)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(json -> parse(location, json));
    }

    WeatherBriefing parse(WeatherLocation location, JsonNode json) {
        JsonNode daily = json.path("daily");
        requireArray(daily, "time");
        requireArray(daily, "weather_code");
        requireArray(daily, "temperature_2m_max");
        requireArray(daily, "temperature_2m_min");
        requireArray(daily, "precipitation_probability_max");

        ZoneId zone = ZoneId.of(location.timezone());
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(zone));
        String date = daily.path("time").path(0).asText();
        int code = daily.path("weather_code").path(0).asInt();
        double min = daily.path("temperature_2m_min").path(0).asDouble();
        double max = daily.path("temperature_2m_max").path(0).asDouble();
        int maxRain = daily.path("precipitation_probability_max").path(0).asInt();

        JsonNode hourly = json.path("hourly");
        JsonNode times = requireArray(hourly, "time");
        JsonNode probabilities = requireArray(hourly, "precipitation_probability");
        String nextRainAt = null;
        Integer nextRainProbability = null;
        ZonedDateTime lookahead = now.plusHours(rainLookaheadHours);
        for (int index = 0; index < Math.min(times.size(), probabilities.size()); index++) {
            ZonedDateTime hour = LocalDateTime.parse(times.path(index).asText(), HOUR).atZone(zone);
            int probability = probabilities.path(index).asInt();
            if (!hour.isBefore(now.withMinute(0).withSecond(0).withNano(0))
                    && !hour.isAfter(lookahead) && probability >= rainThreshold) {
                nextRainAt = hour.toOffsetDateTime().toString();
                nextRainProbability = probability;
                break;
            }
        }

        String summary = weatherCodeLabel(code);
        String briefing = "%s今天%s，%.0f～%.0f℃，最高降雨概率%d%%。".formatted(
                location.name(), summary, min, max, maxRain);
        if (nextRainAt != null) {
            ZonedDateTime rainTime = OffsetDateTime.parse(nextRainAt).atZoneSameInstant(zone);
            briefing += "预计%s点前后可能下雨，出门记得带伞。".formatted(rainTime.getHour());
        }
        return new WeatherBriefing(location, OffsetDateTime.now(clock.withZone(zone)), date, summary,
                min, max, maxRain, nextRainAt != null, nextRainAt, nextRainProbability, briefing);
    }

    private static JsonNode requireArray(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        if (!value.isArray() || value.isEmpty()) {
            throw new IllegalStateException("weather response missing " + field);
        }
        return value;
    }

    static String weatherCodeLabel(int code) {
        if (code == 0) return "晴朗";
        if (code <= 3) return "多云";
        if (code == 45 || code == 48) return "有雾";
        if (code >= 51 && code <= 57) return "有毛毛雨";
        if (code >= 61 && code <= 67) return "有雨";
        if (code >= 71 && code <= 77) return "有雪";
        if (code >= 80 && code <= 82) return "有阵雨";
        if (code >= 85 && code <= 86) return "有阵雪";
        if (code >= 95 && code <= 99) return "有雷雨";
        return "天气状况未知";
    }
}

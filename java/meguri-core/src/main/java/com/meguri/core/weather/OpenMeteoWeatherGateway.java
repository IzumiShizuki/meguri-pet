package com.meguri.core.weather;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.LocalDate;
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
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of(location.timezone())));
        return fetch(location, today);
    }

    @Override
    public Mono<WeatherBriefing> fetch(WeatherLocation location, LocalDate targetDate) {
        java.net.URI uri = UriComponentsBuilder.fromUriString(baseUrl)
                        .queryParam("latitude", location.latitude())
                        .queryParam("longitude", location.longitude())
                        .queryParam("hourly", "temperature_2m,relative_humidity_2m,wind_speed_10m,precipitation_probability,weather_code")
                        .queryParam("daily", "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max")
                        .queryParam("timezone", location.timezone())
                        .queryParam("start_date", targetDate)
                        .queryParam("end_date", targetDate)
                        .build().encode().toUri();
        return webClient.get().uri(uri)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(json -> parse(location, json, targetDate));
    }

    WeatherBriefing parse(WeatherLocation location, JsonNode json) {
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of(location.timezone())));
        return parse(location, json, today);
    }

    WeatherBriefing parse(WeatherLocation location, JsonNode json, LocalDate targetDate) {
        JsonNode daily = json.path("daily");
        JsonNode dates = requireArray(daily, "time");
        requireArray(daily, "weather_code");
        requireArray(daily, "temperature_2m_max");
        requireArray(daily, "temperature_2m_min");
        requireArray(daily, "precipitation_probability_max");

        ZoneId zone = ZoneId.of(location.timezone());
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(zone));
        int dailyIndex = -1;
        for (int index = 0; index < dates.size(); index++) {
            if (targetDate.toString().equals(dates.path(index).asText())) {
                dailyIndex = index;
                break;
            }
        }
        if (dailyIndex < 0) {
            throw new IllegalStateException("weather response missing requested date " + targetDate);
        }
        String date = dates.path(dailyIndex).asText();
        int dailyCode = daily.path("weather_code").path(dailyIndex).asInt();
        double min = daily.path("temperature_2m_min").path(dailyIndex).asDouble();
        double max = daily.path("temperature_2m_max").path(dailyIndex).asDouble();
        int maxRain = daily.path("precipitation_probability_max").path(dailyIndex).asInt();
        boolean todayRequested = targetDate.equals(now.toLocalDate());

        JsonNode hourly = json.path("hourly");
        JsonNode times = requireArray(hourly, "time");
        JsonNode probabilities = requireArray(hourly, "precipitation_probability");
        JsonNode hourlyCodes = hourly.path("weather_code");
        JsonNode hourlyTemperatures = hourly.path("temperature_2m");
        JsonNode hourlyHumidity = hourly.path("relative_humidity_2m");
        JsonNode hourlyWind = hourly.path("wind_speed_10m");
        int currentCode = dailyCode;
        Double currentTemperature = null;
        Integer currentHumidity = null;
        Double currentWindSpeed = null;
        String nextRainAt = null;
        Integer nextRainProbability = null;
        ZonedDateTime lookahead = now.plusHours(rainLookaheadHours);
        ZonedDateTime currentHour = now.withMinute(0).withSecond(0).withNano(0);
        for (int index = 0; todayRequested && index < Math.min(times.size(), probabilities.size()); index++) {
            ZonedDateTime hour = LocalDateTime.parse(times.path(index).asText(), HOUR).atZone(zone);
            if (hour.equals(currentHour) && hourlyCodes.isArray() && index < hourlyCodes.size()) {
                currentCode = hourlyCodes.path(index).asInt(dailyCode);
            }
            if (hour.equals(currentHour)) {
                if (hourlyTemperatures.isArray() && index < hourlyTemperatures.size()) {
                    currentTemperature = hourlyTemperatures.path(index).asDouble();
                }
                if (hourlyHumidity.isArray() && index < hourlyHumidity.size()) {
                    currentHumidity = hourlyHumidity.path(index).asInt();
                }
                if (hourlyWind.isArray() && index < hourlyWind.size()) {
                    currentWindSpeed = hourlyWind.path(index).asDouble();
                }
            }
            int probability = probabilities.path(index).asInt();
            if (!hour.isBefore(currentHour)
                    && !hour.isAfter(lookahead) && probability >= rainThreshold) {
                nextRainAt = hour.toOffsetDateTime().toString();
                nextRainProbability = probability;
                break;
            }
        }

        String summary = weatherCodeLabel(currentCode);
        String dailySummary = weatherCodeLabel(dailyCode);
        String dateLabel = dateLabel(now.toLocalDate(), targetDate);
        String briefing = todayRequested
                ? "%s目前%s；今天整体%s，%.0f～%.0f℃，最高降雨概率%d%%。".formatted(
                        location.name(), summary, dailySummary, min, max, maxRain)
                : "%s%s整体%s，%.0f～%.0f℃，最高降雨概率%d%%。".formatted(
                        location.name(), dateLabel, dailySummary, min, max, maxRain);
        if (currentTemperature != null) briefing += "当前%.1f℃。".formatted(currentTemperature);
        if (currentHumidity != null) briefing += "湿度%d%%。".formatted(currentHumidity);
        if (currentWindSpeed != null) briefing += "风速%.1f公里/小时。".formatted(currentWindSpeed);
        if (nextRainAt != null) {
            ZonedDateTime rainTime = OffsetDateTime.parse(nextRainAt).atZoneSameInstant(zone);
            briefing += "预计%s点前后可能下雨，出门记得带伞。".formatted(rainTime.getHour());
        }
        return new WeatherBriefing(location, OffsetDateTime.now(clock.withZone(zone)), date, summary,
                currentTemperature, currentHumidity, currentWindSpeed,
                min, max, maxRain, nextRainAt != null, nextRainAt, nextRainProbability, briefing);
    }

    private static String dateLabel(LocalDate today, LocalDate targetDate) {
        long days = java.time.temporal.ChronoUnit.DAYS.between(today, targetDate);
        if (days == 0) return "今天";
        if (days == 1) return "明天";
        if (days == 2) return "后天";
        return "%d月%d日".formatted(targetDate.getMonthValue(), targetDate.getDayOfMonth());
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

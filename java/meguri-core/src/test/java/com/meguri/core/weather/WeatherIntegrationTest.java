package com.meguri.core.weather;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WeatherIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final WeatherLocation SHANGHAI =
            new WeatherLocation("上海", 31.2304, 121.4737, "Asia/Shanghai");

    @TempDir
    Path temporaryDirectory;

    @Test
    void parsesDailyBriefingAndDetectsRainWithinLookahead() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-07-21T00:30:00Z"), ZoneOffset.UTC);
        OpenMeteoWeatherGateway gateway = new OpenMeteoWeatherGateway(
                WebClient.create(), "https://api.open-meteo.com/v1/forecast", clock, 50, 2);
        JsonNode json = MAPPER.readTree("""
                {
                  "daily": {
                    "time": ["2026-07-21"], "weather_code": [95],
                    "temperature_2m_max": [31.6], "temperature_2m_min": [26.4],
                    "precipitation_probability_max": [94]
                  },
                  "hourly": {
                    "time": ["2026-07-21T08:00", "2026-07-21T09:00", "2026-07-21T10:00"],
                    "precipitation_probability": [20, 70, 40]
                  }
                }
                """);

        WeatherBriefing result = gateway.parse(SHANGHAI, json);

        assertThat(result.summary()).isEqualTo("有雷雨");
        assertThat(result.rainSoon()).isTrue();
        assertThat(result.nextRainProbability()).isEqualTo(70);
        assertThat(result.briefing()).contains("上海今天有雷雨").contains("9点前后可能下雨");
    }

    @Test
    void savesLocationAtomicallyAndReloadsIt() throws Exception {
        Path locationFile = temporaryDirectory.resolve("weather-location.json");
        StubGateway gateway = new StubGateway();
        WeatherService first = new WeatherService(gateway, MAPPER, true, locationFile, SHANGHAI,
                Clock.fixed(Instant.parse("2026-07-21T00:00:00Z"), ZoneOffset.UTC));
        WeatherLocation tokyo = new WeatherLocation("东京", 35.6762, 139.6503, "Asia/Tokyo");

        first.saveLocation(tokyo);
        WeatherService reloaded = new WeatherService(gateway, MAPPER, true, locationFile, SHANGHAI,
                Clock.systemUTC());

        assertThat(Files.readString(locationFile)).contains("东京");
        assertThat(reloaded.location()).isEqualTo(tokyo);
    }

    @Test
    void refreshesOnlyOnceDuringTheSavedLocationsTwoAmHour() {
        StubGateway gateway = new StubGateway();
        WeatherService service = new WeatherService(gateway, MAPPER, true,
                temporaryDirectory.resolve("location.json"), SHANGHAI,
                Clock.fixed(Instant.parse("2026-07-20T18:15:00Z"), ZoneOffset.UTC));

        service.refreshAtTwoLocalTime();
        service.refreshAtTwoLocalTime();

        assertThat(gateway.calls).hasValue(1);
    }

    @Test
    void disabledWeatherNeverContactsProvider() {
        StubGateway gateway = new StubGateway();
        WeatherService service = new WeatherService(gateway, MAPPER, false,
                temporaryDirectory.resolve("location.json"), SHANGHAI, Clock.systemUTC());

        assertThatThrownBy(() -> service.current(false).block())
                .isInstanceOf(WeatherService.WeatherDisabledException.class);
        assertThat(gateway.calls).hasValue(0);
    }

    private static final class StubGateway implements WeatherGateway {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public Mono<WeatherBriefing> fetch(WeatherLocation location) {
            calls.incrementAndGet();
            return Mono.just(new WeatherBriefing(location, OffsetDateTime.parse("2026-07-21T02:00:00+08:00"),
                    "2026-07-21", "晴朗", 24, 31, 10, false, null, null,
                    location.name() + "今天晴朗。"));
        }
    }
}

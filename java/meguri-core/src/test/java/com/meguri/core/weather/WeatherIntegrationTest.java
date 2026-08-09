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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
                    "precipitation_probability": [20, 70, 40],
                    "weather_code": [2, 61, 3],
                    "temperature_2m": [28.2, 29.1, 30.0],
                    "relative_humidity_2m": [78, 75, 70],
                    "wind_speed_10m": [12.4, 15.0, 18.0]
                  }
                }
                """);

        WeatherBriefing result = gateway.parse(SHANGHAI, json);

        assertThat(result.summary()).isEqualTo("多云");
        assertThat(result.currentTemperatureC()).isEqualTo(28.2);
        assertThat(result.relativeHumidityPercent()).isEqualTo(78);
        assertThat(result.windSpeedKmh()).isEqualTo(12.4);
        assertThat(result.rainSoon()).isTrue();
        assertThat(result.nextRainProbability()).isEqualTo(70);
        assertThat(result.briefing()).contains("上海目前多云").contains("今天整体有雷雨")
                .contains("湿度78%").contains("风速12.4公里/小时").contains("9点前后可能下雨");
    }

    @Test
    void selectsTheRequestedFutureDateInsteadOfAlwaysReturningToday() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-07-21T00:30:00Z"), ZoneOffset.UTC);
        OpenMeteoWeatherGateway gateway = new OpenMeteoWeatherGateway(
                WebClient.create(), "https://api.open-meteo.com/v1/forecast", clock, 50, 2);
        JsonNode json = MAPPER.readTree("""
                {
                  "daily": {
                    "time": ["2026-07-21", "2026-07-22"],
                    "weather_code": [0, 61],
                    "temperature_2m_max": [31.6, 29.2],
                    "temperature_2m_min": [26.4, 24.1],
                    "precipitation_probability_max": [10, 80]
                  },
                  "hourly": {
                    "time": ["2026-07-22T08:00"],
                    "precipitation_probability": [70],
                    "weather_code": [61],
                    "temperature_2m": [25.0],
                    "relative_humidity_2m": [82],
                    "wind_speed_10m": [12.0]
                  }
                }
                """);

        WeatherBriefing result =
                gateway.parse(SHANGHAI, json, LocalDate.parse("2026-07-22"));

        assertThat(result.date()).isEqualTo("2026-07-22");
        assertThat(result.summary()).isEqualTo("有雨");
        assertThat(result.currentTemperatureC()).isNull();
        assertThat(result.briefing()).contains("上海明天整体有雨").contains("24～29℃")
                .contains("最高降雨概率80%");
    }

    @Test
    void rejectsAMissingRequestedDateInsteadOfUsingTodaysForecast() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-07-21T00:30:00Z"), ZoneOffset.UTC);
        OpenMeteoWeatherGateway gateway = new OpenMeteoWeatherGateway(
                WebClient.create(), "https://api.open-meteo.com/v1/forecast", clock, 50, 2);
        JsonNode json = MAPPER.readTree("""
                {
                  "daily": {
                    "time": ["2026-07-21"], "weather_code": [0],
                    "temperature_2m_max": [31.6], "temperature_2m_min": [26.4],
                    "precipitation_probability_max": [10]
                  },
                  "hourly": {
                    "time": ["2026-07-21T08:00"], "precipitation_probability": [10],
                    "weather_code": [0], "temperature_2m": [28.2],
                    "relative_humidity_2m": [78], "wind_speed_10m": [12.4]
                  }
                }
                """);

        assertThatThrownBy(() -> gateway.parse(SHANGHAI, json, LocalDate.parse("2026-07-22")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requested date 2026-07-22");
    }

    @Test
    void controllerResolvesFutureWeatherDatesInTheSavedLocationTimezone() {
        AtomicReference<LocalDate> requestedDate = new AtomicReference<>();
        WeatherGateway gateway = new WeatherGateway() {
            @Override
            public Mono<WeatherBriefing> fetch(WeatherLocation location) {
                return Mono.just(briefing("晴朗", false, 10, 27.0, 8.0));
            }

            @Override
            public Mono<WeatherBriefing> fetch(WeatherLocation location, LocalDate targetDate) {
                requestedDate.set(targetDate);
                return Mono.just(briefing("有雨", false, 80, 24.0, 10.0));
            }
        };
        WeatherService service = new WeatherService(gateway, MAPPER, true,
                temporaryDirectory.resolve("dated-location.json"), SHANGHAI,
                Clock.fixed(Instant.parse("2026-07-21T00:00:00Z"), ZoneOffset.UTC));
        WeatherController controller = new WeatherController(service);

        WeatherBriefing result = controller.briefing("明天", true).block();

        assertThat(requestedDate).hasValue(LocalDate.parse("2026-07-22"));
        assertThat(result).isNotNull();
        assertThat(service.resolveForecastDate("tomorrow")).isEqualTo(LocalDate.parse("2026-07-22"));
        assertThat(service.resolveForecastDate("day after tomorrow")).isEqualTo(LocalDate.parse("2026-07-23"));
        assertThat(service.resolveForecastDate("明天 2026-07-24")).isEqualTo(LocalDate.parse("2026-07-24"));
        assertThat(service.resolveForecastDate(null)).isEqualTo(LocalDate.parse("2026-07-21"));

        WeatherService atShanghaiDateBoundary = new WeatherService(gateway, MAPPER, true,
                temporaryDirectory.resolve("timezone-boundary-location.json"), SHANGHAI,
                Clock.fixed(Instant.parse("2026-07-21T16:30:00Z"), ZoneOffset.UTC));
        assertThat(atShanghaiDateBoundary.resolveForecastDate("tomorrow"))
                .isEqualTo(LocalDate.parse("2026-07-23"));
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

    @Test
    void concurrentRefreshRequestsShareOneProviderCall() {
        StubGateway gateway = new StubGateway();
        WeatherService service = new WeatherService(gateway, MAPPER, true,
                temporaryDirectory.resolve("location.json"), SHANGHAI, Clock.systemUTC());

        Mono<WeatherBriefing> first = service.current(true);
        Mono<WeatherBriefing> second = service.current(true);

        assertThat(first.block()).isEqualTo(second.block());
        assertThat(gateway.calls).hasValue(1);
    }

    @Test
    void workHourCheckPublishesOnlyAChangedOrProblematicForecastOncePerHour() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-21T00:15:00Z"), ZoneOffset.UTC);
        SequenceGateway gateway = new SequenceGateway(
                briefing("晴朗", false, 10, 27.0, 8.0),
                briefing("有阵雨", true, 80, 25.0, 18.0));
        WeatherService service = new WeatherService(gateway, MAPPER, true,
                temporaryDirectory.resolve("hourly-location.json"), SHANGHAI, clock, 8, 18);

        service.refreshDuringWorkingHours();
        assertThat(service.latestNotice()).isNull();

        clock.advance(Duration.ofHours(1));
        service.refreshDuringWorkingHours();
        service.refreshDuringWorkingHours();

        assertThat(gateway.calls).hasValue(2);
        assertThat(service.latestNotice()).isNotNull();
        assertThat(service.latestNotice().id()).isEqualTo("2026-07-21T09");
        assertThat(service.latestNotice().message()).contains("天气有变化").contains("有阵雨");
    }

    @Test
    void failedWorkHourRefreshCanRetryWithinTheSameHour() {
        AtomicInteger calls = new AtomicInteger();
        WeatherGateway gateway = location -> calls.getAndIncrement() == 0
                ? Mono.error(new IllegalStateException("temporary outage"))
                : Mono.just(briefing("晴朗", false, 10, 27.0, 8.0));
        WeatherService service = new WeatherService(gateway, MAPPER, true,
                temporaryDirectory.resolve("retry-location.json"), SHANGHAI,
                Clock.fixed(Instant.parse("2026-07-21T00:15:00Z"), ZoneOffset.UTC), 8, 18);

        service.refreshDuringWorkingHours();
        service.refreshDuringWorkingHours();

        assertThat(calls).hasValue(2);
        assertThat(service.latest()).isNotNull();
    }

    @Test
    void stableHazardDoesNotPublishANewNoticeEveryHour() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-21T00:15:00Z"), ZoneOffset.UTC);
        WeatherBriefing rain = briefing("有阵雨", true, 80, 25.0, 18.0);
        SequenceGateway gateway = new SequenceGateway(rain, rain);
        WeatherService service = new WeatherService(gateway, MAPPER, true,
                temporaryDirectory.resolve("stable-hazard.json"), SHANGHAI, clock, 8, 18);

        service.refreshDuringWorkingHours();
        String firstNoticeId = service.latestNotice().id();
        clock.advance(Duration.ofHours(1));
        service.refreshDuringWorkingHours();

        assertThat(gateway.calls).hasValue(2);
        assertThat(service.latestNotice().id()).isEqualTo(firstNoticeId);
    }

    @Test
    void defaultWorkWindowIncludesNinePmAndStopsAtTenPm() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-21T13:15:00Z"), ZoneOffset.UTC);
        StubGateway gateway = new StubGateway();
        WeatherService service = new WeatherService(gateway, MAPPER, true,
                temporaryDirectory.resolve("evening-window.json"), SHANGHAI, clock);

        service.refreshDuringWorkingHours();
        clock.advance(Duration.ofHours(1));
        service.refreshDuringWorkingHours();

        assertThat(gateway.calls).hasValue(1);
    }

    @Test
    void weekendIsOutsideConfiguredWorkHours() {
        StubGateway gateway = new StubGateway();
        WeatherService service = new WeatherService(gateway, MAPPER, true,
                temporaryDirectory.resolve("weekend.json"), SHANGHAI,
                Clock.fixed(Instant.parse("2026-07-25T01:15:00Z"), ZoneOffset.UTC), 8, 18);

        service.refreshDuringWorkingHours();

        assertThat(gateway.calls).hasValue(0);
    }

    private static WeatherBriefing briefing(String summary, boolean rainSoon, int rainProbability,
                                             double currentTemperature, double windSpeed) {
        return new WeatherBriefing(SHANGHAI, OffsetDateTime.parse("2026-07-21T08:15:00+08:00"),
                "2026-07-21", summary, currentTemperature, 65, windSpeed,
                24, 31, rainProbability, rainSoon,
                rainSoon ? "2026-07-21T10:00:00+08:00" : null,
                rainSoon ? rainProbability : null,
                "上海目前" + summary + "。" + (rainSoon ? "临近可能下雨。" : ""));
    }

    private static final class SequenceGateway implements WeatherGateway {
        private final AtomicInteger calls = new AtomicInteger();
        private final Deque<WeatherBriefing> values = new ArrayDeque<>();

        private SequenceGateway(WeatherBriefing... values) {
            this.values.addAll(java.util.List.of(values));
        }

        @Override
        public Mono<WeatherBriefing> fetch(WeatherLocation location) {
            calls.incrementAndGet();
            return Mono.just(values.removeFirst());
        }
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;
        private final ZoneId zone;

        private MutableClock(Instant instant, ZoneId zone) {
            this(new AtomicReference<>(instant), zone);
        }

        private MutableClock(AtomicReference<Instant> instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void advance(Duration duration) { instant.updateAndGet(value -> value.plus(duration)); }
        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId zone) { return new MutableClock(instant, zone); }
        @Override public Instant instant() { return instant.get(); }
    }

    private static final class StubGateway implements WeatherGateway {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public Mono<WeatherBriefing> fetch(WeatherLocation location) {
            calls.incrementAndGet();
            return Mono.just(new WeatherBriefing(location, OffsetDateTime.parse("2026-07-21T02:00:00+08:00"),
                    "2026-07-21", "晴朗", 27.0, 60, 8.0, 24, 31, 10, false, null, null,
                    location.name() + "今天晴朗。"));
        }
    }
}

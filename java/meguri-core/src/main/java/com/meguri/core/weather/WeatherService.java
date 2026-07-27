package com.meguri.core.weather;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Owns persisted location, the 02:00 refresh and bounded rain polling. */
@Service
public final class WeatherService {
    private final WeatherGateway gateway;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final Path locationFile;
    private final Clock clock;
    private final int workStartHour;
    private final int workEndHour;
    private final AtomicReference<WeatherLocation> location;
    private final AtomicReference<WeatherBriefing> latest = new AtomicReference<>();
    private final AtomicReference<Mono<WeatherBriefing>> inFlight = new AtomicReference<>();
    private final AtomicReference<LocalDate> lastDailyRefresh = new AtomicReference<>();
    private final AtomicReference<String> lastWorkHour = new AtomicReference<>();
    private final AtomicReference<WeatherBriefing> previousWorkBriefing = new AtomicReference<>();
    private final AtomicReference<WeatherNotice> latestNotice = new AtomicReference<>();

    @Autowired
    public WeatherService(
            WeatherGateway gateway,
            ObjectMapper mapper,
            @Value("${meguri.weather.enabled:true}") boolean enabled,
            @Value("${meguri.weather.location-file:${user.home}/.meguri/weather-location.json}") String locationFile,
            @Value("${meguri.weather.location-name:浙江省杭州市钱塘区}") String defaultName,
            @Value("${meguri.weather.latitude:30.323040}") double defaultLatitude,
            @Value("${meguri.weather.longitude:120.493941}") double defaultLongitude,
            @Value("${meguri.weather.timezone:Asia/Shanghai}") String defaultTimezone,
            @Value("${meguri.weather.work-start-hour:8}") int workStartHour,
            @Value("${meguri.weather.work-end-hour:22}") int workEndHour) {
        this(gateway, mapper, enabled, Path.of(locationFile),
                new WeatherLocation(defaultName, defaultLatitude, defaultLongitude, defaultTimezone), Clock.systemUTC(),
                workStartHour, workEndHour);
    }

    WeatherService(WeatherGateway gateway, ObjectMapper mapper, boolean enabled, Path locationFile,
                   WeatherLocation fallback, Clock clock) {
        this(gateway, mapper, enabled, locationFile, fallback, clock, 8, 22);
    }

    WeatherService(WeatherGateway gateway, ObjectMapper mapper, boolean enabled, Path locationFile,
                   WeatherLocation fallback, Clock clock, int workStartHour, int workEndHour) {
        this.gateway = gateway;
        this.mapper = mapper;
        this.enabled = enabled;
        this.locationFile = locationFile;
        this.clock = clock;
        if (workStartHour < 0 || workStartHour > 23 || workEndHour < 1 || workEndHour > 24
                || workStartHour >= workEndHour) {
            throw new IllegalArgumentException("weather work hours must be within one day");
        }
        this.workStartHour = workStartHour;
        this.workEndHour = workEndHour;
        this.location = new AtomicReference<>(loadLocation(fallback));
    }

    public boolean enabled() { return enabled; }
    public WeatherLocation location() { return location.get(); }
    public WeatherBriefing latest() { return latest.get(); }
    public WeatherNotice latestNotice() { return latestNotice.get(); }

    public WeatherLocation saveLocation(WeatherLocation requested) {
        try {
            Path parent = locationFile.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Path temporary = locationFile.resolveSibling(locationFile.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), requested);
            try {
                Files.move(temporary, locationFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveUnsupported) {
                Files.move(temporary, locationFile, StandardCopyOption.REPLACE_EXISTING);
            }
            synchronized (inFlight) {
                location.set(requested);
                latest.set(null);
                inFlight.set(null);
                previousWorkBriefing.set(null);
                lastWorkHour.set(null);
                latestNotice.set(null);
            }
            return requested;
        } catch (IOException error) {
            throw new IllegalStateException("failed to persist weather location", error);
        }
    }

    public Mono<WeatherBriefing> current(boolean forceRefresh) {
        if (!enabled) return Mono.error(new WeatherDisabledException());
        WeatherBriefing cached = latest.get();
        if (!forceRefresh && cached != null) return Mono.just(cached);
        synchronized (inFlight) {
            Mono<WeatherBriefing> existing = inFlight.get();
            if (existing != null) return existing;
            WeatherLocation selected = location.get();
            AtomicReference<Mono<WeatherBriefing>> holder = new AtomicReference<>();
            Mono<WeatherBriefing> created = gateway.fetch(selected)
                    .doOnNext(value -> {
                        if (selected.equals(location.get())) latest.set(value);
                    })
                    .doFinally(ignored -> inFlight.compareAndSet(holder.get(), null))
                    .cache();
            holder.set(created);
            inFlight.set(created);
            return created;
        }
    }

    /** Uses the saved location timezone, so a timezone change takes effect without restart. */
    @Scheduled(fixedDelay = 60_000L)
    public void refreshAtTwoLocalTime() {
        if (!enabled) return;
        WeatherLocation selected = location.get();
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(java.time.ZoneId.of(selected.timezone())));
        LocalDate today = now.toLocalDate();
        if (now.getHour() == 2 && !today.equals(lastDailyRefresh.get())) {
            current(true).doOnSuccess(ignored -> lastDailyRefresh.set(today)).subscribe(
                    ignored -> { }, error -> { });
        }
    }

    @Scheduled(fixedDelayString = "${meguri.weather.poll-delay-ms:900000}")
    public void pollForRain() {
        if (!enabled) return;
        current(true).subscribe(ignored -> { }, error -> { });
    }

    /** Checks exactly once per local work hour and only publishes meaningful changes or hazards. */
    @Scheduled(fixedDelay = 60_000L)
    public void refreshDuringWorkingHours() {
        if (!enabled) return;
        WeatherLocation selected = location.get();
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(java.time.ZoneId.of(selected.timezone())));
        DayOfWeek day = now.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) return;
        if (now.getHour() < workStartHour || now.getHour() >= workEndHour) return;
        String hourKey = "%sT%02d".formatted(now.toLocalDate(), now.getHour());
        String previousHour = lastWorkHour.get();
        if (hourKey.equals(previousHour) || !lastWorkHour.compareAndSet(previousHour, hourKey)) return;
        current(true).subscribe(current -> evaluateWorkHourWeather(hourKey, current),
                error -> lastWorkHour.compareAndSet(hourKey, previousHour));
    }

    private void evaluateWorkHourWeather(String hourKey, WeatherBriefing current) {
        WeatherBriefing previous = previousWorkBriefing.getAndSet(current);
        boolean changed = previous != null && materiallyChanged(previous, current);
        if (previous == null ? !hasWeatherProblem(current) : !changed) return;
        String lead = changed ? "刚刚查到天气有变化：" : "刚刚查了工作时段天气：";
        WeatherNotice notice = new WeatherNotice(
                hourKey,
                OffsetDateTime.now(clock.withZone(java.time.ZoneId.of(current.location().timezone()))),
                lead + current.briefing(), current);
        latestNotice.set(notice);
    }

    private static boolean materiallyChanged(WeatherBriefing before, WeatherBriefing after) {
        if (hasWeatherProblem(before) != hasWeatherProblem(after)) return true;
        if (!Objects.equals(before.summary(), after.summary())) return true;
        if (before.rainSoon() != after.rainSoon()) return true;
        if (!Objects.equals(before.nextRainAt(), after.nextRainAt())) return true;
        Integer beforeRain = before.nextRainProbability();
        Integer afterRain = after.nextRainProbability();
        if (beforeRain != null && afterRain != null && Math.abs(beforeRain - afterRain) >= 20) return true;
        if (before.currentTemperatureC() != null && after.currentTemperatureC() != null
                && Math.abs(before.currentTemperatureC() - after.currentTemperatureC()) >= 5) return true;
        if (before.windSpeedKmh() != null && after.windSpeedKmh() != null
                && before.windSpeedKmh() < 30 && after.windSpeedKmh() >= 30) return true;
        return false;
    }

    private static boolean hasWeatherProblem(WeatherBriefing briefing) {
        String summary = briefing.summary();
        return briefing.rainSoon()
                || briefing.currentTemperatureC() != null && (briefing.currentTemperatureC() <= 0
                || briefing.currentTemperatureC() >= 35)
                || briefing.windSpeedKmh() != null && briefing.windSpeedKmh() >= 39
                || briefing.temperatureMinC() <= 0
                || briefing.temperatureMaxC() >= 35
                || summary.contains("雨") || summary.contains("雪") || summary.contains("雾")
                || summary.contains("未知");
    }

    private WeatherLocation loadLocation(WeatherLocation fallback) {
        if (!Files.isRegularFile(locationFile)) return fallback;
        try {
            return mapper.readValue(locationFile.toFile(), WeatherLocation.class);
        } catch (IOException | IllegalArgumentException ignored) {
            return fallback;
        }
    }

    public static final class WeatherDisabledException extends RuntimeException {
        public WeatherDisabledException() { super("weather integration is disabled"); }
    }
}

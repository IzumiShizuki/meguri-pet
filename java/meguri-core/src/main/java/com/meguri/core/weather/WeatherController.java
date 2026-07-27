package com.meguri.core.weather;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.Map;

/** Loopback desktop API for saved location, startup briefing and manual refresh. */
@RestController
@RequestMapping("/v1/daily/weather")
@CrossOrigin(origins = {
        "http://127.0.0.1:4173", "http://127.0.0.1:5173",
        "http://localhost:4173", "http://localhost:5173"
})
public final class WeatherController {
    private final WeatherService service;

    public WeatherController(WeatherService service) {
        this.service = service;
    }

    @GetMapping(path = "/location", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> location() {
        return Map.of("enabled", service.enabled(), "location", service.location());
    }

    @PutMapping(path = "/location", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> saveLocation(@RequestBody WeatherLocation location) {
        WeatherLocation saved = service.saveLocation(location);
        if (!service.enabled()) return Mono.just(Map.of("enabled", false, "location", saved));
        return service.current(true).map(briefing -> Map.of(
                "enabled", true, "location", saved, "briefing", briefing));
    }

    @GetMapping(path = "/briefing", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<WeatherBriefing> briefing(
            @RequestParam(name = "refresh", defaultValue = "false") boolean refresh) {
        return service.current(refresh).onErrorMap(WeatherService.WeatherDisabledException.class,
                ignored -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "weather integration is disabled; set MEGURI_WEATHER_ENABLED=true"));
    }

    @PostMapping(path = "/refresh", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<WeatherBriefing> refresh() {
        return briefing(true);
    }

    /** Desktop polling boundary for work-hour changes; unchanged notices return 204. */
    @GetMapping(path = "/notice", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<WeatherNotice> notice(
            @RequestParam(name = "after_id", defaultValue = "") String afterId) {
        WeatherNotice notice = service.latestNotice();
        if (notice == null || notice.id().equals(afterId)) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(notice);
    }
}

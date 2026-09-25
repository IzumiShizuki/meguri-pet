package com.meguri.core.daily;

import jakarta.validation.Valid;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** Upload and polling API shared by the local report producer and AstrBot. */
@RestController
@RequestMapping("/v1/daily/reports")
public final class DailyReportController {
    private static final MediaType MARKDOWN = new MediaType("text", "markdown", StandardCharsets.UTF_8);

    private final DailyReportStore store;

    public DailyReportController(DailyReportStore store) {
        this.store = store;
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<DailyReportReceipt> upload(@Valid @RequestBody DailyReportUpload upload) {
        return Mono.fromCallable(() -> store.save(upload))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(IllegalArgumentException.class,
                        error -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, error.getMessage()));
    }

    @GetMapping(path = "/latest", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<DailyReportReceipt> latest(@RequestParam(defaultValue = "bilibili") String kind) {
        return Mono.fromCallable(() -> store.latest(kind)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "report not found")))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(IllegalArgumentException.class,
                        error -> new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage()));
    }

    @GetMapping(path = "/{kind}/{date}/markdown", produces = "text/markdown;charset=UTF-8")
    public Mono<ResponseEntity<Resource>> markdown(
            @PathVariable String kind, @PathVariable String date) {
        return Mono.fromCallable(() -> {
                    Path path = store.markdown(kind, date)
                            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "report not found"));
                    return ResponseEntity.<Resource>ok()
                            .contentType(MARKDOWN)
                            .cacheControl(CacheControl.noStore())
                            .header("X-Content-Type-Options", "nosniff")
                            .body((Resource) new FileSystemResource(path));
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(IllegalArgumentException.class,
                        error -> new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage()));
    }
}

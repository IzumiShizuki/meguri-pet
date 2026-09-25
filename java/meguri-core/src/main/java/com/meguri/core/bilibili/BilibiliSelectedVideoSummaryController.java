package com.meguri.core.bilibili;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Explicit-selection gate for content summaries; never runs from the daily-report path. */
@RestController
@RequestMapping("/v1/daily/bilibili")
@CrossOrigin(origins = {
        "http://127.0.0.1:4173", "http://127.0.0.1:5173",
        "http://localhost:4173", "http://localhost:5173"
})
public final class BilibiliSelectedVideoSummaryController {
    private final BilibiliSelectedVideoSummaryGateway gateway;

    public BilibiliSelectedVideoSummaryController(BilibiliSelectedVideoSummaryGateway gateway) {
        this.gateway = gateway;
    }

    @PostMapping(
            path = "/selected-summary",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<BilibiliSelectedVideoSummaryResult> summarize(
            @RequestBody BilibiliSelectedVideoSummaryRequest request) {
        return gateway.summarize(request);
    }
}

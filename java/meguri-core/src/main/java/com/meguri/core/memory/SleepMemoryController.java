package com.meguri.core.memory;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Loopback inspection and explicit-run endpoint for sleep-memory consolidation. */
@RestController
@RequestMapping("/v1/memory/sleep-consolidation")
@CrossOrigin(origins = {
        "http://127.0.0.1:4173", "http://127.0.0.1:5173",
        "http://localhost:4173", "http://localhost:5173"
})
public final class SleepMemoryController {
    private final SleepMemoryConsolidationService service;

    public SleepMemoryController(SleepMemoryConsolidationService service) {
        this.service = service;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public SleepMemoryReport latest() {
        return service.lastReport();
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<SleepMemoryReport> consolidateNow() {
        return service.consolidateNow();
    }
}

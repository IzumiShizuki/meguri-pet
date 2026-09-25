package com.meguri.core.metrics;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Local numeric-only diagnostics endpoint; it never exposes prompt, memory, or reply content. */
@RestController
@RequestMapping("/v1/runtime/metrics/prompt-cache")
public class PromptCacheMetricsController {
    private final PromptCacheMetricsService metrics;

    public PromptCacheMetricsController(PromptCacheMetricsService metrics) {
        this.metrics = metrics;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public PromptCacheMetricsSnapshot snapshot() {
        return metrics.snapshot();
    }
}

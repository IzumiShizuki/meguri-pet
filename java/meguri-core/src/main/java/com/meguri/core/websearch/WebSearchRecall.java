package com.meguri.core.websearch;

import java.util.List;

/** Search result and availability state passed into the LLM context. */
public record WebSearchRecall(String status, String provider, List<WebSearchResult> results,
                              List<String> diagnosticCodes) {
    public WebSearchRecall(String status, String provider, List<WebSearchResult> results) {
        this(status, provider, results, List.of());
    }
    public WebSearchRecall {
        status = status == null || status.isBlank() ? "unavailable" : status;
        provider = provider == null || provider.isBlank() ? "none" : provider;
        results = results == null ? List.of() : List.copyOf(results);
        diagnosticCodes = diagnosticCodes == null ? List.of() : List.copyOf(diagnosticCodes);
    }

    public static WebSearchRecall disabled() {
        return new WebSearchRecall("disabled", "none", List.of());
    }

    public static WebSearchRecall unavailable(String provider) {
        return new WebSearchRecall("unavailable", provider, List.of());
    }

    public static WebSearchRecall unavailable(String provider, String diagnosticCode) {
        return new WebSearchRecall("unavailable", provider, List.of(), List.of(diagnosticCode));
    }

    public List<String> contextLines() {
        return results.stream().map(WebSearchResult::asContextLine).toList();
    }
}

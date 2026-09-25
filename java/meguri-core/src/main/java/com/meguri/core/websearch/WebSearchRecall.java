package com.meguri.core.websearch;

import java.util.List;

/** Search result and availability state passed into the LLM context. */
public record WebSearchRecall(String status, String provider, List<WebSearchResult> results) {
    public WebSearchRecall {
        status = status == null || status.isBlank() ? "unavailable" : status;
        provider = provider == null || provider.isBlank() ? "none" : provider;
        results = results == null ? List.of() : List.copyOf(results);
    }

    public static WebSearchRecall disabled() {
        return new WebSearchRecall("disabled", "none", List.of());
    }

    public static WebSearchRecall unavailable(String provider) {
        return new WebSearchRecall("unavailable", provider, List.of());
    }

    public List<String> contextLines() {
        return results.stream().map(WebSearchResult::asContextLine).toList();
    }
}

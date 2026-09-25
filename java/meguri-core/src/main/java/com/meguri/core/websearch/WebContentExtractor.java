package com.meguri.core.websearch;

import java.time.Instant;
import reactor.core.publisher.Mono;

/** Fetches and extracts one already policy-approved public HTTPS page. */
@FunctionalInterface
public interface WebContentExtractor {
    Mono<ExtractedWebPage> extract(WebSearchResult result, Instant deadline);

    static WebContentExtractor snippets() {
        return (result, deadline) -> result.snippet().isBlank()
                ? Mono.empty()
                : Mono.just(new ExtractedWebPage(
                        result.url(), result.title(), result.snippet(), Instant.now()));
    }
}

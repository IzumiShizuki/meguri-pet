package com.meguri.core.websearch;

import reactor.core.publisher.Mono;

/** Controlled web lookup boundary used by the Java turn orchestrator. */
public interface WebSearchGateway {
    Mono<WebSearchRecall> search(String query, int limit);

    default String providerName() {
        return getClass().getSimpleName();
    }

    default boolean shouldSearch(String message) {
        return WebSearchPolicy.shouldSearch(message);
    }
}

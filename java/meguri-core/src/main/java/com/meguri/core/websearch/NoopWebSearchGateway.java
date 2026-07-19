package com.meguri.core.websearch;

import reactor.core.publisher.Mono;

/** Offline default. It never contacts a network service. */
public final class NoopWebSearchGateway implements WebSearchGateway {
    @Override
    public Mono<WebSearchRecall> search(String query, int limit) {
        return Mono.just(WebSearchRecall.disabled());
    }

    @Override
    public String providerName() {
        return "disabled";
    }
}

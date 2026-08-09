package com.meguri.core.react;

import reactor.core.publisher.Mono;

public interface ReactTraceRepository {
    Mono<Void> append(ReactRoundTrace trace);

    Mono<Void> complete(ReactExecutionSummary summary);
}

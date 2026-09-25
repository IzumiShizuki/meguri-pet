package com.meguri.core.react;

import reactor.core.publisher.Mono;

/** Adapter port. Production wiring must delegate to the existing Capability Runtime. */
@FunctionalInterface
public interface ReactActionExecutor {
    Mono<RawReactObservation> execute(
            ValidatedReactAction action,
            ReactExecutionContext context);
}

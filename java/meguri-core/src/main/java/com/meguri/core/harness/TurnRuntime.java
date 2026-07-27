package com.meguri.core.harness;

import com.meguri.core.dto.EventEnvelope;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The sole application-facing turn seam. Context, Persona, Capability and
 * persistence details remain behind this interface.
 */
public interface TurnRuntime {
    Mono<TurnSnapshot> submit(TurnCommand command);

    Flux<EventEnvelope> events(EventCursor cursor);

    Mono<TurnSnapshot> snapshot(String turnId);
}

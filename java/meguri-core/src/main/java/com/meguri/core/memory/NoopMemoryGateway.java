package com.meguri.core.memory;

import com.meguri.core.dto.LlmResponse;
import com.meguri.core.dto.MemoryCandidate;
import com.meguri.core.dto.TurnRequest;
import reactor.core.publisher.Mono;

import java.util.List;

/** Safe default when the internal Python bridge is not configured. */
public final class NoopMemoryGateway implements MemoryGateway {
    @Override
    public Mono<MemoryRecall> recall(TurnRequest request) {
        return Mono.just(MemoryRecall.unavailable());
    }

    @Override
    public Mono<List<MemoryCandidate>> extract(TurnRequest request) {
        return Mono.just(List.of());
    }

    @Override
    public Mono<MemoryWriteResult> write(TurnRequest request, LlmResponse response, String turnId, String traceId) {
        return Mono.just(MemoryWriteResult.unavailable());
    }
}

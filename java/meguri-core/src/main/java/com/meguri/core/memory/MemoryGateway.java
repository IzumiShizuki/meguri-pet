package com.meguri.core.memory;

import com.meguri.core.dto.LlmResponse;
import com.meguri.core.dto.MemoryCandidate;
import com.meguri.core.dto.TurnRequest;
import reactor.core.publisher.Mono;

import java.util.List;

/** Phase-one boundary around the Python authoritative memory provider. */
public interface MemoryGateway {
    Mono<MemoryRecall> recall(TurnRequest request);

    Mono<List<MemoryCandidate>> extract(TurnRequest request);

    Mono<MemoryWriteResult> write(TurnRequest request, LlmResponse response, String turnId, String traceId);
}

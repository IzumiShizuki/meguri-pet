package com.meguri.core.llm;

import com.meguri.core.dto.LlmResponse;
import com.meguri.core.dto.RuntimeState;
import com.meguri.core.dto.TurnRequest;
import java.util.List;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Online LLM boundary used by the Java runtime. Implementations are backed by
 * LangChain4j models; the interface keeps the Meguri contract independent from
 * a particular vendor.
 */
public interface LlmProvider {
    Mono<LlmResponse> respond(TurnRequest request, RuntimeState state,
                              List<String> canon, List<String> memories,
                              List<String> recentContext);

    /** Optional bounded web context; legacy providers may ignore it. */
    default Mono<LlmResponse> respond(TurnRequest request, RuntimeState state,
                                      List<String> canon, List<String> memories,
                                      List<String> recentContext, List<String> webResults) {
        return respond(request, state, canon, memories, recentContext);
    }

    default Mono<LlmResponse> respond(TurnRequest request, RuntimeState state,
                                      List<String> canon, List<String> memories) {
        return respond(request, state, canon, memories, List.of());
    }

    /** Stream deterministic text deltas after structured output has validated. */
    default Flux<String> stream(TurnRequest request, RuntimeState state,
                                List<String> canon, List<String> memories,
                                List<String> recentContext) {
        return respond(request, state, canon, memories, recentContext)
                .flatMapMany(response -> Flux.fromIterable(TextChunks.of(response.getReply(), 18)));
    }

    default String providerName() {
        return getClass().getSimpleName();
    }
}

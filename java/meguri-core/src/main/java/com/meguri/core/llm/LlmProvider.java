package com.meguri.core.llm;

import com.meguri.core.dto.LlmResponse;
import com.meguri.core.dto.MemoryCandidate;
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
    /** Canonical 20.x boundary. Legacy implementations are adapted without losing typed inputs upstream. */
    default Mono<LlmResponse> respond(ProviderRequest request) {
        return respond(request.turn(), request.runtimeState(), request.legacyCanon(),
                request.legacyMemories(), request.legacyRecentContext(),
                request.legacyWebResults());
    }

    /**
     * Optional server-side semantic refinement for SLOW turns. An empty result
     * means the main model should answer with the already assembled evidence.
     */
    default Mono<AgentPlanningRequest.Decision> planAgent(
            AgentPlanningRequest request) {
        return Mono.empty();
    }

    Mono<LlmResponse> respond(TurnRequest request, RuntimeState state,
                              List<String> canon, List<String> memories,
                              List<String> recentContext);

    /** Optional bounded web context; legacy providers may ignore it. */
    default Mono<LlmResponse> respond(TurnRequest request, RuntimeState state,
                                      List<String> canon, List<String> memories,
                                      List<String> recentContext, List<String> webResults) {
        return respond(request, state, canon, memories, recentContext);
    }

    /** Generate a second valid answer for explicit human preference collection. */
    default Mono<LlmResponse> respondAlternative(TurnRequest request, RuntimeState state,
                                                  List<String> canon, List<String> memories,
                                                  List<String> recentContext, List<String> webResults) {
        return respond(request, state, canon, memories, recentContext, webResults);
    }

    default Mono<LlmResponse> respondAlternative(ProviderRequest request) {
        return respondAlternative(request.turn(), request.runtimeState(), request.legacyCanon(),
                request.legacyMemories(), request.legacyRecentContext(),
                request.legacyWebResults());
    }

    default Mono<LlmResponse> respond(TurnRequest request, RuntimeState state,
                                      List<String> canon, List<String> memories) {
        return respond(request, state, canon, memories, List.of());
    }

    /**
     * Structured providers expose one truthful complete delta. Implementations
     * with native provider streaming must override this method explicitly.
     */
    default Flux<String> stream(TurnRequest request, RuntimeState state,
                                List<String> canon, List<String> memories,
                                List<String> recentContext) {
        return respond(request, state, canon, memories, recentContext)
                .map(LlmResponse::getReply)
                .flux();
    }

    /** Native text channel. Web context is kept separate from local context. */
    default Flux<String> stream(TurnRequest request, RuntimeState state,
                                List<String> canon, List<String> memories,
                                List<String> recentContext, List<String> webResults) {
        return stream(request, state, canon, memories, recentContext);
    }

    default Flux<String> stream(ProviderRequest request) {
        return stream(request.turn(), request.runtimeState(), request.legacyCanon(),
                request.legacyMemories(), request.legacyRecentContext(),
                request.legacyWebResults());
    }

    /** True only when stream() emits provider tokens before a complete response exists. */
    default boolean supportsNativeStreaming() {
        return false;
    }

    /**
     * Non-blocking control-plane fallback after the text stream has completed.
     * Native providers may override this with a lightweight classifier.
     */
    default Mono<LlmResponse> finalizeStream(String reply, TurnRequest request, RuntimeState state) {
        return Mono.just(new LlmResponse(reply));
    }

    default Mono<LlmResponse> finalizeStream(String reply, ProviderRequest request) {
        return finalizeStream(reply, request.turn(), request.runtimeState());
    }

    default String providerName() {
        String name = getClass().getSimpleName();
        return name == null || name.isBlank() ? "anonymous-llm" : name;
    }

    default String modelId() {
        String name = providerName();
        return name == null || name.isBlank() ? "anonymous-llm" : name;
    }

    default ProviderTokenizer tokenizer() {
        return new DeterministicProviderTokenizer();
    }

    /** Optional offline-safe hook for bounded session-level candidate extraction. */
    default Mono<List<MemoryCandidate>> extractMemoryCandidates(List<String> sessionMessages) {
        return Mono.just(List.of());
    }
}

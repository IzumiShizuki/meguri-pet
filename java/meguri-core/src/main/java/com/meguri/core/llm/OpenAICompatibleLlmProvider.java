package com.meguri.core.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.dto.LlmResponse;
import com.meguri.core.dto.RuntimeState;
import com.meguri.core.dto.TurnRequest;
import dev.langchain4j.model.chat.ChatModel;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Compatibility facade retaining the Python provider's descriptive name while
 * delegating all transport and structured-output work to LangChain4j.
 */
public final class OpenAICompatibleLlmProvider implements LlmProvider {
    private final LangChain4jLlmProvider delegate;

    public OpenAICompatibleLlmProvider(ChatModel model, ObjectMapper mapper, String systemPrompt,
                                       String responseFormat, int maxConcurrency,
                                       Map<String, String> expectedReleaseHeaders) {
        this.delegate = new LangChain4jLlmProvider(model, mapper, systemPrompt,
                responseFormat, maxConcurrency, expectedReleaseHeaders);
    }

    public OpenAICompatibleLlmProvider(ChatModel model, ObjectMapper mapper, String systemPrompt) {
        this.delegate = new LangChain4jLlmProvider(model, mapper, systemPrompt);
    }

    @Override
    public Mono<LlmResponse> respond(TurnRequest request, RuntimeState state, List<String> canon,
                                     List<String> memories, List<String> recentContext) {
        return delegate.respond(request, state, canon, memories, recentContext);
    }

    @Override
    public Mono<LlmResponse> respond(TurnRequest request, RuntimeState state, List<String> canon,
                                     List<String> memories, List<String> recentContext,
                                     List<String> webResults) {
        return delegate.respond(request, state, canon, memories, recentContext, webResults);
    }

    @Override
    public Flux<String> stream(TurnRequest request, RuntimeState state, List<String> canon,
                               List<String> memories, List<String> recentContext) {
        return delegate.stream(request, state, canon, memories, recentContext);
    }

    public void validateReleaseHeaders(Map<String, String> responseHeaders) {
        delegate.validateReleaseHeaders(responseHeaders);
    }

    @Override
    public String providerName() {
        return "openai-compatible/langchain4j";
    }
}

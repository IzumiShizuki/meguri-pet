package com.meguri.core.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.dto.ExpressionTag;
import com.meguri.core.dto.Intensity;
import com.meguri.core.dto.Mode;
import com.meguri.core.dto.Relationship;
import com.meguri.core.dto.RuntimeState;
import com.meguri.core.dto.TurnRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LangChain4jLlmProviderTest {
    private final TurnRequest request = new TurnRequest("u", "airi", "s", "hello");
    private final RuntimeState state = new RuntimeState("airi", Mode.WORK, Relationship.SIBLING, "01",
            "2026-07-18T10:00:00+08:00", false, false, false, List.of(ExpressionTag.NEUTRAL));

    @Test
    void usesLangChainStructuredRequestAndStrictlyMapsResponse() {
        AtomicReference<ChatRequest> captured = new AtomicReference<>();
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest chatRequest) {
                captured.set(chatRequest);
                return ChatResponse.builder().aiMessage(AiMessage.from("""
                        {"reply":"hello back","expression_tag":"neutral","expression_intensity":"low",
                         "voice_style":"neutral","memory_candidates":[]}
                        """)).build();
            }
        };
        LangChain4jLlmProvider provider = new LangChain4jLlmProvider(model, new ObjectMapper(), "system");
        StepVerifier.create(provider.respond(request, state, List.of("canon"), List.of(), List.of()))
                .assertNext(response -> assertEquals("hello back", response.getReply()))
                .verifyComplete();
        assertNotNull(captured.get());
        assertNotNull(captured.get().responseFormat());
        assertTrue(captured.get().messages().getFirst().toString().contains("system"));
    }

    @Test
    void invalidStructuredOutputIsSanitized() {
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest ignored) {
                return ChatResponse.builder().aiMessage(AiMessage.from("{\"reply\":\"ok\",\"extra\":true}"))
                        .build();
            }
        };
        LangChain4jLlmProvider provider = new LangChain4jLlmProvider(model, new ObjectMapper(), "system");
        StepVerifier.create(provider.respond(request, state, List.of(), List.of(), List.of()))
                .expectErrorSatisfies(error -> assertEquals("LLM provider returned an invalid Meguri response", error.getMessage()))
                .verify();
    }

    @Test
    void jsonObjectModeSerializesThePromptSchemaAsPlainJson() {
        AtomicReference<ChatRequest> captured = new AtomicReference<>();
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest chatRequest) {
                captured.set(chatRequest);
                return ChatResponse.builder().aiMessage(AiMessage.from("""
                        {"reply":"ok","expression_tag":"neutral","expression_intensity":"low",
                         "voice_style":"neutral","memory_candidates":[]}
                        """)).build();
            }
        };
        LangChain4jLlmProvider provider = new LangChain4jLlmProvider(
                model, new ObjectMapper(), "return json", "json_object", 1, java.util.Map.of());

        StepVerifier.create(provider.respond(request, state, List.of("canon"), List.of(), List.of()))
                .assertNext(response -> assertEquals("ok", response.getReply()))
                .verifyComplete();
        assertNotNull(captured.get());
        assertTrue(captured.get().messages().get(1).toString().contains("required_output_schema"));
    }

    @Test
    void releaseHeaderValidationFailsClosed() {
        ChatModel model = new ChatModel() { };
        LangChain4jLlmProvider provider = new LangChain4jLlmProvider(
                model, new ObjectMapper(), "system", "json_schema", 1,
                java.util.Map.of("X-Meguri-Model-Id", "v1"));
        assertThrows(LlmProviderException.class, () -> provider.validateReleaseHeaders(java.util.Map.of()));
    }
}

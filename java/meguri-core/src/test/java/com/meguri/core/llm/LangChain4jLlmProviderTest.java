package com.meguri.core.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.dto.ExpressionTag;
import com.meguri.core.dto.Intensity;
import com.meguri.core.dto.MemorySourceScope;
import com.meguri.core.dto.Mode;
import com.meguri.core.dto.Relationship;
import com.meguri.core.dto.RuntimeState;
import com.meguri.core.dto.TurnRequest;
import com.meguri.core.metrics.PromptCacheMetricsRecorder;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
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
    void oversizedMandatoryInputFailsByTokenBudgetWithoutCallingTheModel() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest ignored) {
                calls.incrementAndGet();
                throw new AssertionError("model must not receive an over-budget prompt");
            }
        };
        LangChain4jLlmProvider provider = new LangChain4jLlmProvider(
                model, new ObjectMapper(), "system", "json_schema", 1, java.util.Map.of(),
                PromptCacheMetricsRecorder.noop(), new OpenAiProviderTokenizer("gpt-4o-mini"), 256);
        TurnRequest oversized = new TurnRequest(
                "u", "airi", "s", "important user input ".repeat(500));

        StepVerifier.create(provider.respond(oversized, state, List.of(), List.of(), List.of()))
                .expectErrorMatches(error -> error instanceof LlmProviderException
                        && error.getMessage().contains("mandatory prompt context"))
                .verify();
        assertEquals(0, calls.get());
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
        assertTrue(captured.get().messages().get(1).toString().contains("required_output_example"));
    }

    @Test
    void bilingualRequestCarriesJapaneseFirstCompositionContract() throws Exception {
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
        TurnRequest bilingual = new ObjectMapper().readValue("""
                {"user_id":"u","client_id":"astrbot","session_id":"s","message":"hello",
                 "reply_format":"zh_ja_pairs"}
                """, TurnRequest.class);
        LangChain4jLlmProvider provider = new LangChain4jLlmProvider(model, new ObjectMapper(), "system");

        StepVerifier.create(provider.respond(bilingual, state, List.of(), List.of(), List.of()))
                .expectNextCount(1)
                .verifyComplete();

        String prompt = captured.get().messages().get(1).toString();
        assertTrue(prompt.contains("zh_ja_pairs"));
        assertTrue(prompt.contains("\u5144\u3055\u3093"));
        assertTrue(prompt.contains("never \u304a\u5144\u3061\u3083\u3093"));
    }

    @Test
    void repeatedUserMessageCarriesConversationDiversitySignal() {
        AtomicReference<ChatRequest> captured = new AtomicReference<>();
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest chatRequest) {
                captured.set(chatRequest);
                return ChatResponse.builder().aiMessage(AiMessage.from("""
                        {"reply":"new angle","expression_tag":"neutral","expression_intensity":"low",
                         "voice_style":"neutral","memory_candidates":[]}
                        """)).build();
            }
        };
        TurnRequest repeated = new TurnRequest("u", "airi", "s", "\u665a\u4e0a\u597d");
        LangChain4jLlmProvider provider = new LangChain4jLlmProvider(model, new ObjectMapper(), "system");

        StepVerifier.create(provider.respond(repeated, state, List.of(), List.of(), List.of(
                        "user: \u665a\u4e0a\u597d",
                        "assistant: \u665a\u4e0a\u597d\uff0c\u8be5\u4f11\u606f\u4e86\u3002",
                        "user: \u665a\u4e0a\u597d",
                        "assistant: \u95ed\u4e0a\u773c\u775b\u7761\u5427\u3002")))
                .expectNextCount(1)
                .verifyComplete();

        String prompt = captured.get().messages().get(1).toString();
        assertTrue(prompt.contains("consecutive_identical_user_message_count=3"));
        assertTrue(prompt.contains("\u95ed\u4e0a\u773c\u775b\u7761\u5427\u3002"));
        assertTrue(prompt.contains("Do not repeat or merely paraphrase the previous assistant answer"));
    }

    @Test
    void releaseHeaderValidationFailsClosed() {
        ChatModel model = new ChatModel() { };
        LangChain4jLlmProvider provider = new LangChain4jLlmProvider(
                model, new ObjectMapper(), "system", "json_schema", 1,
                java.util.Map.of("X-Meguri-Model-Id", "v1"));
        assertThrows(LlmProviderException.class, () -> provider.validateReleaseHeaders(java.util.Map.of()));
    }

    @Test
    void extractsBoundedConversationCandidatesWithConversationProvenance() {
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest ignored) {
                return ChatResponse.builder().aiMessage(AiMessage.from("""
                        {"memory_candidates":[{"type":"project","summary":"正在维护 Meguri","confidence":0.9,"sensitivity":"normal","source_scope":"conversation"}]}
                        """)).build();
            }
        };
        LangChain4jLlmProvider provider = new LangChain4jLlmProvider(model, new ObjectMapper(), "system");
        StepVerifier.create(provider.extractMemoryCandidates(List.of("user: 我在维护 Meguri", "assistant: 好")))
                .assertNext(values -> {
                    assertEquals(1, values.size());
                    assertEquals(MemorySourceScope.CONVERSATION, values.getFirst().sourceScope());
                    assertEquals("正在维护 Meguri", values.getFirst().summary());
                })
                .verifyComplete();
    }

    @Test
    void alternativeCandidateCarriesAnExplicitDiversityInstruction() {
        AtomicReference<ChatRequest> captured = new AtomicReference<>();
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest chatRequest) {
                captured.set(chatRequest);
                return ChatResponse.builder().aiMessage(AiMessage.from("""
                        {"reply":"different answer","expression_tag":"neutral","expression_intensity":"low",
                         "voice_style":"restrained","memory_candidates":[]}
                        """)).build();
            }
        };
        LangChain4jLlmProvider provider = new LangChain4jLlmProvider(model, new ObjectMapper(), "system");

        StepVerifier.create(provider.respondAlternative(request, state, List.of(), List.of(), List.of(), List.of()))
                .assertNext(response -> assertEquals("different answer", response.reply()))
                .verifyComplete();

        assertTrue(captured.get().messages().get(1).toString().contains("preference_sampling"));
    }
}

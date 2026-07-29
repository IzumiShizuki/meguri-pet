package com.meguri.core.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.context.ContextBundle;
import com.meguri.core.dto.ExpressionTag;
import com.meguri.core.dto.Intensity;
import com.meguri.core.dto.MemorySourceScope;
import com.meguri.core.dto.Mode;
import com.meguri.core.dto.Relationship;
import com.meguri.core.dto.RuntimeState;
import com.meguri.core.dto.TurnRequest;
import com.meguri.core.metrics.PromptCacheMetricsRecorder;
import com.meguri.core.persona.PersonaRuntimeDefaults;
import com.meguri.core.persona.prompt.PromptPolicyComposer;
import com.meguri.core.persona.runtime.AllowedBehaviorEnvelope;
import com.meguri.core.persona.runtime.ClientCapabilityState;
import com.meguri.core.persona.runtime.EffectivePersonaState;
import com.meguri.core.persona.runtime.InteractionState;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
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

    @Test
    void typedRequestSerializesEveryCanonicalSegmentExactlyOnce() {
        AtomicReference<ChatRequest> captured = new AtomicReference<>();
        ChatModel model = successfulModel(captured);
        LangChain4jLlmProvider provider = new LangChain4jLlmProvider(
                model, new ObjectMapper(), "BASE_SYSTEM_UNIQUE");
        ProviderRequest typed = typedRequest(
                "USER_MESSAGE_UNIQUE", "CONTEXT_BLOCK_UNIQUE",
                "TRUSTED_PROMPT_SKILL_UNIQUE", "MCP_EXTERNAL_UNIQUE",
                "trace-a", Instant.parse("2026-07-29T00:00:00Z"));

        StepVerifier.create(provider.respond(typed)).expectNextCount(1).verifyComplete();

        String wirePrompt = captured.get().messages().toString();
        assertEquals(1, occurrences(wirePrompt, "USER_MESSAGE_UNIQUE"));
        assertEquals(1, occurrences(wirePrompt, "CONTEXT_BLOCK_UNIQUE"));
        assertEquals(1, occurrences(wirePrompt, "TRUSTED_PROMPT_SKILL_UNIQUE"));
        assertEquals(1, occurrences(wirePrompt, "MCP_EXTERNAL_UNIQUE"));
        assertEquals(1, occurrences(wirePrompt, "Core traits:"));
        assertEquals(1, occurrences(wirePrompt, "context_blocks"));
        assertTrue(!wirePrompt.contains("\"effective_persona\":"));
        assertTrue(!wirePrompt.contains("\"user_message\":"));
        assertTrue(!wirePrompt.contains("\"context_bundle\":"));
    }

    @Test
    void canonicalPromptDigestIgnoresVolatileIdsButChangesWithSemanticContent() {
        ProviderRequest first = typedRequest(
                "same user", "same context", "same skill", "same external",
                "trace-a", Instant.parse("2026-07-29T00:00:00Z"));
        ProviderRequest sameContent = typedRequest(
                "same user", "same context", "same skill", "same external",
                "trace-b", Instant.parse("2026-07-30T00:00:00Z"));
        ProviderRequest changed = typedRequest(
                "same user", "changed context", "same skill", "same external",
                "trace-c", Instant.parse("2026-07-31T00:00:00Z"));

        assertEquals(first.canonicalPromptDigest(), sameContent.canonicalPromptDigest());
        assertTrue(!first.canonicalPromptDigest().equals(changed.canonicalPromptDigest()));
    }

    @Test
    void oversizedTypedCanonicalContextFailsWithoutCallingTheModel() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest ignored) {
                calls.incrementAndGet();
                throw new AssertionError("model must not receive an over-budget typed prompt");
            }
        };
        LangChain4jLlmProvider provider = new LangChain4jLlmProvider(
                model, new ObjectMapper(), "system", "json_schema", 1, Map.of(),
                PromptCacheMetricsRecorder.noop(),
                new OpenAiProviderTokenizer("gpt-4o-mini"), 256);
        ProviderRequest oversized = typedRequest(
                "user", "mandatory canonical context ".repeat(500),
                "trusted skill", "external", "trace",
                Instant.parse("2026-07-29T00:00:00Z"));

        StepVerifier.create(provider.respond(oversized))
                .expectErrorMatches(error -> error instanceof LlmProviderException
                        && error.getMessage().contains("mandatory prompt context"))
                .verify();
        assertEquals(0, calls.get());
    }

    private static ChatModel successfulModel(AtomicReference<ChatRequest> captured) {
        return new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest chatRequest) {
                captured.set(chatRequest);
                return ChatResponse.builder().aiMessage(AiMessage.from("""
                        {"reply":"ok","expression_tag":"neutral","expression_intensity":"low",
                         "voice_style":"neutral","memory_candidates":[]}
                        """)).build();
            }
        };
    }

    private static ProviderRequest typedRequest(
            String userMessage,
            String contextContent,
            String trustedSkill,
            String externalContent,
            String traceId,
            Instant deadline) {
        TurnRequest turn = new TurnRequest("u", "airi", "s", userMessage);
        RuntimeState runtime = new RuntimeState(
                "airi", Mode.WORK, Relationship.SIBLING, "01",
                "2026-07-29T08:00:00+08:00", false, false, false,
                List.of(ExpressionTag.NEUTRAL));
        EffectivePersonaState persona = new EffectivePersonaState(
                PersonaRuntimeDefaults.meguriProfile(),
                PersonaRuntimeDefaults.defaultRelationship("u"),
                PersonaRuntimeDefaults.defaultScene("s"),
                new InteractionState("conversation", InteractionState.Urgency.NORMAL,
                        Set.of(), null, Set.of()),
                Mode.WORK,
                new AllowedBehaviorEnvelope(Set.of("help"), Set.of("override"),
                        Set.of("neutral"), Set.of("neutral")),
                new ClientCapabilityState("airi", false, true, false, false, false),
                "policy-v1", "resolution-trace", List.of());
        ContextBundle context = new ContextBundle(
                "s", "m2", "topic", List.of(
                        new ContextBundle.Block(ContextBundle.BlockType.RECENT_RAW,
                                List.of("m1"), ContextBundle.Trust.USER, 4,
                                "user: " + userMessage),
                        new ContextBundle.Block(ContextBundle.BlockType.SUMMARY,
                                List.of("summary-1"), ContextBundle.Trust.SYSTEM, 4,
                                contextContent)),
                new ContextBundle.Budget(1_000, 700, 900, 8, "test", Map.of()),
                List.of(), "context-v1");
        List<PromptPolicyComposer.PromptBlock> blocks = List.of(
                new PromptPolicyComposer.PromptBlock(
                        PromptPolicyComposer.Role.SYSTEM, PromptPolicyComposer.Source.PERSONA,
                        PromptPolicyComposer.Trust.TRUSTED, "persona:meguri", "persona-v1",
                        "Core traits: warm"),
                new PromptPolicyComposer.PromptBlock(
                        PromptPolicyComposer.Role.DEVELOPER, PromptPolicyComposer.Source.RUNTIME,
                        PromptPolicyComposer.Trust.TRUSTED, "runtime", "policy-v1",
                        "Effective mode=WORK"),
                new PromptPolicyComposer.PromptBlock(
                        PromptPolicyComposer.Role.USER_DATA, PromptPolicyComposer.Source.CONTEXT,
                        PromptPolicyComposer.Trust.USER, "m1", "context-v1",
                        "<user-context>\nuser: " + userMessage + "\n</user-context>"),
                new PromptPolicyComposer.PromptBlock(
                        PromptPolicyComposer.Role.USER_DATA, PromptPolicyComposer.Source.CONTEXT,
                        PromptPolicyComposer.Trust.REVIEWED, "summary-1", "context-v1",
                        "<reviewed-context>\n" + contextContent + "\n</reviewed-context>"),
                new PromptPolicyComposer.PromptBlock(
                        PromptPolicyComposer.Role.DEVELOPER, PromptPolicyComposer.Source.PROMPT_SKILL,
                        PromptPolicyComposer.Trust.TRUSTED, "skill:local", "skill-v1", trustedSkill),
                new PromptPolicyComposer.PromptBlock(
                        PromptPolicyComposer.Role.USER_DATA, PromptPolicyComposer.Source.PROMPT_SKILL,
                        PromptPolicyComposer.Trust.UNTRUSTED, "mcp:remote", "mcp-v1",
                        "<untrusted-data>\n" + externalContent + "\n</untrusted-data>"));
        return new ProviderRequest(
                turn, runtime, persona, context, blocks,
                "knowledge-" + traceId, "retrieval-" + traceId,
                "context-" + traceId, "capabilities-" + traceId,
                List.of(new ProviderRequest.CapabilityRef("tool", "v1")),
                deadline, traceId);
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        for (int offset = 0; (offset = value.indexOf(needle, offset)) >= 0;
             offset += needle.length()) {
            count++;
        }
        return count;
    }
}

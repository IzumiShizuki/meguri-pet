package com.meguri.core.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.capability.CapabilityRuntimeFacade;
import com.meguri.core.context.*;
import com.meguri.core.dto.*;
import com.meguri.core.harness.EventCursor;
import com.meguri.core.harness.retrieval.RetrievalMode;
import com.meguri.core.llm.DeterministicProviderTokenizer;
import com.meguri.core.llm.LangChain4jLlmProvider;
import com.meguri.core.memory.*;
import com.meguri.core.memory.job.InMemoryPostReplyMemoryJobStore;
import com.meguri.core.memory.job.PostReplyMemoryJobEnqueuer;
import com.meguri.core.metrics.PromptCacheMetricsRecorder;
import com.meguri.core.persona.PersonaRuntimeDefaults;
import com.meguri.core.persona.presentation.PresentationResolver;
import com.meguri.core.persona.prompt.PromptPolicyComposer;
import com.meguri.core.rag.RagProvider;
import com.meguri.core.retrieval.SourceType;
import com.meguri.core.retrieval.UnifiedRetrievalFacade;
import com.meguri.core.training.TrainingFeedbackService;
import com.meguri.core.weather.WeatherConversationService;
import com.meguri.core.websearch.WebSearchGateway;
import com.meguri.core.websearch.WebSearchRecall;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** No sockets, real credentials, database, model inference or memory writer are used. */
class PersonaRecallIsolationIntegrationTest {
    private static final String OWNER = "fictional-persona-acceptance-owner";
    private static final String MEMORY = "虚构测试员的纸舟项目使用青绿色卡纸。";
    private static final String LORE = "爱莉: 先慢慢说，我陪你把这一小步理清。";

    @Test
    void approvedFixtureRecallReachesNewSessionModelRequestButNotAnotherIdentityOrDisabledConsent() throws Exception {
        try (Fixture fixture = new Fixture()) {
            TurnRecord first = fixture.run(OWNER, "fictional-session-one", true);
            assertThat(first.getProviderRequest().legacyMemories())
                    .anySatisfy(value -> assertThat(value).contains(MEMORY));
            fixture.run(OWNER, "fictional-session-two", true);
            ChatRequest request = fixture.lastModelRequest.get();
            String system = ((SystemMessage) request.messages().getFirst()).text();
            String user = ((UserMessage) request.messages().get(1)).singleText();
            assertThat(system).contains("Meguri", "Speech style: {language=follow the user, tone=gentle and direct}",
                    "style-only dialogue examples", "relationship=sibling");
            assertThat(system).doesNotContain(MEMORY, LORE);
            assertThat(user).contains(MEMORY, LORE, "<canon-examples>", "<untrusted-data>");
            var payload = fixture.mapper.readTree(user);
            assertThat(payload.has("canon_examples")).isFalse();
            assertThat(payload.has("long_term_memories")).isFalse();
            assertThat(payload.get("context_blocks")).anySatisfy(block -> {
                assertThat(block.path("source").asText()).isEqualTo("MEMORY");
                assertThat(block.path("content").asText()).contains(MEMORY);
            });
            // A fresh conversation has only its own current input, not session one's transcript.
            assertThat(user).doesNotContain("session-one input");
            assertThat(fixture.memory.recalledSessions).contains("fictional-session-one", "fictional-session-two");

            int callsBeforeDisabled = fixture.memory.recallCalls.get();
            TurnRecord disabled = fixture.run(OWNER, "fictional-disabled", false);
            assertThat(fixture.memory.recallCalls).hasValue(callsBeforeDisabled);
            // Planning may retain an empty diagnostic lane; consent forbids the
            // backend call and memory evidence, not the lane's metadata entry.
            assertThat(disabled.getRetrievalBundle().items())
                    .noneMatch(item -> item.sourceType() == SourceType.MEMORY);
            assertThat(disabled.getProviderRequest().legacyMemories()).isEmpty();
            assertThat(((UserMessage) fixture.lastModelRequest.get().messages().get(1)).singleText())
                    .doesNotContain(MEMORY).contains(LORE);

            fixture.run("fictional-other-owner", "fictional-other-session", true);
            assertThat(((UserMessage) fixture.lastModelRequest.get().messages().get(1)).singleText())
                    .doesNotContain(MEMORY);
            assertThat(fixture.memory.writeCalls).hasValue(0);
        }
    }

    @Test
    void unavailableMemoryIsObservableAndNextTurnRecoversWithoutInventedMemory() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.memory.unavailable = true;
            TurnRecord failedRecall = fixture.run(OWNER, "fictional-memory-down", true);
            assertThat(failedRecall.getProviderRequest().legacyMemories()).isEmpty();
            assertThat(((UserMessage) fixture.lastModelRequest.get().messages().get(1)).singleText())
                    .doesNotContain(MEMORY);
            assertThat(fixture.runtime.events(new EventCursor("fictional-memory-down", 0L))
                    .collectList().block()).anySatisfy(event -> {
                        assertThat(event.getType()).isEqualTo("retrieval.completed");
                        assertThat(String.valueOf(event.getData().get("lanes"))).contains("memory_unavailable");
                    });
            fixture.memory.unavailable = false;
            TurnRecord recovered = fixture.run(OWNER, "fictional-memory-recovered", true);
            assertThat(recovered.getProviderRequest().legacyMemories())
                    .anySatisfy(value -> assertThat(value).contains(MEMORY));
            assertThat(fixture.memory.writeCalls).hasValue(0);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        final AtomicReference<ChatRequest> lastModelRequest = new AtomicReference<>();
        final ReadOnlyFictionalMemory memory = new ReadOnlyFictionalMemory();
        final CapabilityRuntimeFacade capabilities = new CapabilityRuntimeFacade(32);
        final UnifiedRetrievalFacade retrieval;
        final TurnOrchestrator runtime;

        Fixture() throws Exception {
            ChatModel model = new ChatModel() {
                @Override public ChatResponse doChat(ChatRequest request) {
                    lastModelRequest.set(request);
                    return ChatResponse.builder().aiMessage(AiMessage.from("""
                            {"reply":"隔离模型桩已收到上下文。","expression_tag":"neutral",
                             "expression_intensity":"low","voice_style":"neutral","memory_candidates":[]}
                            """)).build();
                }
            };
            var tokenizer = new DeterministicProviderTokenizer();
            var provider = new LangChain4jLlmProvider(model, mapper,
                    Files.readString(Path.of("../../configs/meguri_system_prompt.txt")),
                    "json_schema", 1, java.util.Map.of(), PromptCacheMetricsRecorder.noop(), tokenizer, 20_000);
            RagProvider lore = (query, state, limit) -> List.of(LORE);
            WebSearchGateway web = (query, limit) -> Mono.just(WebSearchRecall.disabled());
            retrieval = UnifiedRetrievalFacade.compatibility(lore, memory, web);
            runtime = new TurnOrchestrator(provider, lore, new RuntimeStateMachine(), new ExpressionResolver(),
                    Duration.ofMillis(1), mapper, memory, web, TrainingFeedbackService.disabled(mapper),
                    WeatherConversationService.disabled(), new InMemoryTurnJournal(mapper),
                    new NoopSessionContextPersistence(), capabilities);
            SessionContextStore sessions = new SessionContextStore(20, new NoopSessionContextPersistence());
            var contexts = new CompanionContextRuntime(sessions, new InMemoryContextRuntimePersistence(), tokenizer);
            var budgets = new EnumMap<ContextBundle.BlockType, ContextProfile.SourceBudget>(ContextBundle.BlockType.class);
            for (var type : ContextBundle.BlockType.values()) budgets.put(type, new ContextProfile.SourceBudget(0, 4000));
            runtime.configureCanonicalPipeline(sessions, PersonaRuntimeDefaults.facade(Clock.systemUTC()), retrieval,
                    contexts, new PromptPolicyComposer(), new ContextProfile("isolation", 16000, 1000, 500, .7, .9, budgets),
                    new PresentationResolver(), new PostReplyMemoryJobEnqueuer(
                            new InMemoryPostReplyMemoryJobStore(), mapper, Clock.systemUTC()));
        }

        TurnRecord run(String user, String session, boolean allowed) {
            TurnRequest request = new TurnRequest(user, "website", session,
                    session + " input: 请用平静的语气陪我聊纸舟项目。", RetrievalMode.FAST)
                    .withFormalMemoryAllowed(allowed);
            TurnRecord record = runtime.start(request);
            record.getDone().join();
            assertThat(record.getStatus()).isEqualTo(TurnStatus.COMPLETED);
            return record;
        }

        @Override public void close() {
            runtime.reset();
            retrieval.close();
            capabilities.close();
        }
    }

    /** One pre-approved fictional record; this fixture never learns from conversation input. */
    private static final class ReadOnlyFictionalMemory implements MemoryGateway {
        final AtomicInteger recallCalls = new AtomicInteger();
        final AtomicInteger writeCalls = new AtomicInteger();
        final List<String> recalledSessions = new CopyOnWriteArrayList<>();
        volatile boolean unavailable;
        @Override public Mono<MemoryRecall> recall(TurnRequest request) {
            recallCalls.incrementAndGet();
            recalledSessions.add(request.getSessionId());
            return Mono.just(unavailable ? MemoryRecall.unavailable()
                    : MemoryRecall.available(OWNER.equals(request.getUserId()) ? List.of(MEMORY) : List.of()));
        }
        @Override public Mono<List<MemoryCandidate>> extract(TurnRequest request) {
            return Mono.error(new AssertionError("isolated fixture forbids memory extraction"));
        }
        @Override public Mono<MemoryWriteResult> write(TurnRequest request, LlmResponse response, String turn, String trace) {
            writeCalls.incrementAndGet();
            return Mono.error(new AssertionError("isolated fixture forbids memory writes"));
        }
        @Override public Mono<SessionSummaryResult> summarize(SessionSummaryRequest request) {
            return Mono.error(new AssertionError("isolated fixture forbids memory summaries"));
        }
    }
}

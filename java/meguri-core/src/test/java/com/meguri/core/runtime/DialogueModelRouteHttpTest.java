package com.meguri.core.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.capability.CapabilityRuntimeFacade;
import com.meguri.core.dto.TurnRequest;
import com.meguri.core.harness.retrieval.RetrievalMode;
import com.meguri.core.llm.AgentPlannerException;
import com.meguri.core.llm.AgentPlannerRoute;
import com.meguri.core.llm.AgentPlanningRequest;
import com.meguri.core.llm.LangChain4jLlmProvider;
import com.meguri.core.llm.OpenAiProviderTokenizer;
import com.meguri.core.metrics.PromptCacheMetricsRecorder;
import com.meguri.core.memory.NoopMemoryGateway;
import com.meguri.core.training.TrainingFeedbackService;
import com.meguri.core.weather.WeatherConversationService;
import com.meguri.core.websearch.NoopWebSearchGateway;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.model.openai.OpenAiChatModel;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real HTTP provider boundary, followed by real Core multi-turn context assembly. */
class DialogueModelRouteHttpTest {
    @Test
    void distinctPlannerRouteAndAnswerRouteSurvivePlannerFailureAndNextTurn() throws Exception {
        try (Fixture fixture = new Fixture()) {
            TurnRecord first = fixture.turn("same-session", "FIRST_SESSION_MARKER");
            assertThat(first.getStatus()).isEqualTo(TurnStatus.COMPLETED);
            fixture.failPlanner.set(true);
            assertThatThrownBy(() -> fixture.provider.planAgent(planning(first)).block(Duration.ofSeconds(5)))
                    .isInstanceOf(AgentPlannerException.class);
            TurnRecord next = fixture.turn("same-session", "follow up please");
            assertThat(next.getStatus()).isEqualTo(TurnStatus.COMPLETED);
            assertThat(fixture.provider.planAgent(planning(next)).block(Duration.ofSeconds(5))).isNull();

            assertThat(fixture.calls).extracting(call -> call.path("model").asText())
                    .containsExactly("dialogue-answer", "dialogue-planner", "dialogue-answer", "dialogue-planner");
            assertThat(fixture.calls.get(2).path("messages").toString())
                    .contains("FIRST_SESSION_MARKER", "answer-1");
            assertThat(fixture.calls.get(1).path("max_tokens").asInt()).isEqualTo(128);
            assertThat(fixture.calls.get(0).path("max_tokens").asInt()).isEqualTo(1200);
        }
    }

    @Test
    void independentSessionDoesNotReceivePreviousSessionsRawHistory() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.turn("session-a", "PRIVATE_CONTEXT_MARKER");
            fixture.turn("session-a", "continue this discussion");
            fixture.turn("session-b", "start fresh discussion");
            assertThat(fixture.calls.get(1).path("messages").toString()).contains("PRIVATE_CONTEXT_MARKER");
            assertThat(fixture.calls.get(2).path("messages").toString()).doesNotContain("PRIVATE_CONTEXT_MARKER", "answer-1");
        }
    }

    @Test
    void inputSmallModelPrecedesMainFlowAndFailureFallsBackAcrossTurns() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.core.configureInputModelChecker(new com.meguri.core.input.InputModelChecker(
                    new com.meguri.core.input.InputModelChecker.Config(true, fixture.base, "dialogue-input-small",
                            "fixture-only", Duration.ofSeconds(2), Duration.ofMillis(100), 1, 8192),
                    fixture.mapper, java.net.http.HttpClient.newHttpClient(), java.time.Clock.systemUTC()));
            String raw = "讲讲 LangChain4j";
            TurnRecord first = fixture.turn("input-session", raw);
            assertThat(first.getRequest().getMessage()).isEqualTo(raw);
            assertThat(first.getProviderRequest().checkedInputText()).isEqualTo("请介绍 LangChain4j。");
            assertThat(fixture.calls.get(1).path("messages").toString()).contains("input_assistance", raw, "请介绍 LangChain4j。");
            fixture.failInput.set(true);
            TurnRecord failedCheck = fixture.turn("input-session", raw);
            assertThat(failedCheck.getProviderRequest().checkedInputText()).isNull();
            TurnRecord recovered = fixture.turn("input-session", raw);
            assertThat(recovered.getProviderRequest().checkedInputText()).isEqualTo("请介绍 LangChain4j。");
            assertThat(fixture.calls).extracting(call -> call.path("model").asText()).containsExactly(
                    "dialogue-input-small", "dialogue-answer", "dialogue-input-small", "dialogue-answer", "dialogue-input-small", "dialogue-answer");
            assertThat(recovered.getProviderRequest().legacyRecentContext().toString()).doesNotContain("请介绍 LangChain4j。");
        }
    }

    @Test
    void checkedInputRagAndMainModelStayConnectedThroughDegradationAndEmptyResults() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.core.configureInputModelChecker(new com.meguri.core.input.InputModelChecker(
                    new com.meguri.core.input.InputModelChecker.Config(true, fixture.base, "dialogue-input-small",
                            "fixture-only", Duration.ofSeconds(2), Duration.ofMillis(100), 1, 8192),
                    fixture.mapper, java.net.http.HttpClient.newHttpClient(), java.time.Clock.systemUTC()));
            String raw = "讲讲 LangChain4j";
            TurnRecord first = fixture.turn("rag-session", raw, RetrievalMode.FAST);
            assertThat(first.getProviderRequest().checkedInputText()).isEqualTo("请介绍 LangChain4j。");
            assertThat(first.getProviderRequest().legacyCanon()).anyMatch(text -> text.contains("remote-rag-result-1"));
            assertThat(fixture.calls.get(1).path("messages").toString()).contains("remote-rag-result-1", "input_assistance");
            fixture.failRag.set(true);
            TurnRecord degraded = fixture.turn("rag-session", raw, RetrievalMode.FAST);
            assertThat(degraded.getRetrievalBundle().degradations()).contains("rag_bridge_unavailable");
            assertThat(degraded.getProviderRequest().legacyCanon()).anyMatch(text -> text.contains("local-rag-fallback"));
            assertThat(fixture.calls.get(3).path("messages").toString()).contains("local-rag-fallback").doesNotContain("remote-rag-result-1");
            fixture.emptyRag.set(true);
            TurnRecord empty = fixture.turn("rag-session", raw, RetrievalMode.FAST);
            assertThat(empty.getProviderRequest().legacyCanon()).isEmpty();
            assertThat(empty.getRetrievalBundle().degradations()).doesNotContain("rag_bridge_unavailable");
            TurnRecord recovered = fixture.turn("new-rag-session", raw, RetrievalMode.FAST);
            assertThat(recovered.getProviderRequest().legacyCanon()).anyMatch(text -> text.contains("remote-rag-result-4"));
            assertThat(fixture.ragQueries).containsExactly(raw, raw, raw, raw);
            assertThat(recovered.getProviderRequest().legacyRecentContext()).noneMatch(text -> text.contains("answer-2"));
        }
    }

    private static AgentPlanningRequest planning(TurnRecord record) {
        return new AgentPlanningRequest(record.getProviderRequest(), List.of(
                new AgentPlanningRequest.AgentCandidate("local-agent", java.util.Set.of("agent.invoke"), "result-v1")), "AGENT");
    }

    private static final class Fixture implements AutoCloseable {
        private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        private final HttpServer server;
        private final java.util.concurrent.ExecutorService httpThreads = Executors.newVirtualThreadPerTaskExecutor();
        private final List<JsonNode> calls = new CopyOnWriteArrayList<>();
        private final AtomicBoolean failPlanner = new AtomicBoolean();
        private final AtomicBoolean failInput = new AtomicBoolean();
        private final AtomicBoolean failRag = new AtomicBoolean();
        private final AtomicBoolean emptyRag = new AtomicBoolean();
        private final List<String> ragQueries = new CopyOnWriteArrayList<>();
        private final String base;
        private final LangChain4jLlmProvider provider;
        private final TurnOrchestrator core;

        Fixture() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(httpThreads);
            server.createContext("/v1/chat/completions", exchange -> {
                try {
                    JsonNode body = mapper.readTree(exchange.getRequestBody());
                    calls.add(body);
                    boolean planner = body.path("model").asText().equals("dialogue-planner");
                    boolean input = body.path("model").asText().equals("dialogue-input-small");
                    int status = planner && failPlanner.getAndSet(false) || input && failInput.getAndSet(false) ? 503 : 200;
                    String text = input ? "{\"checked_text\":\"请介绍 LangChain4j。\"}" : planner
                            ? "{\"invoke_agent\":false,\"agent_id\":null,\"task_brief\":null,\"execution_preference\":\"AWAIT\"}"
                            : mapper.writeValueAsString(Map.of("reply", "answer-" + calls.size(),
                                    "expression_tag", "neutral", "expression_intensity", "low",
                                    "voice_style", "neutral", "memory_candidates", List.of()));
                    byte[] response = mapper.writeValueAsBytes(status == 503
                            ? Map.of("error", Map.of("message", "fixture unavailable", "type", "server_error"))
                            : Map.of("id", "fixture-" + calls.size(), "object", "chat.completion",
                                    "created", 1, "model", body.path("model").asText(), "choices", List.of(
                                            Map.of("index", 0, "message", Map.of("role", "assistant", "content", text),
                                                    "finish_reason", "stop")),
                                    "usage", Map.of("prompt_tokens", 10, "completion_tokens", 10, "total_tokens", 20)));
                    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                    exchange.sendResponseHeaders(status, response.length);
                    exchange.getResponseBody().write(response);
                } finally {
                    exchange.close();
                }
            });
            server.createContext("/internal/rag/search", exchange -> {
                try {
                    JsonNode body = mapper.readTree(exchange.getRequestBody());
                    ragQueries.add(body.path("query").asText());
                    int status = failRag.getAndSet(false) ? 503 : 200;
                    byte[] response = mapper.writeValueAsBytes(Map.of("items", emptyRag.getAndSet(false)
                            ? List.of() : List.of("remote-rag-result-" + ragQueries.size())));
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status, response.length);
                    exchange.getResponseBody().write(response);
                } finally { exchange.close(); }
            });
            server.start();
            base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            var answer = OpenAiChatModel.builder().baseUrl(base).apiKey("fixture-only").modelName("dialogue-answer")
                    .maxTokens(1200).maxRetries(0).timeout(Duration.ofSeconds(3)).build();
            var planner = OpenAiChatModel.builder().baseUrl(base).apiKey("fixture-only").modelName("dialogue-planner")
                    .maxTokens(128).maxRetries(0).timeout(Duration.ofSeconds(2)).build();
            provider = new LangChain4jLlmProvider(answer, null, mapper, "Fixture assistant.", "json_object", 1,
                    Map.of(), PromptCacheMetricsRecorder.noop(), new OpenAiProviderTokenizer("gpt-4o-mini"),
                    12000, new AgentPlannerRoute(planner, 1, Duration.ZERO), "dialogue-answer");
            var rag = new com.meguri.core.rag.PythonRagGateway(org.springframework.web.reactive.function.client.WebClient.builder(),
                    base.substring(0, base.length() - 3), "fixture-rag-token", "", 2000,
                    (query, state, limit) -> List.of("local-rag-fallback"));
            core = new TurnOrchestrator(provider, rag,
                    new RuntimeStateMachine(), new ExpressionResolver(), Duration.ofMillis(1), mapper,
                    new NoopMemoryGateway(), new NoopWebSearchGateway(), TrainingFeedbackService.disabled(mapper),
                    WeatherConversationService.disabled(), new InMemoryTurnJournal(mapper),
                    new NoopSessionContextPersistence(), new CapabilityRuntimeFacade(32));
        }

        TurnRecord turn(String sessionId, String text) throws Exception {
            return turn(sessionId, text, RetrievalMode.NONE);
        }

        TurnRecord turn(String sessionId, String text, RetrievalMode mode) throws Exception {
            TurnRecord record = core.start(new TurnRequest("fixture-user", "website", sessionId, text, mode));
            record.getDone().get(10, TimeUnit.SECONDS);
            assertThat(record.getStatus()).as("Core completed turn for %s", text).isEqualTo(TurnStatus.COMPLETED);
            return record;
        }

        @Override public void close() {
            core.reset();
            server.stop(0);
            httpThreads.shutdownNow();
        }
    }
}

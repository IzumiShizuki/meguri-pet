package com.meguri.core.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.dto.ClientCapabilities;
import com.meguri.core.dto.TurnRequest;
import com.meguri.core.retrieval.BundleAssembler;
import com.meguri.core.retrieval.DefaultRetrievalPlanner;
import com.meguri.core.retrieval.InMemoryRetrievalTraceRepository;
import com.meguri.core.retrieval.MemoryRetrievalProviderAdapter;
import com.meguri.core.retrieval.RetrievalContext;
import com.meguri.core.retrieval.RetrievalGate;
import com.meguri.core.retrieval.RetrievalMode;
import com.meguri.core.retrieval.RetrievalPlan;
import com.meguri.core.retrieval.RetrievalPlanner;
import com.meguri.core.retrieval.RetrievalBundle;
import com.meguri.core.retrieval.RetrievalRuntime;
import com.meguri.core.retrieval.RetrievalTrace;
import com.meguri.core.retrieval.SourceType;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class PythonMemoryGatewayTest {
    private static final String TOKEN = "local-test-token";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void recallDistinguishesSuccessEmptyMalformedAndUnavailableResponses() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (LoopbackBridge bridge = new LoopbackBridge(exchange -> {
            JsonNode request = MAPPER.readTree(exchange.getRequestBody());
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(exchange.getRequestURI().getPath()).isEqualTo("/internal/memory/search");
            assertThat(exchange.getRequestHeaders().getFirst("X-Meguri-Internal-Token")).isEqualTo(TOKEN);
            assertThat(request.path("user_id").asText()).isEqualTo("user-1");
            int call = calls.getAndIncrement();
            switch (call) {
                case 0 -> respond(exchange, 200,
                        "{\"items\":[{\"record\":{\"canonical_text\":\"likes tea\"}}]}");
                case 1 -> respond(exchange, 200, "{\"items\":[]}");
                case 2 -> respond(exchange, 200, "{\"result\":[]}");
                case 3 -> respond(exchange, 200, "not-json");
                default -> respond(exchange, 503, "{\"detail\":\"bridge unavailable\"}");
            }
        })) {
            PythonMemoryGateway gateway = gateway(bridge, 1_000);

            MemoryRecall success = gateway.recall(request("success")).block(Duration.ofSeconds(2));
            MemoryRecall empty = gateway.recall(request("empty")).block(Duration.ofSeconds(2));
            MemoryRecall missingItems = gateway.recall(request("missing-items")).block(Duration.ofSeconds(2));
            MemoryRecall malformed = gateway.recall(request("malformed-json")).block(Duration.ofSeconds(2));
            MemoryRecall unavailable = gateway.recall(request("unavailable")).block(Duration.ofSeconds(2));

            assertThat(success).isEqualTo(MemoryRecall.available(List.of("likes tea")));
            assertThat(empty).isEqualTo(MemoryRecall.available(List.of()));
            assertThat(missingItems).isEqualTo(MemoryRecall.unavailable());
            assertThat(malformed).isEqualTo(MemoryRecall.unavailable());
            assertThat(unavailable).isEqualTo(MemoryRecall.unavailable());
            assertThat(calls).hasValue(5);
        }
    }

    @Test
    void timedOutTurnCannotLeakLateHttpResultIntoTheNextTurn() throws Exception {
        CountDownLatch oldRequestStarted = new CountDownLatch(1);
        CountDownLatch releaseOldResponse = new CountDownLatch(1);
        CountDownLatch oldResponseAttempted = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        try (LoopbackBridge bridge = new LoopbackBridge(exchange -> {
            JsonNode request = MAPPER.readTree(exchange.getRequestBody());
            if ("old turn".equals(request.path("query").asText())) {
                oldRequestStarted.countDown();
                try {
                    if (!releaseOldResponse.await(2, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("old response was not released");
                    }
                    respond(exchange, 200, recallJson("old-memory"));
                } finally {
                    oldResponseAttempted.countDown();
                }
            } else {
                calls.incrementAndGet();
                respond(exchange, 200, recallJson("new-memory"));
            }
        })) {
            PythonMemoryGateway gateway = gateway(bridge, 5_000);
            MemoryRetrievalProviderAdapter memory = new MemoryRetrievalProviderAdapter(gateway);
            RetrievalPlanner planner = (query, mode, deadline) -> {
                Map<SourceType, Integer> budgets = Map.of(SourceType.MEMORY, 1);
                return new RetrievalPlan(mode, new com.meguri.core.retrieval.QueryRewriteResult(
                        query, query, List.of(), false), Set.of(SourceType.MEMORY),
                        budgets, budgets, false, 1, 1, deadline);
            };
            InMemoryRetrievalTraceRepository traces = new InMemoryRetrievalTraceRepository();

            try (RetrievalRuntime runtime = new RetrievalRuntime(
                    planner, new RetrievalGate(), Map.of(SourceType.MEMORY, memory),
                    (query, limit, context) -> List.of(), null, new BundleAssembler(), traces,
                    Executors.newVirtualThreadPerTaskExecutor(), Duration.ofMillis(750),
                    Duration.ofMillis(30), true)) {
                Instant oldStart = Instant.now();
                RetrievalBundle oldBundle = runtime.retrieve("old turn", RetrievalMode.FAST,
                        context("old-trace", oldStart.plusSeconds(2), request("old turn")));

                assertThat(oldRequestStarted.await(1, TimeUnit.SECONDS)).isTrue();
                assertThat(oldBundle.lanes().get(SourceType.MEMORY).degradations())
                        .contains("lane_timeout");
                assertThat(oldBundle.items()).isEmpty();

                Instant nextStart = Instant.now();
                RetrievalBundle nextBundle = runtime.retrieve("new turn", RetrievalMode.FAST,
                        context("new-trace", nextStart.plusSeconds(2), request("new turn")));

                assertThat(nextBundle.items()).extracting(item -> item.content())
                        .containsExactly("new-memory");
                assertThat(nextBundle.items()).allSatisfy(item ->
                        assertThat(item.traceId()).isEqualTo("new-trace"));

                releaseOldResponse.countDown();
                assertThat(oldResponseAttempted.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(calls).hasValue(1);
                assertThat(traces.find("old-trace").orElseThrow().replayBundle().items()).isEmpty();
                RetrievalTrace nextTrace = traces.find("new-trace").orElseThrow();
                assertThat(nextTrace.replayBundle().items()).extracting(item -> item.content())
                        .containsExactly("new-memory");
            } finally {
                releaseOldResponse.countDown();
            }
        }
    }

    @Test
    void unavailableMemoryIsReportedToTheTypedRetrievalLane() throws Exception {
        try (LoopbackBridge bridge = new LoopbackBridge(exchange -> respond(exchange, 503, "unavailable"))) {
            var gateway = gateway(bridge, 1000);
            var adapter = new MemoryRetrievalProviderAdapter(gateway);
            var result = adapter.retrieveWithDiagnostics("query", 3,
                    context("unavailable-trace", Instant.now().plusSeconds(3), request("query")));
            assertThat(result.items()).isEmpty();
            assertThat(result.degradations()).contains("memory_unavailable");
        }
    }

    private static PythonMemoryGateway gateway(LoopbackBridge bridge, long timeoutMs) {
        return new PythonMemoryGateway(WebClient.builder(), MAPPER, bridge.baseUrl(), TOKEN, "", timeoutMs);
    }

    private static TurnRequest request(String message) {
        return new TurnRequest("user-1", "website", "session-1", message,
                List.of(), new ClientCapabilities(), null, null, true);
    }

    private static RetrievalContext context(String traceId, Instant deadline, TurnRequest request) {
        return new RetrievalContext("user-1", Set.of(), "snapshot", 1,
                Instant.now(), deadline, traceId, "meguri-local", null, request);
    }

    private static String recallJson(String text) {
        try {
            return MAPPER.writeValueAsString(Map.of("items", List.of(Map.of(
                    "record", Map.of("canonical_text", text)))));
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws Exception;
    }

    private static final class LoopbackBridge implements AutoCloseable {
        private final HttpServer server;

        private LoopbackBridge(Handler handler) throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.setExecutor(Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "python-memory-loopback-test");
                thread.setDaemon(true);
                return thread;
            }));
            server.createContext("/internal/memory/search", exchange -> {
                try {
                    handler.handle(exchange);
                } catch (Exception error) {
                    try {
                        respond(exchange, 500, "{\"detail\":\"test handler failed\"}");
                    } catch (IOException ignored) {
                        exchange.close();
                    }
                }
            });
            server.start();
        }

        private String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}

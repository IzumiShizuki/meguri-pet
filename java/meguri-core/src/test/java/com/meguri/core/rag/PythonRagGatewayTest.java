package com.meguri.core.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.dto.ExpressionTag;
import com.meguri.core.dto.Mode;
import com.meguri.core.dto.Relationship;
import com.meguri.core.dto.RuntimeState;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class PythonRagGatewayTest {
    private static final String TOKEN = "local-rag-test-token";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void searchHandlesSuccessEmptyMalformedAndUnavailableResponses() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (LoopbackRagBridge bridge = new LoopbackRagBridge(exchange -> {
            JsonNode request = MAPPER.readTree(exchange.getRequestBody());
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(exchange.getRequestURI().getPath()).isEqualTo("/internal/rag/search");
            assertThat(exchange.getRequestHeaders().getFirst("X-Meguri-Internal-Token")).isEqualTo(TOKEN);
            assertThat(request.path("limit").asInt()).isEqualTo(3);
            switch (calls.getAndIncrement()) {
                case 0 -> respond(exchange, 200, "{\"items\":[\"likes tea\"]}");
                case 1 -> respond(exchange, 200, "{\"items\":[]}");
                case 2 -> respond(exchange, 200, "{\"payload\":[]}");
                case 3 -> respond(exchange, 200, "not-json");
                default -> respond(exchange, 503, "{\"detail\":\"RAG unavailable\"}");
            }
        })) {
            PythonRagGateway gateway = gateway(bridge, 1_000, (query, state, limit) ->
                    List.of("fallback:" + query));

            assertThat(gateway.search("success", state(), 3)).containsExactly("likes tea");
            assertThat(gateway.search("empty", state(), 3)).isEmpty();
            assertThat(gateway.search("missing-items", state(), 3))
                    .containsExactly("fallback:missing-items");
            assertThat(gateway.search("malformed-json", state(), 3))
                    .containsExactly("fallback:malformed-json");
            assertThat(gateway.search("unavailable", state(), 3))
                    .containsExactly("fallback:unavailable");
            assertThat(calls).hasValue(5);
        }
    }

    @Test
    void lateSearchResponseCannotReplaceFollowingSearchResult() throws Exception {
        CountDownLatch oldRequestStarted = new CountDownLatch(1);
        CountDownLatch releaseOldResponse = new CountDownLatch(1);
        CountDownLatch oldResponseAttempted = new CountDownLatch(1);
        AtomicInteger nextCalls = new AtomicInteger();
        try (LoopbackRagBridge bridge = new LoopbackRagBridge(exchange -> {
            JsonNode request = MAPPER.readTree(exchange.getRequestBody());
            String query = request.path("query").asText();
            if ("old query".equals(query)) {
                oldRequestStarted.countDown();
                try {
                    if (!releaseOldResponse.await(2, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("old response was not released");
                    }
                    respond(exchange, 200, "{\"items\":[\"late old result\"]}");
                } finally {
                    oldResponseAttempted.countDown();
                }
            } else {
                nextCalls.incrementAndGet();
                respond(exchange, 200, "{\"items\":[\"current result\"]}");
            }
        })) {
            RagProvider fallback = (query, runtimeState, limit) -> List.of("fallback:" + query);
            PythonRagGateway gateway = gateway(bridge, 150, fallback);

            List<String> oldResult = gateway.search("old query", state(), 3);
            assertThat(oldRequestStarted.await(1, TimeUnit.SECONDS)).isTrue();
            List<String> currentResult = gateway.search("current query", state(), 3);
            releaseOldResponse.countDown();

            assertThat(oldResponseAttempted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(oldResult).containsExactly("fallback:old query");
            assertThat(currentResult).containsExactly("current result");
            assertThat(nextCalls).hasValue(1);
        } finally {
            releaseOldResponse.countDown();
        }
    }

    @Test
    void fallbackRemainsVisibleInsteadOfAppearingAsSuccessfulRemoteRag() throws Exception {
        try (LoopbackRagBridge bridge = new LoopbackRagBridge(exchange ->
                respond(exchange, 503, "unavailable"))) {
            RagProvider gateway = gateway(bridge, 1000, (query, state, limit) -> List.of("local lexical result"));
            var result = gateway.searchWithDiagnostics("lore query", state(), 3);
            assertThat(result.items()).containsExactly("local lexical result");
            assertThat(result.degradations()).contains("rag_bridge_unavailable");
        }
    }

    private static PythonRagGateway gateway(
            LoopbackRagBridge bridge, long timeoutMs, RagProvider fallback) {
        return new PythonRagGateway(WebClient.builder(), bridge.baseUrl(), TOKEN, "",
                timeoutMs, fallback);
    }

    private static RuntimeState state() {
        return new RuntimeState("airi", Mode.PRIVATE, Relationship.LOVER, "03",
                "2026-07-18T10:00:00+08:00", false, false, false, List.of(ExpressionTag.NEUTRAL));
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

    private static final class LoopbackRagBridge implements AutoCloseable {
        private final HttpServer server;

        private LoopbackRagBridge(Handler handler) throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "python-rag-loopback-test");
                thread.setDaemon(true);
                return thread;
            }));
            server.createContext("/internal/rag/search", exchange -> {
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

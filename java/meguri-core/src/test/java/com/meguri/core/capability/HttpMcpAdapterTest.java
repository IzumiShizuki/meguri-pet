package com.meguri.core.capability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpMcpAdapterTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void usesDateProtocolSessionPaginationJsonAndEventStream() throws Exception {
        AtomicInteger notifications = new AtomicInteger();
        AtomicBoolean deleted = new AtomicBoolean();
        try (TestServer server = server(exchange -> {
            if ("DELETE".equals(exchange.getRequestMethod())) {
                assertThat(exchange.getRequestHeaders().getFirst("Mcp-Session-Id")).isEqualTo("session-1");
                assertThat(exchange.getRequestHeaders().getFirst("MCP-Protocol-Version"))
                        .isEqualTo("2025-11-25");
                deleted.set(true);
                respond(exchange, 204, null, "");
                return;
            }
            JsonNode request = mapper.readTree(exchange.getRequestBody());
            String method = request.path("method").asText();
            if (method.equals("initialize")) {
                assertThat(request.path("params").path("protocolVersion").asText())
                        .isEqualTo("2025-11-25");
                assertThat(request.path("params").path("capabilities").isEmpty()).isTrue();
                exchange.getResponseHeaders().add("Mcp-Session-Id", "session-1");
                json(exchange, request, Map.of(
                        "protocolVersion", "2025-11-25",
                        "capabilities", Map.of(
                                "tools", Map.of("listChanged", true),
                                "prompts", Map.of(),
                                "resources", Map.of())));
                return;
            }
            assertThat(exchange.getRequestHeaders().getFirst("Mcp-Session-Id")).isEqualTo("session-1");
            assertThat(exchange.getRequestHeaders().getFirst("MCP-Protocol-Version"))
                    .isEqualTo("2025-11-25");
            if (method.equals("notifications/initialized")) {
                respond(exchange, 202, "application/json", "");
                return;
            }
            String cursor = request.path("params").path("cursor").asText("");
            String field = method.substring(0, method.indexOf('/'));
            if (cursor.isEmpty()) {
                json(exchange, request, Map.of(field, List.of(item(field, "one")), "nextCursor", field + "-2"));
            } else if (field.equals("tools")) {
                String response = rpc(request, Map.of(field, List.of(item(field, "two"))));
                String eventStream = "data: {\"jsonrpc\":\"2.0\",\"method\":"
                        + "\"notifications/tools/list_changed\"}\n\n"
                        + "data: " + response + "\n\n";
                respond(exchange, 200, "text/event-stream; charset=utf-8", eventStream);
            } else {
                json(exchange, request, Map.of(field, List.of(item(field, "two"))));
            }
        })) {
            HttpMcpAdapter adapter = adapter(server.uri(), 100);
            adapter.onListChanged(notifications::incrementAndGet);

            McpAdapter.Negotiation negotiation = adapter.negotiate(4, Set.of(
                    "tools", "prompts", "resources", "list_changed"));

            assertThat(negotiation.protocolVersion()).isEqualTo(4);
            assertThat(adapter.listTools()).extracting(value -> value.get("name"))
                    .containsExactly("one", "two");
            assertThat(adapter.listPrompts()).extracting(value -> value.get("name"))
                    .containsExactly("one", "two");
            assertThat(adapter.listResources()).extracting(value -> value.get("uri"))
                    .containsExactly("resource://one", "resource://two");
            assertThat(notifications).hasValue(1);

            adapter.close();
            assertThat(deleted).isTrue();
        }
    }

    @Test
    void rejectsNumericProtocolAndCursorCycles() throws Exception {
        AtomicBoolean numericProtocol = new AtomicBoolean(true);
        try (TestServer server = server(exchange -> {
            JsonNode request = mapper.readTree(exchange.getRequestBody());
            if (request.path("method").asText().equals("initialize")) {
                Object protocol = numericProtocol.get() ? 3 : "2025-06-18";
                json(exchange, request, Map.of(
                        "protocolVersion", protocol,
                        "capabilities", Map.of("tools", Map.of())));
                return;
            }
            if (request.path("method").asText().equals("notifications/initialized")) {
                respond(exchange, 202, "application/json", "");
                return;
            }
            json(exchange, request, Map.of("tools", List.of(), "nextCursor", "same"));
        })) {
            HttpMcpAdapter adapter = adapter(server.uri(), 3);
            assertThatThrownBy(() -> adapter.negotiate(3, Set.of("tools")))
                    .isInstanceOf(HttpMcpAdapter.McpTransportException.class)
                    .hasMessageContaining("date string");

            numericProtocol.set(false);
            adapter.negotiate(3, Set.of("tools"));
            assertThatThrownBy(adapter::listTools)
                    .isInstanceOf(HttpMcpAdapter.McpTransportException.class)
                    .hasMessageContaining("cursor cycle");
        }
    }

    @Test
    void explicitPollDetectsCatalogChangesWithoutAResidentConnection() throws Exception {
        AtomicBoolean changed = new AtomicBoolean();
        try (TestServer server = server(exchange -> {
            JsonNode request = mapper.readTree(exchange.getRequestBody());
            String method = request.path("method").asText();
            if (method.equals("initialize")) {
                json(exchange, request, Map.of(
                        "protocolVersion", "2025-06-18",
                        "capabilities", Map.of("tools", Map.of())));
            } else if (method.equals("notifications/initialized")) {
                respond(exchange, 202, "application/json", "");
            } else {
                String name = changed.get() ? "new" : "old";
                json(exchange, request, Map.of("tools", List.of(item("tools", name))));
            }
        })) {
            HttpMcpAdapter adapter = adapter(server.uri(), 4);
            adapter.negotiate(3, Set.of("tools"));
            adapter.listTools();

            assertThat(adapter.pollForListChanges()).isFalse();
            changed.set(true);
            assertThat(adapter.pollForListChanges()).isTrue();
        }
    }

    @Test
    void consumesResidentGetEventStreamBeforeConnectionClosesAndCancelsIt() throws Exception {
        CountDownLatch delivered = new CountDownLatch(1);
        CountDownLatch keepOpen = new CountDownLatch(1);
        AtomicBoolean deleted = new AtomicBoolean();
        try (TestServer server = server(exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                assertThat(exchange.getRequestHeaders().getFirst("Mcp-Session-Id"))
                        .isEqualTo("resident-session");
                assertThat(exchange.getRequestHeaders().getFirst("MCP-Protocol-Version"))
                        .isEqualTo("2025-11-25");
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(("data: {\"jsonrpc\":\"2.0\",\"method\":"
                        + "\"notifications/tools/list_changed\"}\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                keepOpen.await(5, TimeUnit.SECONDS);
                exchange.close();
                return;
            }
            if ("DELETE".equals(exchange.getRequestMethod())) {
                deleted.set(true);
                respond(exchange, 204, null, "");
                return;
            }
            JsonNode request = mapper.readTree(exchange.getRequestBody());
            String method = request.path("method").asText();
            if (method.equals("initialize")) {
                exchange.getResponseHeaders().add("Mcp-Session-Id", "resident-session");
                json(exchange, request, Map.of(
                        "protocolVersion", "2025-11-25",
                        "capabilities", Map.of("tools", Map.of("listChanged", true))));
            } else if (method.equals("notifications/initialized")) {
                respond(exchange, 202, "application/json", "");
            } else {
                json(exchange, request, Map.of("tools", List.of()));
            }
        })) {
            HttpMcpAdapter adapter = new HttpMcpAdapter(
                    server.uri(), Map.of(), null, mapper, HttpClient.newHttpClient(),
                    new McpEndpointPolicy(), true, 10, Duration.ofMillis(25));
            adapter.negotiate(4, Set.of("tools", "list_changed"));
            adapter.onListChanged(delivered::countDown);

            adapter.startListChangeMonitoring(Duration.ofMillis(25));

            assertThat(delivered.await(2, TimeUnit.SECONDS)).isTrue();
            adapter.close();
            keepOpen.countDown();
            assertThat(deleted).isTrue();
        } finally {
            keepOpen.countDown();
        }
    }

    @Test
    void fallsBackToBoundedPollingWhenServerDoesNotAdvertiseSse() throws Exception {
        AtomicBoolean changed = new AtomicBoolean();
        CountDownLatch delivered = new CountDownLatch(1);
        try (TestServer server = server(exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, "application/json", "");
                return;
            }
            if ("DELETE".equals(exchange.getRequestMethod())) {
                respond(exchange, 204, null, "");
                return;
            }
            JsonNode request = mapper.readTree(exchange.getRequestBody());
            String method = request.path("method").asText();
            if (method.equals("initialize")) {
                json(exchange, request, Map.of(
                        "protocolVersion", "2025-06-18",
                        "capabilities", Map.of("tools", Map.of())));
            } else if (method.equals("notifications/initialized")) {
                respond(exchange, 202, "application/json", "");
            } else {
                json(exchange, request, Map.of("tools", List.of(
                        item("tools", changed.get() ? "new" : "old"))));
            }
        })) {
            HttpMcpAdapter adapter = new HttpMcpAdapter(
                    server.uri(), Map.of(), null, mapper, HttpClient.newHttpClient(),
                    new McpEndpointPolicy(), true, 10, Duration.ofMillis(25));
            adapter.negotiate(3, Set.of("tools", "list_changed"));
            adapter.listTools();
            adapter.onListChanged(delivered::countDown);
            changed.set(true);

            adapter.startListChangeMonitoring(Duration.ofMillis(25));

            assertThat(delivered.await(2, TimeUnit.SECONDS)).isTrue();
            adapter.close();
        }
    }

    @Test
    void stopsPaginationAtTheConfiguredPageLimit() throws Exception {
        AtomicInteger pages = new AtomicInteger();
        try (TestServer server = server(exchange -> {
            JsonNode request = mapper.readTree(exchange.getRequestBody());
            String method = request.path("method").asText();
            if (method.equals("initialize")) {
                json(exchange, request, Map.of(
                        "protocolVersion", "2025-06-18",
                        "capabilities", Map.of("tools", Map.of())));
            } else if (method.equals("notifications/initialized")) {
                respond(exchange, 202, "application/json", "");
            } else {
                int page = pages.incrementAndGet();
                json(exchange, request, Map.of(
                        "tools", List.of(), "nextCursor", "page-" + page));
            }
        })) {
            HttpMcpAdapter adapter = adapter(server.uri(), 2);
            adapter.negotiate(3, Set.of("tools"));

            assertThatThrownBy(adapter::listTools)
                    .isInstanceOf(HttpMcpAdapter.McpTransportException.class)
                    .hasMessageContaining("exceeded page limit 2");
            assertThat(pages).hasValue(2);
        }
    }

    private HttpMcpAdapter adapter(URI uri, int maxPages) {
        return new HttpMcpAdapter(uri, Map.of(), null, mapper, HttpClient.newHttpClient(),
                new McpEndpointPolicy(), true, maxPages);
    }

    private static Map<String, Object> item(String field, String name) {
        return field.equals("resources")
                ? Map.of("uri", "resource://" + name, "name", name)
                : Map.of("name", name);
    }

    private void json(HttpExchange exchange, JsonNode request, Map<String, Object> result) throws IOException {
        respond(exchange, 200, "application/json; charset=utf-8", rpc(request, result));
    }

    private String rpc(JsonNode request, Map<String, Object> result) throws IOException {
        return mapper.writeValueAsString(Map.of(
                "jsonrpc", "2.0", "id", request.path("id").asLong(), "result", result));
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        if (contentType != null) exchange.getResponseHeaders().add("Content-Type", contentType);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
        if (bytes.length > 0) exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static TestServer server(Handler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            try {
                handler.handle(exchange);
            } catch (Throwable failure) {
                exchange.close();
                if (failure instanceof AssertionError assertion) throw assertion;
            }
        });
        server.start();
        return new TestServer(server);
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws Exception;
    }

    private record TestServer(HttpServer server) implements AutoCloseable {
        URI uri() {
            return URI.create("http://localhost:" + server.getAddress().getPort() + "/mcp");
        }

        @Override public void close() {
            server.stop(0);
        }
    }
}

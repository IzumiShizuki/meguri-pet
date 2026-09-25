package com.meguri.core.capability;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal MCP JSON-RPC over HTTP client used by configured operational sources.
 * Transport and protocol errors never fall back to an unvalidated tool list.
 */
public final class HttpMcpAdapter implements McpAdapter {
    private static final Map<Integer, String> PROTOCOL_DATES = Map.of(
            1, "2024-11-05",
            2, "2025-03-26",
            3, "2025-06-18",
            4, "2025-11-25");
    private static final int DEFAULT_MAX_LIST_PAGES = 100;
    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(30);

    private final URI endpoint;
    private final Map<String, String> headers;
    private final String authorization;
    private final ObjectMapper objectMapper;
    private final HttpClient client;
    private final McpEndpointPolicy endpointPolicy;
    private final boolean allowInsecureLocalhost;
    private final int maxListPages;
    private final Duration pollingInterval;
    private final AtomicLong requestIds = new AtomicLong();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, String> listDigests = new LinkedHashMap<>();
    private final AtomicBoolean monitoringStarted = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor(runnable ->
            Thread.ofPlatform().daemon().name("meguri-mcp-monitor").unstarted(runnable));
    private Set<String> negotiatedCapabilities = Set.of();
    private volatile String sessionId;
    private volatile String negotiatedProtocolDate;
    private volatile InputStream eventStream;

    HttpMcpAdapter(
            URI endpoint,
            Map<String, String> headers,
            String authorization,
            ObjectMapper objectMapper,
            HttpClient client) {
        this(endpoint, headers, authorization, objectMapper, client,
                new McpEndpointPolicy(), false, DEFAULT_MAX_LIST_PAGES, DEFAULT_POLL_INTERVAL);
    }

    HttpMcpAdapter(
            URI endpoint,
            Map<String, String> headers,
            String authorization,
            ObjectMapper objectMapper,
            HttpClient client,
            McpEndpointPolicy endpointPolicy,
            boolean allowInsecureLocalhost) {
        this(endpoint, headers, authorization, objectMapper, client,
                endpointPolicy, allowInsecureLocalhost, DEFAULT_MAX_LIST_PAGES, DEFAULT_POLL_INTERVAL);
    }

    HttpMcpAdapter(
            URI endpoint,
            Map<String, String> headers,
            String authorization,
            ObjectMapper objectMapper,
            HttpClient client,
            McpEndpointPolicy endpointPolicy,
            boolean allowInsecureLocalhost,
            int maxListPages) {
        this(endpoint, headers, authorization, objectMapper, client, endpointPolicy,
                allowInsecureLocalhost, maxListPages, DEFAULT_POLL_INTERVAL);
    }

    HttpMcpAdapter(
            URI endpoint,
            Map<String, String> headers,
            String authorization,
            ObjectMapper objectMapper,
            HttpClient client,
            McpEndpointPolicy endpointPolicy,
            boolean allowInsecureLocalhost,
            int maxListPages,
            Duration pollingInterval) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.headers = Map.copyOf(headers == null ? Map.of() : headers);
        this.authorization = authorization;
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.client = Objects.requireNonNull(client, "client");
        this.endpointPolicy = Objects.requireNonNull(endpointPolicy, "endpointPolicy");
        this.allowInsecureLocalhost = allowInsecureLocalhost;
        if (maxListPages < 1 || maxListPages > 1_000) {
            throw new IllegalArgumentException("maxListPages must be between 1 and 1000");
        }
        this.maxListPages = maxListPages;
        this.pollingInterval = Objects.requireNonNull(pollingInterval, "pollingInterval");
        if (pollingInterval.isZero() || pollingInterval.isNegative()) {
            throw new IllegalArgumentException("pollingInterval must be positive");
        }
    }

    @Override
    public Negotiation negotiate(int maximumProtocol, Set<String> supportedCapabilities) {
        String requestedProtocol = protocolDate(maximumProtocol);
        JsonNode result = call("initialize", Map.of(
                "protocolVersion", requestedProtocol,
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "meguri-core", "version", "1")));
        JsonNode protocolValue = result.get("protocolVersion");
        if (protocolValue == null || !protocolValue.isTextual()) {
            throw new McpTransportException("MCP protocolVersion must be a date string");
        }
        int protocol = protocolLevel(protocolValue.textValue());
        if (protocol < 1 || protocol > maximumProtocol) {
            throw new McpTransportException("MCP protocol negotiation exceeded local bounds");
        }
        java.util.LinkedHashSet<String> capabilities = new java.util.LinkedHashSet<>();
        JsonNode advertised = result.path("capabilities");
        if (supportedCapabilities.contains("tools") && advertised.has("tools")) capabilities.add("tools");
        if (supportedCapabilities.contains("prompts") && advertised.has("prompts")) capabilities.add("prompts");
        if (supportedCapabilities.contains("resources") && advertised.has("resources")) capabilities.add("resources");
        if (supportedCapabilities.contains("list_changed") && (
                advertised.path("tools").path("listChanged").asBoolean(false)
                || advertised.path("prompts").path("listChanged").asBoolean(false)
                || advertised.path("resources").path("listChanged").asBoolean(false))) {
            capabilities.add("list_changed");
        }
        negotiatedCapabilities = Set.copyOf(capabilities);
        negotiatedProtocolDate = protocolValue.textValue();
        notify("notifications/initialized", Map.of());
        return new Negotiation(protocol, capabilities);
    }

    @Override
    public List<Map<String, Object>> listTools() {
        return list("tools/list", "tools", true);
    }

    @Override
    public List<Map<String, Object>> listPrompts() {
        return list("prompts/list", "prompts", true);
    }

    @Override
    public List<Map<String, Object>> listResources() {
        return list("resources/list", "resources", true);
    }

    @Override
    public List<Map<String, Object>> getPrompt(String promptName, Map<String, Object> arguments) {
        JsonNode messages = call("prompts/get", Map.of(
                "name", CapabilityDescriptor.required(promptName, "promptName"),
                "arguments", arguments == null ? Map.of() : arguments)).path("messages");
        if (!messages.isArray()) throw new McpTransportException("MCP prompts/get returned no messages array");
        return objectMapper.convertValue(messages, new TypeReference<>() { });
    }

    @Override
    public List<Map<String, Object>> readResource(String uri) {
        JsonNode contents = call("resources/read", Map.of(
                "uri", CapabilityDescriptor.required(uri, "uri"))).path("contents");
        if (!contents.isArray()) throw new McpTransportException("MCP resources/read returned no contents array");
        return objectMapper.convertValue(contents, new TypeReference<>() { });
    }

    @Override
    public Map<String, Object> invoke(
            String toolName,
            Map<String, Object> input,
            CapabilityImplementation.ExecutionContext context) {
        JsonNode result = call("tools/call", Map.of(
                "name", CapabilityDescriptor.required(toolName, "toolName"),
                "arguments", input == null ? Map.of() : input,
                "_meta", Map.of("operationId", context.operationId(), "attempt", context.attempt())));
        if (result.path("isError").asBoolean(false)) {
            throw new McpTransportException("MCP tool returned an error");
        }
        return objectMapper.convertValue(result, new TypeReference<>() { });
    }

    @Override
    public void onListChanged(Runnable listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void signalListChanged() {
        listeners.forEach(Runnable::run);
    }

    @Override
    public void startListChangeMonitoring(Duration ignored) {
        if (!monitoringStarted.compareAndSet(false, true) || closed.get()) return;
        if (negotiatedCapabilities.contains("list_changed")) {
            monitor.execute(this::listenForServerEvents);
        } else {
            startPollingFallback();
        }
    }

    @Override
    public synchronized boolean pollForListChanges() {
        Map<String, String> observed = new LinkedHashMap<>();
        if (negotiatedCapabilities.contains("tools")) {
            observed.put("tools", digest(list("tools/list", "tools", false)));
        }
        if (negotiatedCapabilities.contains("prompts")) {
            observed.put("prompts", digest(list("prompts/list", "prompts", false)));
        }
        if (negotiatedCapabilities.contains("resources")) {
            observed.put("resources", digest(list("resources/list", "resources", false)));
        }
        return !observed.equals(listDigests);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        InputStream currentStream = eventStream;
        if (currentStream != null) {
            try {
                currentStream.close();
            } catch (Exception ignored) {
                // Closing the stream unblocks the resident listener.
            }
        }
        monitor.shutdownNow();
        String current = sessionId;
        if (current == null) return;
        try {
            endpointPolicy.validate(endpoint, allowInsecureLocalhost);
            HttpRequest.Builder request = requestBuilder()
                    .DELETE();
            client.send(request.build(), HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {
            // Session deletion is best-effort; local teardown must always complete.
        } finally {
            sessionId = null;
        }
    }

    private synchronized List<Map<String, Object>> list(
            String method, String field, boolean rememberDigest) {
        List<Map<String, Object>> all = new ArrayList<>();
        Set<String> seenCursors = new HashSet<>();
        String cursor = null;
        for (int page = 0; page < maxListPages; page++) {
            Map<String, Object> params = cursor == null ? Map.of() : Map.of("cursor", cursor);
            JsonNode result = call(method, params);
            JsonNode values = result.path(field);
            if (!values.isArray()) {
                throw new McpTransportException("MCP " + method + " returned no " + field + " array");
            }
            all.addAll(objectMapper.convertValue(values, new TypeReference<>() { }));
            JsonNode next = result.get("nextCursor");
            if (next == null || next.isNull() || next.asText().isBlank()) {
                if (rememberDigest) listDigests.put(field, digest(all));
                return List.copyOf(all);
            }
            if (!next.isTextual()) {
                throw new McpTransportException("MCP " + method + " nextCursor must be a string");
            }
            cursor = next.textValue();
            if (!seenCursors.add(cursor)) {
                throw new McpTransportException("MCP " + method + " cursor cycle detected");
            }
        }
        throw new McpTransportException("MCP " + method + " exceeded page limit " + maxListPages);
    }

    private JsonNode call(String method, Map<String, Object> params) {
        long id = requestIds.incrementAndGet();
        JsonNode response = exchange(Map.of(
                "jsonrpc", "2.0", "id", id, "method", method, "params", params));
        if (!response.path("id").canConvertToLong() || response.path("id").asLong() != id) {
            throw new McpTransportException("MCP response id mismatch");
        }
        if (response.has("error")) {
            throw new McpTransportException("MCP remote returned an error");
        }
        JsonNode result = response.get("result");
        if (result == null || !result.isObject()) {
            throw new McpTransportException("MCP response result is invalid");
        }
        return result;
    }

    private void notify(String method, Map<String, Object> params) {
        exchange(Map.of("jsonrpc", "2.0", "method", method, "params", params));
    }

    private JsonNode exchange(Map<String, Object> payload) {
        try {
            endpointPolicy.validate(endpoint, allowInsecureLocalhost);
            HttpRequest.Builder request = requestBuilder();
            String currentSession = sessionId;
            HttpResponse<InputStream> response = client.send(
                    request.POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(payload))).build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                if (response.statusCode() == 404) sessionId = null;
                throw new McpTransportException("MCP HTTP status " + response.statusCode());
            }
            String responseSession = response.headers()
                    .firstValue("Mcp-Session-Id").orElse(null);
            if (responseSession != null && !responseSession.isBlank()) {
                if (currentSession != null && !currentSession.equals(responseSession)) {
                    throw new McpTransportException("MCP session id changed unexpectedly");
                }
                sessionId = responseSession;
            }
            String contentType = response.headers().firstValue("Content-Type")
                    .orElse("application/json").toLowerCase(java.util.Locale.ROOT);
            try (InputStream body = response.body()) {
                if (body == null) return objectMapper.createObjectNode();
                if (contentType.startsWith("application/json")) {
                    byte[] bytes = body.readAllBytes();
                    if (bytes.length == 0) return objectMapper.createObjectNode();
                    return handleMessage(objectMapper.readTree(bytes));
                }
                if (contentType.startsWith("text/event-stream")) {
                    Long expectedId = payload.get("id") instanceof Number number
                            ? number.longValue() : null;
                    JsonNode value = readEventStream(body, expectedId, false);
                    return value == null ? objectMapper.createObjectNode() : value;
                }
            }
            throw new McpTransportException("unsupported MCP response content type");
        } catch (McpTransportException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new McpTransportException("MCP transport failed closed", failure);
        }
    }

    private HttpRequest.Builder requestBuilder() {
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json, text/event-stream")
                .header("Content-Type", "application/json");
        headers.forEach(request::header);
        if (authorization != null) request.header("Authorization", authorization);
        String currentSession = sessionId;
        if (currentSession != null) request.header("Mcp-Session-Id", currentSession);
        String protocol = negotiatedProtocolDate;
        if (protocol != null) request.header("MCP-Protocol-Version", protocol);
        return request;
    }

    private JsonNode readEventStream(InputStream body, Long expectedId, boolean persistent) throws Exception {
        JsonNode response = null;
        StringBuilder data = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
        String line;
        while (!closed.get() && (line = reader.readLine()) != null) {
            if (line.isBlank()) {
                if (!data.isEmpty()) {
                    JsonNode candidate = handleMessage(objectMapper.readTree(data.toString()));
                    if (matches(candidate, expectedId)) response = candidate;
                    data.setLength(0);
                    if (!persistent && response != null) return response;
                }
            } else if (line.startsWith("data:")) {
                if (!data.isEmpty()) data.append('\n');
                data.append(line.substring(5).stripLeading());
            }
        }
        if (!data.isEmpty()) {
            JsonNode candidate = handleMessage(objectMapper.readTree(data.toString()));
            if (matches(candidate, expectedId)) response = candidate;
        }
        if (!persistent && response == null) {
            throw new McpTransportException("MCP event stream contained no JSON-RPC response");
        }
        return response;
    }

    private static boolean matches(JsonNode message, Long expectedId) {
        if (expectedId == null) return message.has("id");
        return message.path("id").canConvertToLong()
                && message.path("id").asLong() == expectedId;
    }

    private void listenForServerEvents() {
        if (closed.get()) return;
        try {
            endpointPolicy.validate(endpoint, allowInsecureLocalhost);
            HttpResponse<InputStream> response = client.send(
                    eventRequestBuilder().GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            String contentType = response.headers().firstValue("Content-Type")
                    .orElse("").toLowerCase(java.util.Locale.ROOT);
            if (response.statusCode() == 404 || response.statusCode() == 405
                    || !contentType.startsWith("text/event-stream")) {
                response.body().close();
                startPollingFallback();
                return;
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                response.body().close();
                scheduleSseReconnect();
                return;
            }
            eventStream = response.body();
            try (InputStream body = eventStream) {
                readEventStream(body, null, true);
            } finally {
                eventStream = null;
            }
            scheduleSseReconnect();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception failure) {
            if (!closed.get()) scheduleSseReconnect();
        }
    }

    private HttpRequest.Builder eventRequestBuilder() {
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .header("Accept", "text/event-stream");
        headers.forEach(request::header);
        if (authorization != null) request.header("Authorization", authorization);
        String currentSession = sessionId;
        if (currentSession != null) request.header("Mcp-Session-Id", currentSession);
        String protocol = negotiatedProtocolDate;
        if (protocol != null) request.header("MCP-Protocol-Version", protocol);
        return request;
    }

    private void scheduleSseReconnect() {
        if (!closed.get()) {
            monitor.schedule(this::listenForServerEvents,
                    pollingInterval.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private void startPollingFallback() {
        if (closed.get()) return;
        monitor.scheduleWithFixedDelay(() -> {
            if (closed.get()) return;
            try {
                if (pollForListChanges()) signalListChanged();
            } catch (RuntimeException ignored) {
                // A later bounded poll retries; source reads remain fail-closed.
            }
        }, pollingInterval.toMillis(), pollingInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    private JsonNode handleMessage(JsonNode message) {
        String method = message.path("method").asText("");
        if (method.equals("notifications/tools/list_changed")
                || method.equals("notifications/prompts/list_changed")
                || method.equals("notifications/resources/list_changed")) {
            signalListChanged();
        }
        return message;
    }

    private static String digest(List<Map<String, Object>> values) {
        return CapabilityDigest.sha256(Map.of("values", values));
    }

    private static String protocolDate(int level) {
        String value = PROTOCOL_DATES.get(level);
        if (value == null) throw new IllegalArgumentException("unsupported MCP protocol level: " + level);
        return value;
    }

    private static int protocolLevel(String date) {
        return PROTOCOL_DATES.entrySet().stream()
                .filter(entry -> entry.getValue().equals(date))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new McpTransportException("unsupported MCP protocol date: " + date));
    }

    public static final class Factory implements McpSourceManager.McpAdapterFactory {
        private final ObjectMapper objectMapper;
        private final HttpClient client;

        public Factory(ObjectMapper objectMapper) {
            this(objectMapper, HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .connectTimeout(Duration.ofSeconds(5))
                    .build());
        }

        Factory(ObjectMapper objectMapper, HttpClient client) {
            this.objectMapper = objectMapper;
            this.client = client;
        }

        @Override
        public McpAdapter create(
                McpSourceManager.SourceConfiguration configuration,
                String authorization) {
            return new HttpMcpAdapter(configuration.endpoint(), configuration.headers(),
                    authorization, objectMapper, client, new McpEndpointPolicy(),
                    configuration.allowInsecureLocalhost(), DEFAULT_MAX_LIST_PAGES);
        }
    }

    public static final class McpTransportException extends RuntimeException {
        McpTransportException(String message) {
            super(message);
        }

        McpTransportException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

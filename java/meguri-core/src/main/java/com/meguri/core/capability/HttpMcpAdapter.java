package com.meguri.core.capability;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal MCP JSON-RPC over HTTP client used by configured operational sources.
 * Transport and protocol errors never fall back to an unvalidated tool list.
 */
public final class HttpMcpAdapter implements McpAdapter {
    private final URI endpoint;
    private final Map<String, String> headers;
    private final String authorization;
    private final ObjectMapper objectMapper;
    private final HttpClient client;
    private final McpEndpointPolicy endpointPolicy;
    private final boolean allowInsecureLocalhost;
    private final AtomicLong requestIds = new AtomicLong();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    HttpMcpAdapter(
            URI endpoint,
            Map<String, String> headers,
            String authorization,
            ObjectMapper objectMapper,
            HttpClient client) {
        this(endpoint, headers, authorization, objectMapper, client,
                new McpEndpointPolicy(), false);
    }

    HttpMcpAdapter(
            URI endpoint,
            Map<String, String> headers,
            String authorization,
            ObjectMapper objectMapper,
            HttpClient client,
            McpEndpointPolicy endpointPolicy,
            boolean allowInsecureLocalhost) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.headers = Map.copyOf(headers == null ? Map.of() : headers);
        this.authorization = authorization;
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.client = Objects.requireNonNull(client, "client");
        this.endpointPolicy = Objects.requireNonNull(endpointPolicy, "endpointPolicy");
        this.allowInsecureLocalhost = allowInsecureLocalhost;
    }

    @Override
    public Negotiation negotiate(int maximumProtocol, Set<String> supportedCapabilities) {
        JsonNode result = call("initialize", Map.of(
                "protocolVersion", maximumProtocol,
                "capabilities", Map.of("tools", Map.of("listChanged", true)),
                "clientInfo", Map.of("name", "meguri-core", "version", "1")));
        int protocol = result.path("protocolVersion").isIntegralNumber()
                ? result.path("protocolVersion").asInt() : maximumProtocol;
        if (protocol < 1 || protocol > maximumProtocol) {
            throw new McpTransportException("MCP protocol negotiation exceeded local bounds");
        }
        Set<String> capabilities = result.path("capabilities").path("tools")
                .path("listChanged").asBoolean(false)
                ? Set.of("tools", "list_changed") : Set.of("tools");
        notify("notifications/initialized", Map.of());
        return new Negotiation(protocol, capabilities);
    }

    @Override
    public List<Map<String, Object>> listTools() {
        JsonNode tools = call("tools/list", Map.of()).path("tools");
        if (!tools.isArray()) throw new McpTransportException("MCP tools/list returned no tool array");
        return objectMapper.convertValue(tools, new TypeReference<>() { });
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
            HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json");
            headers.forEach(request::header);
            if (authorization != null) request.header("Authorization", authorization);
            HttpResponse<String> response = client.send(
                    request.POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(payload))).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new McpTransportException("MCP HTTP status " + response.statusCode());
            }
            if (response.body() == null || response.body().isBlank()) {
                return objectMapper.createObjectNode();
            }
            return objectMapper.readTree(response.body());
        } catch (McpTransportException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new McpTransportException("MCP transport failed closed", failure);
        }
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
                    configuration.allowInsecureLocalhost());
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

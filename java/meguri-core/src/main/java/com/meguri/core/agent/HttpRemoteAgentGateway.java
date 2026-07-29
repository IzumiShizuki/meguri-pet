package com.meguri.core.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import reactor.core.publisher.Mono;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Production Remote Agent transport using an A2A-style submit/status/cancel/result API. */
public final class HttpRemoteAgentGateway implements RemoteAgentGateway {
    private static final Set<String> RESERVED_HEADERS = Set.of(
            "authorization", "content-type", "accept", "host", "content-length");
    private static final int MAX_NESTING = 16;

    private final Config config;
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final Clock clock;

    public HttpRemoteAgentGateway(Config config, ObjectMapper mapper) {
        this(config, mapper, HttpClient.newBuilder()
                .connectTimeout(config.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build(), Clock.systemUTC());
    }

    HttpRemoteAgentGateway(Config config, ObjectMapper mapper, HttpClient client, Clock clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.client = Objects.requireNonNull(client, "client");
        this.clock = Objects.requireNonNull(clock, "clock");
        validateEndpoint(config.endpoint(), config.allowInsecureLocalhost());
    }

    @Override
    public Mono<RemoteSubmission> submit(AgentTask task, InvokeAgentProposal proposal) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(proposal, "proposal");
        Duration remaining = Duration.between(clock.instant(), proposal.deadline());
        if (remaining.isNegative() || remaining.isZero()) {
            return Mono.error(new RemoteAgentTransportException(
                    ErrorCode.DEADLINE_EXCEEDED, "remote agent submit deadline exceeded", false));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("agentId", proposal.agentId());
        body.put("taskId", task.taskId());
        body.put("parentTaskId", task.parentTaskId() == null ? "" : task.parentTaskId());
        body.put("idempotencyKey", task.idempotencyKey());
        body.put("taskBrief", proposal.taskBrief());
        body.put("references", proposal.references());
        body.put("deadline", proposal.deadline().toString());
        body.put("budget", proposal.budget());
        body.put("allowedCapabilities", proposal.allowedCapabilities());
        body.put("resultSchema", proposal.resultSchema());
        body.put("trace", Map.of("traceId", task.context().traceId(), "spanId", task.context().spanId()));
        return exchange("POST", "tasks:submit", body, min(config.requestTimeout(), remaining))
                .map(json -> new RemoteSubmission(remoteTaskId(json)));
    }

    @Override
    public Mono<RemoteAgentStatus> poll(String remoteTaskId) {
        String id = safeTaskId(remoteTaskId);
        return exchange("GET", "tasks/" + id, null, config.requestTimeout())
                .map(json -> status(json.path("status").asText()));
    }

    @Override
    public Mono<Void> cancel(String remoteTaskId) {
        String id = safeTaskId(remoteTaskId);
        return exchange("POST", "tasks/" + id + ":cancel", Map.of(), config.requestTimeout()).then();
    }

    @Override
    public Mono<AgentResult> result(String remoteTaskId) {
        String id = safeTaskId(remoteTaskId);
        return exchange("GET", "tasks/" + id + "/result", null, config.requestTimeout())
                .map(this::agentResult);
    }

    private Mono<JsonNode> exchange(String method, String path, Object body, Duration timeout) {
        return Mono.defer(() -> {
            try {
                HttpRequest.Builder request = HttpRequest.newBuilder(resolve(path))
                        .timeout(timeout)
                        .header("Accept", "application/json")
                        .header("Content-Type", "application/json")
                        .header("X-Meguri-Protocol", "a2a-http-v1");
                config.headers().forEach(request::header);
                if (config.authorization() != null) {
                    request.header("Authorization", config.authorization());
                }
                if ("GET".equals(method)) request.GET();
                else request.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
                return Mono.fromCompletionStage(client.sendAsync(
                                request.build(), HttpResponse.BodyHandlers.ofString()))
                        .map(this::validateResponse)
                        .onErrorMap(error -> stable(error, "remote agent transport failed"));
            } catch (Exception error) {
                return Mono.error(stable(error, "remote agent request creation failed"));
            }
        });
    }

    private JsonNode validateResponse(HttpResponse<String> response) {
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            ErrorCode code = switch (status) {
                case 401, 403 -> ErrorCode.UNAUTHORIZED;
                case 404 -> ErrorCode.NOT_FOUND;
                case 408, 504 -> ErrorCode.DEADLINE_EXCEEDED;
                case 409 -> ErrorCode.CONFLICT;
                case 429 -> ErrorCode.CAPACITY_EXHAUSTED;
                default -> status >= 500 ? ErrorCode.REMOTE_UNAVAILABLE : ErrorCode.PROTOCOL_ERROR;
            };
            throw new RemoteAgentTransportException(code, "remote agent HTTP status " + status,
                    status == 408 || status == 429 || status >= 500);
        }
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        if (!contentType.toLowerCase(Locale.ROOT).contains("application/json")) {
            throw new RemoteAgentTransportException(
                    ErrorCode.PROTOCOL_ERROR, "remote agent response is not JSON", false);
        }
        String body = response.body();
        if (body == null || body.isBlank() || body.length() > config.maximumResponseCharacters()) {
            throw new RemoteAgentTransportException(
                    ErrorCode.INVALID_OUTPUT, "remote agent response size is invalid", false);
        }
        try {
            JsonNode json = mapper.readTree(body);
            if (!json.isObject()) throw new IllegalArgumentException("response must be an object");
            requireDepth(json, 0);
            return json;
        } catch (RemoteAgentTransportException error) {
            throw error;
        } catch (Exception error) {
            throw new RemoteAgentTransportException(
                    ErrorCode.INVALID_OUTPUT, "remote agent returned invalid JSON", false, error);
        }
    }

    private AgentResult agentResult(JsonNode json) {
        String schemaId = requiredText(json, "schemaId");
        String sourceAgentId = requiredText(json, "sourceAgentId");
        JsonNode payload = json.get("payload");
        if (payload == null || !payload.isObject()) {
            throw new RemoteAgentTransportException(
                    ErrorCode.INVALID_OUTPUT, "remote agent payload must be an object", false);
        }
        Map<String, Object> value = mapper.convertValue(payload, new TypeReference<>() { });
        return new AgentResult(schemaId, sourceAgentId, value,
                json.path("sensitive").asBoolean(false), AgentResult.TrustLabel.UNTRUSTED_AGENT_RESULT);
    }

    private static String remoteTaskId(JsonNode json) {
        return safeTaskId(requiredText(json, "remoteTaskId"));
    }

    private static RemoteAgentStatus status(String raw) {
        try {
            return RemoteAgentStatus.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (RuntimeException error) {
            throw new RemoteAgentTransportException(
                    ErrorCode.INVALID_OUTPUT, "unknown remote agent status", false, error);
        }
    }

    private URI resolve(String path) {
        String base = config.endpoint().toString();
        if (!base.endsWith("/")) base += "/";
        return URI.create(base).resolve("./" + path);
    }

    private static String safeTaskId(String value) {
        String id = value == null ? "" : value.trim();
        if (!id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new RemoteAgentTransportException(
                    ErrorCode.INVALID_OUTPUT, "remote task id is invalid", false);
        }
        return id;
    }

    private static String requiredText(JsonNode json, String field) {
        String value = json.path(field).asText();
        if (value.isBlank()) {
            throw new RemoteAgentTransportException(
                    ErrorCode.INVALID_OUTPUT, "remote agent response missing " + field, false);
        }
        return value;
    }

    private static void requireDepth(JsonNode node, int depth) {
        if (depth > MAX_NESTING) {
            throw new RemoteAgentTransportException(
                    ErrorCode.INVALID_OUTPUT, "remote agent output nesting is excessive", false);
        }
        node.elements().forEachRemaining(child -> requireDepth(child, depth + 1));
    }

    private static RemoteAgentTransportException stable(Throwable error, String message) {
        if (error instanceof RemoteAgentTransportException transport) return transport;
        if (error instanceof java.net.http.HttpTimeoutException
                || error instanceof java.util.concurrent.TimeoutException) {
            return new RemoteAgentTransportException(
                    ErrorCode.DEADLINE_EXCEEDED, message, true, error);
        }
        return new RemoteAgentTransportException(
                ErrorCode.REMOTE_UNAVAILABLE, message, true, error);
    }

    private static Duration min(Duration left, Duration right) {
        return left.compareTo(right) <= 0 ? left : right;
    }

    private static void validateEndpoint(URI endpoint, boolean allowInsecureLocalhost) {
        String scheme = endpoint.getScheme() == null ? "" : endpoint.getScheme().toLowerCase(Locale.ROOT);
        if ("https".equals(scheme)) return;
        if (!"http".equals(scheme) || !allowInsecureLocalhost) {
            throw new IllegalArgumentException("remote agent endpoint must use HTTPS");
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(endpoint.getHost())) {
                if (!(address.isLoopbackAddress() || address.isAnyLocalAddress())) {
                    throw new IllegalArgumentException("insecure remote agent endpoint must resolve to localhost");
                }
            }
        } catch (java.net.UnknownHostException error) {
            throw new IllegalArgumentException("remote agent endpoint cannot be resolved", error);
        }
    }

    public record Config(
            URI endpoint,
            String authorization,
            Map<String, String> headers,
            Duration connectTimeout,
            Duration requestTimeout,
            int maximumResponseCharacters,
            boolean allowInsecureLocalhost) {
        public Config {
            Objects.requireNonNull(endpoint, "endpoint");
            authorization = blankToNull(authorization);
            headers = sanitizeHeaders(headers);
            connectTimeout = positive(connectTimeout, "connectTimeout");
            requestTimeout = positive(requestTimeout, "requestTimeout");
            if (maximumResponseCharacters < 256 || maximumResponseCharacters > 4_000_000) {
                throw new IllegalArgumentException("maximumResponseCharacters is outside local bounds");
            }
        }

        private static Map<String, String> sanitizeHeaders(Map<String, String> source) {
            if (source == null || source.isEmpty()) return Map.of();
            Map<String, String> result = new LinkedHashMap<>();
            source.forEach((key, value) -> {
                String name = key == null ? "" : key.trim();
                String normalized = name.toLowerCase(Locale.ROOT);
                if (!name.matches("[A-Za-z0-9-]+") || RESERVED_HEADERS.contains(normalized)) {
                    throw new IllegalArgumentException("unsafe or reserved remote agent header");
                }
                if (value == null || value.contains("\r") || value.contains("\n")) {
                    throw new IllegalArgumentException("unsafe remote agent header value");
                }
                result.put(name, value);
            });
            return Map.copyOf(result);
        }

        private static Duration positive(Duration value, String field) {
            if (value == null || value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException(field + " must be positive");
            }
            return value;
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }
    }

    public enum ErrorCode {
        UNAUTHORIZED,
        NOT_FOUND,
        CONFLICT,
        CAPACITY_EXHAUSTED,
        DEADLINE_EXCEEDED,
        REMOTE_UNAVAILABLE,
        PROTOCOL_ERROR,
        INVALID_OUTPUT
    }

    public static final class RemoteAgentTransportException extends RuntimeException {
        private final ErrorCode code;
        private final boolean retryable;

        RemoteAgentTransportException(ErrorCode code, String message, boolean retryable) {
            super(message);
            this.code = code;
            this.retryable = retryable;
        }

        RemoteAgentTransportException(ErrorCode code, String message, boolean retryable, Throwable cause) {
            super(message, cause);
            this.code = code;
            this.retryable = retryable;
        }

        public ErrorCode code() { return code; }
        public boolean retryable() { return retryable; }
    }
}

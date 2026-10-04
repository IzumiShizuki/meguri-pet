package com.meguri.core.shizuki;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Strict client for Shizuki's process-lifetime, authenticated loopback API.
 * Discovery credentials never leave this class and redirects are disabled.
 */
public final class LoopbackShizukiControlClient implements ShizukiControlClient {
    static final int SUPPORTED_PROTOCOL_MAJOR = 1;
    static final int MAX_MANIFEST_BYTES = 16 * 1024;
    static final int MAX_RESPONSE_BYTES = 256 * 1024;
    private static final int MAX_JSON_DEPTH = 16;
    private static final int MAX_TOKEN_CHARACTERS = 512;
    private static final Set<String> COMMANDS = Set.of(
            "app.navigate",
            "window.show", "window.hide", "window.focus", "window.minimize",
            "window.toggleVisibility",
            "music.play", "music.pause", "music.toggle", "music.next", "music.previous");
    private static final Set<String> DESTINATIONS = Set.of(
            "home", "blog", "music", "apps", "aiHub", "profile", "admin", "author");
    private static final Set<String> SECRET_KEYS = Set.of(
            "secret", "password", "passwd", "token", "access_token", "refresh_token",
            "api_key", "authorization", "cookie", "set_cookie", "credential");

    private final Path manifestPath;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final Duration requestTimeout;

    public LoopbackShizukiControlClient(
            Path manifestPath,
            ObjectMapper mapper,
            Duration connectTimeout,
            Duration requestTimeout) {
        this(manifestPath, mapper, requestTimeout, HttpClient.newBuilder()
                .connectTimeout(positive(connectTimeout, "connectTimeout"))
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build());
    }

    LoopbackShizukiControlClient(
            Path manifestPath,
            ObjectMapper mapper,
            Duration requestTimeout,
            HttpClient http) {
        this.manifestPath = Objects.requireNonNull(manifestPath, "manifestPath")
                .toAbsolutePath().normalize();
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.requestTimeout = positive(requestTimeout, "requestTimeout");
        this.http = Objects.requireNonNull(http, "http");
    }

    @Override
    public Map<String, Object> readState() {
        return runWithRediscovery("state", session -> {
            Handshake handshake = handshake(session);
            JsonNode state = protectedJson(session, "GET", "/v1/state", null);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "online");
            result.put("protocol_version", handshake.protocolVersion());
            result.put("summary", stateSummary(state));
            result.put("state", safeObject(state));
            return Collections.unmodifiableMap(result);
        });
    }

    @Override
    public Map<String, Object> execute(String command, Map<String, Object> payload) {
        String safeCommand = command == null ? "" : command.trim();
        Map<String, Object> safePayload;
        try {
            safePayload = validateCommand(safeCommand, payload);
        } catch (ControlFailure failure) {
            return failureResult("command", safeCommand, failure);
        }
        String commandId = "meguri-" + UUID.randomUUID();
        return runWithRediscovery("command", safeCommand, session -> {
            Handshake handshake = handshake(session);
            if (!handshake.commands().contains(safeCommand)) {
                throw new ControlFailure(
                        ErrorCode.UNSUPPORTED_COMMAND,
                        "Shizuki did not advertise the mapped command");
            }
            Map<String, Object> body = Map.of(
                    "id", commandId,
                    "command", safeCommand,
                    "payload", safePayload);
            JsonNode response = protectedJson(session, "POST", "/v1/commands", body);
            if (!response.path("ok").asBoolean(false)
                    || !commandId.equals(response.path("id").asText())) {
                throw new ControlFailure(
                        ErrorCode.INVALID_RESPONSE,
                        "Shizuki returned an invalid command correlation");
            }
            JsonNode commandResult = response.get("result");
            if (commandResult == null || !commandResult.isObject()) {
                throw new ControlFailure(
                        ErrorCode.INVALID_RESPONSE,
                        "Shizuki command result is missing");
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "executed");
            result.put("protocol_version", handshake.protocolVersion());
            result.put("command", safeCommand);
            result.put("command_id", commandId);
            result.put("summary", "Shizuki 已执行本地操作 " + safeCommand + "。");
            result.put("result", safeObject(commandResult));
            return Collections.unmodifiableMap(result);
        });
    }

    private Map<String, Object> runWithRediscovery(
            String operation,
            SessionAction action) {
        return runWithRediscovery(operation, null, action);
    }

    private Map<String, Object> runWithRediscovery(
            String operation,
            String command,
            SessionAction action) {
        try {
            return action.run(discover());
        } catch (ControlFailure first) {
            if (first.code != ErrorCode.UNAUTHORIZED) {
                return failureResult(operation, command, first);
            }
            try {
                return action.run(discover());
            } catch (ControlFailure refreshed) {
                return failureResult(operation, command, refreshed);
            }
        }
    }

    private Session discover() {
        try {
            if (!Files.isRegularFile(manifestPath)) {
                throw new ControlFailure(ErrorCode.OFFLINE, "Shizuki manifest is absent");
            }
            long size = Files.size(manifestPath);
            if (size <= 0 || size > MAX_MANIFEST_BYTES) {
                throw new ControlFailure(
                        ErrorCode.INVALID_MANIFEST, "Shizuki manifest size is invalid");
            }
            JsonNode manifest = mapper.readTree(Files.readString(
                    manifestPath, StandardCharsets.UTF_8));
            if (manifest == null || !manifest.isObject()) {
                throw new ControlFailure(
                        ErrorCode.INVALID_MANIFEST, "Shizuki manifest must be an object");
            }
            String protocolVersion = requiredText(
                    manifest, "protocolVersion", ErrorCode.INVALID_MANIFEST);
            requireSupportedMajor(protocolVersion, ErrorCode.INCOMPATIBLE_PROTOCOL);
            URI endpoint = strictLoopbackEndpoint(requiredText(
                    manifest, "endpoint", ErrorCode.INVALID_MANIFEST));
            long pid = manifest.path("pid").asLong(-1);
            if (pid <= 0 || ProcessHandle.of(pid).filter(ProcessHandle::isAlive).isEmpty()) {
                throw new ControlFailure(ErrorCode.OFFLINE, "Shizuki process is not running");
            }
            String token = requiredText(manifest, "token", ErrorCode.INVALID_MANIFEST);
            if (token.length() < 16 || token.length() > MAX_TOKEN_CHARACTERS
                    || token.contains("\r") || token.contains("\n")) {
                throw new ControlFailure(
                        ErrorCode.INVALID_MANIFEST, "Shizuki bearer token is invalid");
            }
            String createdAt = requiredText(
                    manifest, "createdAt", ErrorCode.INVALID_MANIFEST);
            try {
                Instant.parse(createdAt);
            } catch (DateTimeParseException invalid) {
                throw new ControlFailure(
                        ErrorCode.INVALID_MANIFEST, "Shizuki manifest timestamp is invalid");
            }
            return new Session(protocolVersion, endpoint, pid, token);
        } catch (ControlFailure failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw new ControlFailure(
                    ErrorCode.INVALID_MANIFEST, "Shizuki manifest could not be read", failure);
        }
    }

    private Handshake handshake(Session session) {
        JsonNode health = publicJson(session, "/v1/health");
        if (!"ok".equals(health.path("status").asText())) {
            throw new ControlFailure(
                    ErrorCode.INVALID_RESPONSE, "Shizuki health response is invalid");
        }
        String healthProtocol = requiredText(
                health, "protocolVersion", ErrorCode.INVALID_RESPONSE);
        requireSupportedMajor(healthProtocol, ErrorCode.INCOMPATIBLE_PROTOCOL);

        JsonNode capabilities = protectedJson(
                session, "GET", "/v1/capabilities", null);
        String capabilitiesProtocol = requiredText(
                capabilities, "protocolVersion", ErrorCode.INVALID_RESPONSE);
        requireSupportedMajor(capabilitiesProtocol, ErrorCode.INCOMPATIBLE_PROTOCOL);
        JsonNode commands = capabilities.path("commands");
        if (!commands.isArray()) {
            throw new ControlFailure(
                    ErrorCode.INVALID_RESPONSE, "Shizuki capabilities are missing commands");
        }
        LinkedHashSet<String> advertised = new LinkedHashSet<>();
        for (JsonNode descriptor : commands) {
            String name = descriptor.path("name").asText("").trim();
            if (COMMANDS.contains(name)) advertised.add(name);
        }
        return new Handshake(capabilitiesProtocol, Set.copyOf(advertised));
    }

    private JsonNode publicJson(Session session, String path) {
        return exchange(session, "GET", path, null, false);
    }

    private JsonNode protectedJson(
            Session session,
            String method,
            String path,
            Object body) {
        return exchange(session, method, path, body, true);
    }

    private JsonNode exchange(
            Session session,
            String method,
            String path,
            Object body,
            boolean authenticated) {
        try {
            URI target = endpointPath(session.endpoint(), path);
            HttpRequest.Builder builder = HttpRequest.newBuilder(target)
                    .timeout(requestTimeout)
                    .header("Accept", "application/json");
            if (authenticated) {
                builder.header("Authorization", "Bearer " + session.token());
            }
            if ("POST".equals(method)) {
                builder.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                mapper.writeValueAsString(body), StandardCharsets.UTF_8));
            } else {
                builder.GET();
            }
            HttpResponse<InputStream> response = http.send(
                    builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes;
            try (InputStream stream = response.body()) {
                long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1);
                if (declared > MAX_RESPONSE_BYTES) {
                    throw new ControlFailure(
                            ErrorCode.INVALID_RESPONSE, "Shizuki response is too large");
                }
                bytes = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
            }
            if (bytes.length == 0 || bytes.length > MAX_RESPONSE_BYTES) {
                throw new ControlFailure(
                        ErrorCode.INVALID_RESPONSE, "Shizuki response size is invalid");
            }
            String contentType = response.headers()
                    .firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
            if (!contentType.contains("application/json")) {
                throw new ControlFailure(
                        ErrorCode.INVALID_RESPONSE, "Shizuki response is not JSON");
            }
            JsonNode json = mapper.readTree(bytes);
            if (json == null || !json.isObject()) {
                throw new ControlFailure(
                        ErrorCode.INVALID_RESPONSE, "Shizuki response must be an object");
            }
            requireDepth(json, 0);
            int status = response.statusCode();
            if (status >= 200 && status < 300) return json;
            if (status == 401 || status == 403) {
                throw new ControlFailure(ErrorCode.UNAUTHORIZED, "Shizuki rejected authentication");
            }
            String remoteCode = json.path("error").path("code").asText("");
            throw new ControlFailure(mapRemoteError(status, remoteCode),
                    "Shizuki returned a structured control failure");
        } catch (ControlFailure failure) {
            throw failure;
        } catch (java.net.http.HttpTimeoutException failure) {
            throw new ControlFailure(ErrorCode.TIMEOUT, "Shizuki request timed out", failure);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new ControlFailure(ErrorCode.INTERRUPTED, "Shizuki request was interrupted", failure);
        } catch (IOException failure) {
            throw new ControlFailure(ErrorCode.OFFLINE, "Shizuki endpoint is unavailable", failure);
        } catch (RuntimeException failure) {
            throw new ControlFailure(
                    ErrorCode.INVALID_RESPONSE, "Shizuki response could not be processed", failure);
        }
    }

    private static URI endpointPath(URI endpoint, String path) {
        if (path == null || !path.startsWith("/") || path.contains("?") || path.contains("#")) {
            throw new ControlFailure(ErrorCode.INVALID_REQUEST, "Shizuki endpoint path is invalid");
        }
        try {
            return new URI("http", null, "127.0.0.1", endpoint.getPort(), path, null, null);
        } catch (java.net.URISyntaxException failure) {
            throw new ControlFailure(
                    ErrorCode.INVALID_REQUEST, "Shizuki endpoint path is invalid", failure);
        }
    }

    private static URI strictLoopbackEndpoint(String raw) {
        try {
            URI endpoint = URI.create(raw);
            String path = endpoint.getRawPath();
            if (!"http".equalsIgnoreCase(endpoint.getScheme())
                    || !"127.0.0.1".equals(endpoint.getHost())
                    || endpoint.getPort() <= 0
                    || endpoint.getUserInfo() != null
                    || endpoint.getRawQuery() != null
                    || endpoint.getRawFragment() != null
                    || !(path == null || path.isEmpty() || "/".equals(path))) {
                throw new ControlFailure(
                        ErrorCode.INVALID_MANIFEST,
                        "Shizuki endpoint must be a strict IPv4 loopback origin");
            }
            return new URI("http", null, "127.0.0.1", endpoint.getPort(), null, null, null);
        } catch (ControlFailure failure) {
            throw failure;
        } catch (Exception failure) {
            throw new ControlFailure(
                    ErrorCode.INVALID_MANIFEST, "Shizuki endpoint is invalid", failure);
        }
    }

    private static Map<String, Object> validateCommand(
            String command,
            Map<String, Object> payload) {
        if (!COMMANDS.contains(command)) {
            throw new ControlFailure(
                    ErrorCode.INVALID_REQUEST, "Command is outside the Meguri allowlist");
        }
        Map<String, Object> value = payload == null ? Map.of() : Map.copyOf(payload);
        if ("app.navigate".equals(command)) {
            if (!value.keySet().equals(Set.of("destination"))
                    || !(value.get("destination") instanceof String destination)
                    || !DESTINATIONS.contains(destination)) {
                throw new ControlFailure(
                        ErrorCode.INVALID_REQUEST, "Navigation destination is invalid");
            }
        } else if (!value.isEmpty()) {
            throw new ControlFailure(
                    ErrorCode.INVALID_REQUEST, "This command does not accept a payload");
        }
        return value;
    }

    private static String stateSummary(JsonNode state) {
        boolean ready = state.path("renderer").path("ready").asBoolean(false);
        String route = state.path("renderer").path("route").path("fullPath").asText("");
        JsonNode player = state.path("renderer").path("player");
        boolean playing = player.path("isPlaying").asBoolean(false);
        String track = player.path("currentTrack").path("title").asText("");
        String artist = player.path("currentTrack").path("artist").asText("");
        StringBuilder summary = new StringBuilder("Shizuki 桌面应用已运行；界面")
                .append(ready ? "已就绪" : "尚未就绪");
        if (!route.isBlank()) summary.append("，当前路由为 ").append(bounded(route, 256));
        summary.append("，音乐").append(playing ? "正在播放" : "未播放");
        if (!track.isBlank()) {
            summary.append("，当前曲目为 ").append(bounded(track, 160));
            if (!artist.isBlank()) summary.append(" - ").append(bounded(artist, 160));
        }
        summary.append("。");
        return summary.toString();
    }

    private static Map<String, Object> failureResult(
            String operation,
            String command,
            ControlFailure failure) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", failure.code == ErrorCode.OFFLINE ? "offline" : "failed");
        result.put("operation", operation);
        if (command != null && COMMANDS.contains(command)) result.put("command", command);
        result.put("error_code", failure.code.name());
        result.put("summary", failureSummary(failure.code, command));
        return Map.copyOf(result);
    }

    private static String failureSummary(ErrorCode code, String command) {
        return switch (code) {
            case OFFLINE -> "Shizuki 桌面应用当前未运行；请先启动 Shizuki 桌面版后重试。";
            case INCOMPATIBLE_PROTOCOL -> "当前 Shizuki 控制协议与 Meguri 不兼容。";
            case UNAUTHORIZED -> "Shizuki 控制凭据已失效；请重启 Shizuki 后重试。";
            case UNSUPPORTED_COMMAND -> "当前 Shizuki 版本不支持操作 "
                    + bounded(command, 80) + "。";
            case RENDERER_UNAVAILABLE -> "Shizuki 已启动，但界面尚未就绪；请稍后重试。";
            case RENDERER_TIMEOUT, TIMEOUT -> "Shizuki 没有及时确认操作，Meguri 不会猜测操作已经成功。";
            case INTERRUPTED -> "Shizuki 操作已取消。";
            case INVALID_REQUEST -> "Meguri 拒绝了不在安全白名单内的 Shizuki 操作。";
            case INVALID_MANIFEST, INVALID_RESPONSE, COMMAND_FAILED ->
                    "Shizuki 本地控制接口返回了无效或失败的结果。";
        };
    }

    private Map<String, Object> safeObject(JsonNode node) {
        Object value = safeValue(node, 0);
        if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> object = (Map<String, Object>) map;
            return object;
        }
        throw new ControlFailure(ErrorCode.INVALID_RESPONSE, "Expected an object response");
    }

    private Object safeValue(JsonNode node, int depth) {
        if (depth > MAX_JSON_DEPTH) {
            throw new ControlFailure(ErrorCode.INVALID_RESPONSE, "Shizuki response nesting is excessive");
        }
        if (node == null || node.isNull()) return null;
        if (node.isObject()) {
            Map<String, Object> result = new LinkedHashMap<>();
            node.fields().forEachRemaining(entry -> {
                if (!isSecretKey(entry.getKey())) {
                    result.put(entry.getKey(), safeValue(entry.getValue(), depth + 1));
                }
            });
            return Collections.unmodifiableMap(result);
        }
        if (node.isArray()) {
            List<Object> result = new ArrayList<>();
            node.forEach(child -> result.add(safeValue(child, depth + 1)));
            return Collections.unmodifiableList(result);
        }
        if (node.isTextual()) return bounded(node.textValue(), 16_384);
        if (node.isBoolean()) return node.booleanValue();
        if (node.isIntegralNumber()) return node.longValue();
        if (node.isFloatingPointNumber()) return node.doubleValue();
        throw new ControlFailure(ErrorCode.INVALID_RESPONSE, "Shizuki response value is unsupported");
    }

    private static boolean isSecretKey(String key) {
        String normalized = key.replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .toLowerCase(Locale.ROOT).replace('-', '_');
        return SECRET_KEYS.contains(normalized)
                || normalized.endsWith("_secret")
                || normalized.endsWith("_password")
                || normalized.endsWith("_token");
    }

    private static void requireDepth(JsonNode node, int depth) {
        if (depth > MAX_JSON_DEPTH) {
            throw new ControlFailure(ErrorCode.INVALID_RESPONSE, "Shizuki response nesting is excessive");
        }
        node.elements().forEachRemaining(child -> requireDepth(child, depth + 1));
    }

    private static String requiredText(JsonNode node, String field, ErrorCode code) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new ControlFailure(code, "Shizuki data is missing " + field);
        }
        return value.textValue().trim();
    }

    private static void requireSupportedMajor(String version, ErrorCode code) {
        String[] parts = version.split("\\.", 2);
        try {
            if (parts.length < 2 || Integer.parseInt(parts[0]) != SUPPORTED_PROTOCOL_MAJOR) {
                throw new ControlFailure(code, "Shizuki protocol major is incompatible");
            }
        } catch (NumberFormatException invalid) {
            throw new ControlFailure(code, "Shizuki protocol version is invalid", invalid);
        }
    }

    private static ErrorCode mapRemoteError(int status, String remoteCode) {
        return switch (remoteCode) {
            case "RENDERER_UNAVAILABLE" -> ErrorCode.RENDERER_UNAVAILABLE;
            case "RENDERER_TIMEOUT" -> ErrorCode.RENDERER_TIMEOUT;
            case "UNSUPPORTED_COMMAND" -> ErrorCode.UNSUPPORTED_COMMAND;
            default -> status == 503 ? ErrorCode.RENDERER_UNAVAILABLE
                    : status == 504 ? ErrorCode.RENDERER_TIMEOUT
                    : ErrorCode.COMMAND_FAILED;
        };
    }

    private static String bounded(String value, int maximum) {
        if (value == null) return "";
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    private static Duration positive(Duration value, String field) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    @FunctionalInterface
    private interface SessionAction {
        Map<String, Object> run(Session session);
    }

    private record Session(String protocolVersion, URI endpoint, long pid, String token) { }

    private record Handshake(String protocolVersion, Set<String> commands) { }

    private enum ErrorCode {
        OFFLINE,
        INVALID_MANIFEST,
        INCOMPATIBLE_PROTOCOL,
        UNAUTHORIZED,
        INVALID_REQUEST,
        INVALID_RESPONSE,
        UNSUPPORTED_COMMAND,
        RENDERER_UNAVAILABLE,
        RENDERER_TIMEOUT,
        COMMAND_FAILED,
        TIMEOUT,
        INTERRUPTED
    }

    private static final class ControlFailure extends RuntimeException {
        private final ErrorCode code;

        private ControlFailure(ErrorCode code, String message) {
            super(message);
            this.code = Objects.requireNonNull(code, "code");
        }

        private ControlFailure(ErrorCode code, String message, Throwable cause) {
            super(message, cause);
            this.code = Objects.requireNonNull(code, "code");
        }
    }
}

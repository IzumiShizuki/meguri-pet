package com.meguri.core.shizuki;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class LoopbackShizukiControlClientTest {
    private static final String TOKEN = "1234567890123456789012345678901234567890123";

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Path> temporaryRoots = new ArrayList<>();
    private HttpServer server;

    @AfterEach
    void cleanup() throws IOException {
        if (server != null) server.stop(0);
        for (Path root : temporaryRoots) {
            try (var paths = Files.walk(root)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // Best-effort test cleanup.
                    }
                });
            }
        }
    }

    @Test
    void discoversAuthenticatesReadsStateAndExecutesAllowlistedCommand() throws Exception {
        AtomicReference<JsonNode> posted = new AtomicReference<>();
        server = server();
        server.createContext("/v1/health", exchange -> json(exchange, 200,
                Map.of("status", "ok", "protocolVersion", "1.0")));
        server.createContext("/v1/capabilities", exchange -> {
            if (!authorized(exchange, TOKEN)) {
                json(exchange, 401, Map.of("ok", false));
                return;
            }
            json(exchange, 200, capabilities("music.pause", "app.navigate"));
        });
        server.createContext("/v1/state", exchange -> {
            if (!authorized(exchange, TOKEN)) {
                json(exchange, 401, Map.of("ok", false));
                return;
            }
            json(exchange, 200, Map.of(
                    "protocolVersion", "1.0",
                    "host", Map.of("running", true, "pid", ProcessHandle.current().pid()),
                    "window", Map.of("visible", true),
                    "renderer", Map.of(
                            "ready", true,
                            "route", Map.of("fullPath", "/music", "accessToken", "leak"),
                            "player", Map.of("isPlaying", true))));
        });
        server.createContext("/v1/commands", exchange -> {
            if (!authorized(exchange, TOKEN)) {
                json(exchange, 401, Map.of("ok", false));
                return;
            }
            JsonNode command = mapper.readTree(exchange.getRequestBody());
            posted.set(command);
            json(exchange, 200, Map.of(
                    "id", command.path("id").asText(),
                    "ok", true,
                    "result", Map.of("accepted", true, "token", "must-not-escape")));
        });
        server.start();
        Path manifest = manifest("http://127.0.0.1:" + server.getAddress().getPort(), TOKEN);
        LoopbackShizukiControlClient client = client(manifest);

        Map<String, Object> state = client.readState();
        Map<String, Object> command = client.execute("music.pause", Map.of());

        assertThat(state).containsEntry("status", "online");
        assertThat(state.get("summary").toString()).contains("已就绪", "正在播放");
        assertThat(state.toString()).doesNotContain("accessToken", "leak", TOKEN);
        assertThat(command).containsEntry("status", "executed")
                .containsEntry("command", "music.pause");
        assertThat(command.toString()).doesNotContain("must-not-escape", TOKEN);
        assertThat(posted.get().path("command").asText()).isEqualTo("music.pause");
        assertThat(posted.get().path("payload").isEmpty()).isTrue();
        assertThat(posted.get().path("id").asText()).matches("meguri-[0-9a-f-]{36}");
    }

    @Test
    void rejectsNonLoopbackManifestBeforeSendingCredentials() throws Exception {
        Path manifest = manifest("http://localhost:49152", TOKEN);

        Map<String, Object> result = client(manifest).readState();

        assertThat(result).containsEntry("status", "failed")
                .containsEntry("error_code", "INVALID_MANIFEST");
        assertThat(result.toString()).doesNotContain(TOKEN);
    }

    @Test
    void refusesCommandMissingFromAdvertisedCapabilities() throws Exception {
        AtomicInteger posts = new AtomicInteger();
        server = server();
        server.createContext("/v1/health", exchange -> json(exchange, 200,
                Map.of("status", "ok", "protocolVersion", "1.0")));
        server.createContext("/v1/capabilities", exchange -> json(exchange, 200,
                capabilities("window.show")));
        server.createContext("/v1/commands", exchange -> {
            posts.incrementAndGet();
            json(exchange, 500, Map.of("ok", false));
        });
        server.start();
        Path manifest = manifest("http://127.0.0.1:" + server.getAddress().getPort(), TOKEN);

        Map<String, Object> result = client(manifest).execute("music.pause", Map.of());

        assertThat(result).containsEntry("status", "failed")
                .containsEntry("error_code", "UNSUPPORTED_COMMAND");
        assertThat(posts).hasValue(0);
    }

    @Test
    void refreshesManifestOnceAfterKnownUnauthenticatedRejection() throws Exception {
        String rotatedToken = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG";
        AtomicReference<Path> manifestRef = new AtomicReference<>();
        AtomicInteger capabilityCalls = new AtomicInteger();
        server = server();
        server.createContext("/v1/health", exchange -> json(exchange, 200,
                Map.of("status", "ok", "protocolVersion", "1.0")));
        server.createContext("/v1/capabilities", exchange -> {
            int call = capabilityCalls.incrementAndGet();
            if (call == 1) {
                writeManifest(manifestRef.get(), endpoint(), rotatedToken);
                json(exchange, 401, Map.of("ok", false));
                return;
            }
            if (!authorized(exchange, rotatedToken)) {
                json(exchange, 401, Map.of("ok", false));
                return;
            }
            json(exchange, 200, capabilities("music.pause"));
        });
        server.createContext("/v1/state", exchange -> json(exchange, 200, Map.of(
                "protocolVersion", "1.0", "renderer", Map.of("ready", true))));
        server.start();
        Path manifest = manifest(endpoint(), TOKEN);
        manifestRef.set(manifest);

        Map<String, Object> result = client(manifest).readState();

        assertThat(result).containsEntry("status", "online");
        assertThat(capabilityCalls).hasValue(2);
        assertThat(result.toString()).doesNotContain(TOKEN, rotatedToken);
    }

    @Test
    void reportsMissingManifestAsOffline() throws Exception {
        Path root = Files.createTempDirectory("meguri-shizuki-missing-");
        temporaryRoots.add(root);

        Map<String, Object> result = client(root.resolve("control-api.json")).readState();

        assertThat(result).containsEntry("status", "offline")
                .containsEntry("error_code", "OFFLINE");
        assertThat(result.get("summary").toString()).contains("请先启动 Shizuki");
    }

    private LoopbackShizukiControlClient client(Path manifest) {
        return new LoopbackShizukiControlClient(
                manifest, mapper, Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    private HttpServer server() throws IOException {
        return HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    private String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private Path manifest(String endpoint, String token) throws IOException {
        Path root = Files.createTempDirectory("meguri-shizuki-");
        temporaryRoots.add(root);
        Path manifest = root.resolve("control-api.json");
        writeManifest(manifest, endpoint, token);
        return manifest;
    }

    private void writeManifest(Path manifest, String endpoint, String token) throws IOException {
        Files.writeString(manifest, mapper.writeValueAsString(Map.of(
                "protocolVersion", "1.0",
                "endpoint", endpoint,
                "pid", ProcessHandle.current().pid(),
                "token", token,
                "createdAt", Instant.now().toString())), StandardCharsets.UTF_8);
    }

    private Map<String, Object> capabilities(String... commands) {
        return Map.of(
                "protocolVersion", "1.0",
                "commands", java.util.Arrays.stream(commands)
                        .map(command -> Map.of("name", command, "target", "renderer"))
                        .toList());
    }

    private static boolean authorized(HttpExchange exchange, String token) {
        return ("Bearer " + token).equals(exchange.getRequestHeaders().getFirst("Authorization"));
    }

    private void json(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}

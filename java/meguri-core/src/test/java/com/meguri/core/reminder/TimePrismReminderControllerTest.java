package com.meguri.core.reminder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.security.CoreIdentityVerifier;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;


class TimePrismReminderControllerTest {
    @TempDir Path tempDir;
    private HttpServer timePrism;

    @AfterEach
    void stopTimePrism() {
        if (timePrism != null) timePrism.stop(0);
    }

    @Test
    void rejectsCallerWhoseAuthenticatedIdentityIsNotTheConfiguredOwner() {
        ObjectMapper mapper = new ObjectMapper();
        long ownerId = 42;
        var writer = new TimePrismTodoWriter(mapper, tempDir.toString(), ownerId,
                tempDir.resolve("email").toString(), tempDir.resolve("password").toString(),
                "http://127.0.0.1:1", 250);
        var identity = new CoreIdentityVerifier(true, "test-tenant", "", "synthetic-shared-token");
        var controller = new TimePrismReminderController(
                new TimePrismReminderStore(mapper, tempDir.toString()), writer, identity, ownerId);
        WebTestClient client = WebTestClient.bindToController(controller).build();
        String dueAt = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).plusDays(2)
                .withSecond(0).withNano(0).format(DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm"));

        client.post().uri("/v1/reminders/timeprism/todos")
                .headers(headers -> {
                    headers.setBearerAuth("synthetic-shared-token");
                    headers.set("X-Meguri-Tenant-ID", "test-tenant");
                    headers.set("X-Meguri-User-ID", "shizuki-43");
                    headers.set("X-Meguri-Client-ID", "astrbot");
                    headers.set("X-Meguri-Session-ID", "timeprism-todo-writer");
                })
                .bodyValue(new TimePrismTodoCreateRequest(
                        "2e689858-df59-4a6d-a9b4-8e1ec66c78b9", "Synthetic reminder", dueAt))
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void verifiesPrivilegedTokenEvenWhenGlobalIdentityFilterIsDisabled() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicInteger todoPosts = new AtomicInteger();
        timePrism = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        timePrism.createContext("/api/v1/auth/tokens", exchange -> respond(exchange, 200,
                mapper.createObjectNode().put("resultType", "TOKEN_ISSUED")
                        .put("accessToken", "synthetic-access-token").put("userId", 42)));
        timePrism.createContext("/api/v1/light-apps/todos", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, mapper.createObjectNode().set("data", mapper.createArrayNode()));
                return;
            }
            todoPosts.incrementAndGet();
            var body = mapper.readTree(exchange.getRequestBody());
            var todo = mapper.createObjectNode()
                    .put("todoId", 701)
                    .put("title", body.path("title").asText())
                    .put("detail", body.path("detail").asText())
                    .put("dueAt", body.path("due_at").asText())
                    .put("done", false)
                    .put("reminderEnabled", true)
                    .put("timePrecision", "MINUTE")
                    .put("timingMode", "DEADLINE")
                    .put("deadlineRemindValue", 0)
                    .put("deadlineRemindUnit", "MINUTE");
            respond(exchange, 200, mapper.createObjectNode().set("data", todo));
        });
        timePrism.start();
        Path email = Files.writeString(tempDir.resolve("owner-email.txt"), "synthetic@example.invalid");
        Path password = Files.writeString(tempDir.resolve("owner-password.txt"), "synthetic-test-password");
        var writer = new TimePrismTodoWriter(mapper, tempDir.toString(), 42,
                email.toString(), password.toString(), "http://127.0.0.1:" + timePrism.getAddress().getPort(), 2_000);
        var identity = new CoreIdentityVerifier(false, "test-tenant", "", "synthetic-shared-token");
        var controller = new TimePrismReminderController(
                new TimePrismReminderStore(mapper, tempDir.toString()), writer, identity, 42);
        WebTestClient client = WebTestClient.bindToController(controller).build();
        String dueAt = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).plusDays(2)
                .withSecond(0).withNano(0).format(DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm"));
        var validRequest = new TimePrismTodoCreateRequest(
                "2e689858-df59-4a6d-a9b4-8e1ec66c78b9", "Synthetic reminder", dueAt);

        client.post().uri("/v1/reminders/timeprism/todos")
                .headers(headers -> addIdentity(headers, "synthetic-shared-token", "shizuki-42"))
                .bodyValue(validRequest).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.state").isEqualTo("confirmed")
                .jsonPath("$.dueAt").isEqualTo(dueAt);
        client.post().uri("/v1/reminders/timeprism/todos")
                .headers(headers -> addIdentity(headers, "wrong-token", "shizuki-42"))
                .bodyValue(new TimePrismTodoCreateRequest(
                        "446898b8-f9c7-4c2f-bc14-a73de19c787e", "Blocked reminder", dueAt))
                .exchange().expectStatus().isUnauthorized();
        org.assertj.core.api.Assertions.assertThat(todoPosts).hasValue(1);
    }

    private static void addIdentity(org.springframework.http.HttpHeaders headers, String token, String userId) {
        headers.setBearerAuth(token);
        headers.set("X-Meguri-Tenant-ID", "test-tenant");
        headers.set("X-Meguri-User-ID", userId);
        headers.set("X-Meguri-Client-ID", "astrbot");
        headers.set("X-Meguri-Session-ID", "timeprism-todo-writer");
    }

    private static void respond(HttpExchange exchange, int status, com.fasterxml.jackson.databind.JsonNode body)
            throws IOException {
        byte[] bytes = new ObjectMapper().writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}

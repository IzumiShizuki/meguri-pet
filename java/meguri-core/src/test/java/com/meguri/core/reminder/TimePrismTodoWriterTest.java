package com.meguri.core.reminder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimePrismTodoWriterTest {
    private static final long OWNER_ID = 42;
    private static final String REQUEST_ID = "2e689858-df59-4a6d-a9b4-8e1ec66c78b9";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir Path tempDir;
    private Fixture fixture;
    private Path emailFile;
    private Path passwordFile;
    private String dueAt;

    @BeforeEach
    void startFixture() throws IOException {
        emailFile = Files.writeString(tempDir.resolve("owner-email.txt"), "synthetic@example.invalid");
        passwordFile = Files.writeString(tempDir.resolve("owner-password.txt"), "synthetic-test-password");
        dueAt = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).plusDays(2)
                .withSecond(0).withNano(0).format(DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm"));
        fixture = new Fixture();
    }

    @AfterEach
    void stopFixture() {
        if (fixture != null) fixture.close();
    }

    @Test
    void confirmsVerifiedReceiptAndReturnsCachedReceiptForRepeatedRequest() {
        TimePrismTodoWriter writer = writer(15_000);
        TimePrismTodoCreateRequest request = request(REQUEST_ID, "Synthetic reminder");

        TimePrismTodoWriteReceipt first = writer.create(request);
        TimePrismTodoWriteReceipt repeated = writer.create(request);
        TimePrismTodoWriteReceipt reloaded = writer(15_000).create(request);

        assertThat(first.state()).isEqualTo("confirmed");
        assertThat(first.todoId()).isEqualTo(701);
        assertThat(first.title()).isEqualTo("Synthetic reminder");
        assertThat(first.dueAt()).isEqualTo(request.dueAt());
        assertThat(repeated).usingRecursiveComparison().isEqualTo(first);
        assertThat(reloaded.dueAt()).isEqualTo(request.dueAt());
        assertThat(fixture.postCount).hasValue(1);
        assertThat(fixture.loginCount).hasValue(1);
        assertThat(fixture.lastPost.get()).containsEntry("detail", "meguri-request:" + REQUEST_ID)
                .containsEntry("priority", "MEDIUM")
                .containsEntry("done", false)
                .containsEntry("time_precision", "MINUTE")
                .containsEntry("timing_mode", "DEADLINE")
                .containsEntry("reminder_enabled", true);
    }

    @Test
    void rejectsARequestIdReusedForDifferentContentWithoutAnotherRemoteWrite() {
        TimePrismTodoWriter writer = writer(15_000);
        writer.create(request(REQUEST_ID, "Synthetic reminder"));

        assertThatThrownBy(() -> writer.create(request(REQUEST_ID, "Different reminder")))
                .isInstanceOf(TimePrismTodoWriter.RequestConflictException.class);
        assertThat(fixture.postCount).hasValue(1);
    }

    @Test
    void refusesToWriteWhenAuthenticationReturnsAnotherOwner() {
        fixture.authenticatedUserId.set(OWNER_ID + 1);

        TimePrismTodoWriter legacyConstructorWriter = new TimePrismTodoWriter(MAPPER, tempDir.toString(), OWNER_ID,
                emailFile.toString(), passwordFile.toString(), fixture.origin());
        assertThatThrownBy(() -> legacyConstructorWriter.create(request(REQUEST_ID, "Synthetic reminder")))
                .isInstanceOf(TimePrismTodoWriter.WriterNotConfiguredException.class);
        assertThat(fixture.postCount).hasValue(0);
    }

    @Test
    void boundsStalledLoginBodiesAndAllowsTheNextAuthenticationAttempt() throws Exception {
        fixture.stallNextAuthBody.set(true);
        TimePrismTodoWriter writer = writer(250);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<TimePrismTodoWriteReceipt> pending = executor.submit(
                () -> writer.create(request(REQUEST_ID, "Synthetic reminder")));
        try {
            assertThat(fixture.stalledAuthHeaders.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> pending.get(2, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(TimePrismTodoWriter.SiteUnavailableException.class);
            assertThat(fixture.postCount).hasValue(0);
        }
        finally {
            fixture.releaseStalledAuth.countDown();
            executor.shutdownNow();
        }

        assertThat(writer.create(request(REQUEST_ID, "Synthetic reminder")).state()).isEqualTo("confirmed");
        assertThat(fixture.postCount).hasValue(1);
    }

    @Test
    void reconcilesAWriteThatCommittedBeforeTheResponseBodyStalledAndAllowsLaterCalls() throws Exception {
        fixture.stallNextPostBody.set(true);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<TimePrismTodoWriteReceipt> pending = executor.submit(
                () -> writer(250).create(request(REQUEST_ID, "Synthetic reminder")));
        try {
            assertThat(fixture.stalledPostHeaders.await(2, TimeUnit.SECONDS)).isTrue();

            // The response headers have arrived, but the body is intentionally held open.
            TimePrismTodoWriteReceipt first = pending.get(2, TimeUnit.SECONDS);
            assertThat(first.state()).isEqualTo("uncertain");
        }
        finally {
            fixture.releaseStalledPost.countDown();
            executor.shutdownNow();
        }

        TimePrismTodoWriteReceipt recovered = writer(250).create(request(REQUEST_ID, "Synthetic reminder"));
        assertThat(recovered.state()).isEqualTo("confirmed");
        assertThat(recovered.todoId()).isEqualTo(701);
        assertThat(recovered.dueAt()).isEqualTo(request(REQUEST_ID, "Synthetic reminder").dueAt());
        assertThat(fixture.postCount).hasValue(1);
        assertThat(fixture.getCount).hasValue(1);
    }

    @Test
    void boundsResponseBodiesToOneMiBAndKeepsPotentiallyCommittedWritesUncertain() {
        fixture.oversizeNextPostBody.set(true);
        TimePrismTodoWriter writer = writer(2_000);

        assertThat(writer.create(request(REQUEST_ID, "Synthetic reminder")).state()).isEqualTo("uncertain");
        TimePrismTodoWriteReceipt recovered = writer.create(request(REQUEST_ID, "Synthetic reminder"));

        assertThat(recovered.state()).isEqualTo("confirmed");
        assertThat(recovered.todoId()).isEqualTo(701);
        assertThat(fixture.postCount).hasValue(1);
        assertThat(fixture.getCount).hasValue(1);
    }

    @Test
    void keepsAnUnmatchedUncertainWritePendingAndBlocksEquivalentNewRequestIds() {
        fixture.commitTodos.set(false);
        fixture.failNextPostWithServerError.set(true);
        TimePrismTodoWriter writer = writer(15_000);
        TimePrismTodoCreateRequest original = request(REQUEST_ID, "Synthetic reminder");

        assertThat(writer.create(original).state()).isEqualTo("uncertain");
        assertThat(writer.create(original).state()).isEqualTo("uncertain");
        assertThatThrownBy(() -> writer.create(new TimePrismTodoCreateRequest(
                UUID.randomUUID().toString(), original.title(), original.dueAt())))
                .isInstanceOf(TimePrismTodoWriter.PendingDuplicateException.class);
        assertThat(fixture.postCount).hasValue(1);
    }

    private TimePrismTodoWriter writer(long timeoutMs) {
        return new TimePrismTodoWriter(MAPPER, tempDir.toString(), OWNER_ID,
                emailFile.toString(), passwordFile.toString(), fixture.origin(), timeoutMs);
    }

    private TimePrismTodoCreateRequest request(String requestId, String title) {
        return new TimePrismTodoCreateRequest(requestId, title, dueAt);
    }

    private static final class Fixture implements AutoCloseable {
        private final HttpServer server;
        private final AtomicLong authenticatedUserId = new AtomicLong(OWNER_ID);
        private final AtomicInteger loginCount = new AtomicInteger();
        private final AtomicInteger postCount = new AtomicInteger();
        private final AtomicInteger getCount = new AtomicInteger();
        private final AtomicBoolean stallNextPostBody = new AtomicBoolean();
        private final AtomicBoolean stallNextAuthBody = new AtomicBoolean();
        private final AtomicBoolean oversizeNextPostBody = new AtomicBoolean();
        private final AtomicBoolean failNextPostWithServerError = new AtomicBoolean();
        private final AtomicBoolean commitTodos = new AtomicBoolean(true);
        private final AtomicLong todoIds = new AtomicLong(700);
        private final java.util.concurrent.ConcurrentHashMap<String, JsonNode> todos = new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.concurrent.atomic.AtomicReference<Map<String, Object>> lastPost = new java.util.concurrent.atomic.AtomicReference<>();
        private final CountDownLatch stalledPostHeaders = new CountDownLatch(1);
        private final CountDownLatch releaseStalledPost = new CountDownLatch(1);
        private final CountDownLatch stalledAuthHeaders = new CountDownLatch(1);
        private final CountDownLatch releaseStalledAuth = new CountDownLatch(1);

        private Fixture() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/v1/auth/tokens", exchange -> {
                loginCount.incrementAndGet();
                JsonNode token = MAPPER.createObjectNode().put("resultType", "TOKEN_ISSUED")
                        .put("accessToken", "synthetic-access-token")
                        .put("userId", authenticatedUserId.get());
                if (stallNextAuthBody.compareAndSet(true, false)) {
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, 0);
                    stalledAuthHeaders.countDown();
                    try {
                        releaseStalledAuth.await(3, TimeUnit.SECONDS);
                        exchange.getResponseBody().write(MAPPER.writeValueAsBytes(token));
                    }
                    catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                    }
                    catch (IOException ignored) { }
                    finally { exchange.close(); }
                    return;
                }
                respond(exchange, 200, token);
            });
            server.createContext("/api/v1/light-apps/todos", this::todos);
            server.start();
        }

        private String origin() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private void todos(HttpExchange exchange) throws IOException {
            if ("GET".equals(exchange.getRequestMethod())) {
                getCount.incrementAndGet();
                var rows = MAPPER.createArrayNode();
                todos.values().forEach(rows::add);
                respond(exchange, 200, MAPPER.createObjectNode().set("data", rows));
                return;
            }
            postCount.incrementAndGet();
            JsonNode body = MAPPER.readTree(exchange.getRequestBody());
            Map<String, Object> fields = MAPPER.convertValue(body, MAPPER.getTypeFactory()
                    .constructMapType(Map.class, String.class, Object.class));
            lastPost.set(fields);
            JsonNode todo = MAPPER.createObjectNode()
                    .put("todoId", todoIds.incrementAndGet())
                    .put("title", body.path("title").asText())
                    .put("detail", body.path("detail").asText())
                    .put("dueAt", body.path("due_at").asText())
                    .put("done", body.path("done").asBoolean())
                    .put("reminderEnabled", body.path("reminder_enabled").asBoolean())
                    .put("timePrecision", body.path("time_precision").asText())
                    .put("timingMode", body.path("timing_mode").asText())
                    .put("deadlineRemindValue", body.path("deadline_remind_value").asInt())
                    .put("deadlineRemindUnit", body.path("deadline_remind_unit").asText());
            if (commitTodos.get()) todos.put(body.path("detail").asText(), todo);
            if (stallNextPostBody.compareAndSet(true, false)) {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, 0);
                stalledPostHeaders.countDown();
                try {
                    releaseStalledPost.await(3, TimeUnit.SECONDS);
                    exchange.getResponseBody().write("{}".getBytes(StandardCharsets.UTF_8));
                }
                catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
                catch (IOException ignored) {
                    // A correctly cancelled client may close the body before the fixture releases it.
                }
                finally { exchange.close(); }
                return;
            }
            if (failNextPostWithServerError.compareAndSet(true, false)) {
                respond(exchange, 500, MAPPER.createObjectNode().put("error", "synthetic failure"));
                return;
            }
            if (oversizeNextPostBody.compareAndSet(true, false)) {
                byte[] oversized = new byte[1_048_577];
                java.util.Arrays.fill(oversized, (byte) ' ');
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, oversized.length);
                exchange.getResponseBody().write(oversized);
                exchange.close();
                return;
            }
            respond(exchange, 200, MAPPER.createObjectNode().set("data", todo));
        }

        private static void respond(HttpExchange exchange, int status, JsonNode body) throws IOException {
            byte[] bytes = MAPPER.writeValueAsBytes(body);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        @Override public void close() {
            releaseStalledPost.countDown();
            releaseStalledAuth.countDown();
            server.stop(0);
        }
    }
}

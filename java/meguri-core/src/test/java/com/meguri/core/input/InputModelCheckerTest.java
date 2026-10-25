package com.meguri.core.input;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InputModelCheckerTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void distinctRouteSendsRawTextAndReturnsCheckedTextWithoutChangingRequest() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        startServer(exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes()));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"{\\\"checked_text\\\":\\\"请介绍 LangChain4j。\\\"}\"}}]}");
        });
        String raw = "讲讲 LangChain4j";
        InputModelChecker checker = checker("input-small-v1", 600, 1);

        InputModelChecker.Result result = checker.check("turn-a", raw,
                Instant.now().plusSeconds(2)).block();

        assertEquals(raw, result.rawText());
        assertEquals("请介绍 LangChain4j。", result.checkedText());
        assertTrue(body.get().contains("\"model\":\"input-small-v1\""));
        assertTrue(body.get().contains(raw));
        assertEquals("Bearer test-key", authorization.get());
        assertEquals("enabled", checker.status().state());
    }

    @Test
    void malformedRewriteOrChangedSensitiveFactsFallsBackToRaw() throws Exception {
        startServer(exchange -> respond(exchange, 200,
                "{\"choices\":[{\"message\":{\"content\":\"{\\\"checked_text\\\":\\\"请在 2026-10-20 10:00 打开 https://new.example。\\\"}\"}}]}"));
        InputModelChecker checker = checker("input-small-v1", 600, 1);

        var numberChanged = checker.check("turn-b", "明天10点提醒我", Instant.now().plusSeconds(2)).block();
        var sensitive = checker.check("turn-c", "不要联网，概括这段内容", Instant.now().plusSeconds(2)).block();

        assertEquals("明天10点提醒我", numberChanged.checkedText());
        assertEquals("不要联网，概括这段内容", sensitive.checkedText());
    }

    @Test
    void failedAndOversizedInputReturnOriginalAndLaterTurnCanRetry() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        startServer(exchange -> {
            calls.incrementAndGet();
            if (calls.get() == 1) respond(exchange, 503, "unavailable");
            else respond(exchange, 200, completion("请说明原理。"));
        });
        InputModelChecker checker = checker("input-small-v1", 600, 1);
        String raw = "说明原理";

        assertEquals(raw, checker.check("turn-d", raw, Instant.now().plusSeconds(2)).block().checkedText());
        assertEquals("x".repeat(8193), checker.check("turn-e", "x".repeat(8193), Instant.now().plusSeconds(2))
                .block().checkedText());
        assertEquals("请说明原理。", checker.check("turn-recovered", raw, Instant.now().plusSeconds(2)).block().checkedText());
        assertEquals(2, calls.get());
        assertFalse(checker.status().model().contains("key"));
    }

    @Test
    void routeIsExplicitlyDisabledWithoutModel() {
        InputModelChecker checker = new InputModelChecker(
                InputModelChecker.Config.disabled(), new ObjectMapper(),
                HttpClient.newHttpClient(), Clock.systemUTC());

        var result = checker.check("turn-f", "原始文本", Instant.now().plusSeconds(1)).block();

        assertEquals("原始文本", result.checkedText());
        assertEquals("disabled", checker.status().state());
        assertEquals("", checker.status().model());
    }

    @Test
    void invalidResponsesAndAddedOrRemovedFactsFallBack() throws Exception {
        AtomicReference<String> output = new AtomicReference<>("not-json");
        startServer(exchange -> respond(exchange, 200, output.get()));
        var checker = checker("input-small-v1", 1500, 1);
        String raw = "介绍版本33";
        for (String body : java.util.List.of("not-json", "{}", completion(""),
                completion("介绍版本34"), completion("介绍版本"), completion("删除版本33"),
                "{\"choices\":[{\"message\":{\"content\":\"{\\\"checked_text\\\":\\\"介绍版本33\\\",\\\"extra\\\":true}\"}}]}")) {
            output.set(body);
            var result = checker.check("invalid-turn", raw, Instant.now().plusSeconds(3)).block();
            assertEquals(raw, result.checkedText());
            assertEquals(InputModelChecker.Status.FALLBACK, result.status());
        }
        output.set(completion("今天考试"));
        assertEquals("明天考试", checker.check("changed-relative-date", "明天考试", Instant.now().plusSeconds(3)).block().checkedText());
    }

    @Test
    void actionAndRestrictionInputsBypassRewriting() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        startServer(exchange -> { calls.incrementAndGet(); respond(exchange, 200, completion("无关操作")); });
        var checker = checker("input-small-v1", 1500, 1);
        for (String raw : java.util.List.of("不要联网，介绍一下", "十分钟后提醒我", "cancel todo", "Do not browse", "创建待办")) {
            var result = checker.check("sensitive-turn", raw, Instant.now().plusSeconds(3)).block();
            assertEquals(raw, result.checkedText());
            assertEquals(InputModelChecker.Status.PASSTHROUGH, result.status());
        }
        assertEquals(0, calls.get());
    }

    @Test
    void saturatedInputRouteFallsBackWithoutBlockingFollowingTurn() throws Exception {
        var started = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        startServer(exchange -> {
            started.countDown();
            try { release.await(3, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            respond(exchange, 200, completion("请说明原理。"));
        });
        var checker = checker("input-small-v1", 2500, 1);
        var first = checker.check("held", "说明原理", Instant.now().plusSeconds(4)).toFuture();
        try {
            assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS));
            var second = checker.check("saturated", "说明原理", Instant.now().plusSeconds(3)).block();
            assertEquals(InputModelChecker.Status.FALLBACK, second.status());
            assertEquals("说明原理", second.checkedText());
        } finally { release.countDown(); }
        assertEquals("请说明原理。", first.get(3, java.util.concurrent.TimeUnit.SECONDS).checkedText());
        assertEquals("请说明原理。", checker.check("following", "说明原理", Instant.now().plusSeconds(3)).block().checkedText());
    }

    @Test
    void requestDeadlineAndCancellationReleaseInputRoute() throws Exception {
        var started = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        startServer(exchange -> {
            if (calls.incrementAndGet() == 1) {
                started.countDown();
                try { release.await(3, java.util.concurrent.TimeUnit.SECONDS); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            }
            try { respond(exchange, 200, completion("请说明原理。")); }
            catch (java.io.IOException expectedAfterCancellation) { exchange.close(); }
        });
        var checker = checker("input-small-v1", 2500, 1);
        var first = checker.check("cancelled", "说明原理", Instant.now().plusSeconds(4)).toFuture();
        try {
            assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(first.cancel(true));
        } finally { release.countDown(); }
        var next = checker.check("after-cancel", "说明原理", Instant.now().plusSeconds(3)).block();
        assertEquals("请说明原理。", next.checkedText());
        assertTrue(first.isCancelled());
        assertEquals(InputModelChecker.Status.PASSTHROUGH,
                checker.check("expired", "说明原理", Instant.now().minusSeconds(1)).block().status());
    }

    private static String completion(String checked) throws java.io.IOException {
        var mapper = new ObjectMapper();
        return mapper.writeValueAsString(Map.of("choices", java.util.List.of(Map.of("message",
                Map.of("content", mapper.writeValueAsString(Map.of("checked_text", checked)))))));
    }

    @Test
    void timedOutInputCannotReplaceTheNextTurnsClarification() throws Exception {
        var started = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var returned = new java.util.concurrent.CountDownLatch(1);
        startServer(exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (body.contains("等待返回")) {
                started.countDown();
                try { release.await(3, java.util.concurrent.TimeUnit.SECONDS); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                try { respond(exchange, 200, completion("迟到的结果")); }
                catch (java.io.IOException expectedAfterTimeout) { exchange.close(); }
                finally { returned.countDown(); }
            } else respond(exchange, 200, completion("请说明原理。"));
        });
        HttpClient http = HttpClient.newHttpClient();
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        var warm = new InputModelChecker(new InputModelChecker.Config(true, base, "small", "",
                Duration.ofSeconds(2), Duration.ofMillis(20), 1, 8192), new ObjectMapper(), http, Clock.systemUTC());
        assertEquals("请说明原理。", warm.check("warm", "说明原理", Instant.now().plusSeconds(3)).block().checkedText());
        var checker = new InputModelChecker(new InputModelChecker.Config(true, base, "small", "",
                Duration.ofMillis(200), Duration.ofMillis(20), 1, 8192), new ObjectMapper(), http, Clock.systemUTC());
        var old = checker.check("old", "等待返回", Instant.now().plusSeconds(3)).toFuture();
        try {
            assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(InputModelChecker.Status.FALLBACK, old.get(2, java.util.concurrent.TimeUnit.SECONDS).status());
            var next = checker.check("new", "说明原理", Instant.now().plusSeconds(3)).block();
            assertEquals("请说明原理。", next.checkedText());
            release.countDown();
            assertTrue(returned.await(2, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals("等待返回", old.get().checkedText());
            assertEquals("请说明原理。", next.checkedText());
        } finally { release.countDown(); }
    }

    @Test
    void stalledResponseBodyIsBoundedAfterHeadersArrive() throws Exception {
        var headers = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        startServer(exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (body.contains("挂起返回")) {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, 0);
                try (var output = exchange.getResponseBody()) {
                    output.write('{');
                    output.flush();
                    headers.countDown();
                    try { release.await(3, java.util.concurrent.TimeUnit.SECONDS); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                    output.write('}');
                } catch (java.io.IOException expectedAfterTimeout) { exchange.close(); }
            } else respond(exchange, 200, completion("请说明原理。"));
        });
        var http = HttpClient.newHttpClient();
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        var warm = new InputModelChecker(new InputModelChecker.Config(true, base, "small", "",
                Duration.ofSeconds(2), Duration.ofMillis(20), 1, 8192), new ObjectMapper(), http, Clock.systemUTC());
        assertEquals("请说明原理。", warm.check("warm", "说明原理", Instant.now().plusSeconds(3)).block().checkedText());
        var checker = new InputModelChecker(new InputModelChecker.Config(true, base, "small", "",
                Duration.ofMillis(200), Duration.ofMillis(100), 1, 8192), new ObjectMapper(), http, Clock.systemUTC());
        var old = checker.check("stalled-body", "挂起返回", Instant.now().plusSeconds(3)).toFuture();
        try {
            assertTrue(headers.await(2, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(InputModelChecker.Status.FALLBACK, old.get(1, java.util.concurrent.TimeUnit.SECONDS).status());
            assertEquals("请说明原理。", checker.check("after-stalled-body", "说明原理", Instant.now().plusSeconds(3)).block().checkedText());
        } finally { release.countDown(); }
    }

    private InputModelChecker checker(String model, int timeoutMs, int maxConcurrency) {
        return new InputModelChecker(new InputModelChecker.Config(
                true, "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                model, "test-key", Duration.ofMillis(timeoutMs), Duration.ofMillis(20),
                maxConcurrency, 8192), new ObjectMapper(), HttpClient.newHttpClient(), Clock.systemUTC());
    }

    private void startServer(com.sun.net.httpserver.HttpHandler handler) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.ForkJoinPool.commonPool());
        server.createContext("/v1/chat/completions", handler);
        server.start();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
}

package com.meguri.core.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.dto.EventMetadata;
import com.meguri.core.dto.ChatResponse;
import com.meguri.core.dto.TurnRequest;
import com.meguri.core.capability.CapabilityDescriptor;
import com.meguri.core.capability.JdbcCapabilityRuntimeStore;
import com.meguri.core.memory.job.JdbcPostReplyMemoryJobStore;
import com.meguri.core.memory.job.PostReplyMemoryJob;
import com.meguri.core.memory.job.PostReplyMemoryJobEnqueuer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.io.CleanupMode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Opt-in black-box HTTP/SSE acceptance across three full Spring Core JVMs. */
@EnabledIfEnvironmentVariable(named = "MEGURI_RECOVERY_TEST_DSN_FILE", matches = ".+")
class PostgresCoreProcessRestartIntegrationTest {
    @TempDir(cleanup = CleanupMode.ON_SUCCESS) Path fixture;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private int port;

    @Test
    void fullCoreRestartsPreserveHttpReplayContextQueuePendingAndReceipts() throws Exception {
        var uri = URI.create(Files.readString(Path.of(System.getenv("MEGURI_RECOVERY_TEST_DSN_FILE"))).trim()
                .replace("postgresql+asyncpg://", "postgresql://"));
        String[] auth = uri.getRawUserInfo().split(":", 2);
        String host = uri.getHost().contains(":") ? "[" + uri.getHost() + "]" : uri.getHost();
        String url = "jdbc:postgresql://" + host + ":" + (uri.getPort() < 0 ? 5432 : uri.getPort()) + uri.getRawPath();
        var admin = new JdbcTemplate(source(url, auth));
        String schema = "meguri_p0_core_" + UUID.randomUUID().toString().replace("-", "");
        assertThat(schema).matches("meguri_p0_core_[a-f0-9]{32}");
        assertThat(admin.queryForObject("SELECT count(*) FROM pg_namespace WHERE nspname = ?", Integer.class, schema)).isZero();
        Files.writeString(fixture.resolve("token.txt"), "isolated-p0-core-test-token");
        Process core = null;
        boolean created = false;
        try {
            admin.execute("CREATE SCHEMA \"" + schema + "\"");
            created = true;
            var dataSource = source(url + "?currentSchema=" + schema, auth);
            var jdbc = new JdbcTemplate(dataSource);
            assertThat(jdbc.queryForObject("SELECT current_schema()", String.class)).isEqualTo(schema);
            core = startCore(schema, false, 1);
            assertDescriptorSetOrderSurvives(jdbc);
            var request = Map.of("user_id", "core-fixture-user", "client_id", "desktop_pet",
                    "session_id", "core-fixture-session", "message", "restart fixture conversation",
                    "training_mode", true, "formal_memory_allowed", false, "retrieval_mode", "NONE");
            JsonNode acceptance = json("POST", "/v1/turns", request, "core-fixture-key", 202);
            String turnId = acceptance.path("turn_id").asText();
            assertThat(turnId).isNotBlank();
            await(() -> status(turnId).path("status").asText().equals("completed"), "first completed HTTP Turn");
            // Formal-memory consent is absent, so this real conversation must not enqueue a write.
            assertThat(jdbc.queryForObject("SELECT count(*) FROM post_reply_memory_job WHERE turn_id = ?",
                    Integer.class, turnId)).isZero();
            JsonNode snapshot = json("GET", "/v1/sessions/core-fixture-session/snapshot", null, null, 200);
            var beforeEvents = replay("core-fixture-session", 0, 0, jdbc.queryForObject(
                    "SELECT count(*) FROM turn_event WHERE session_id = ?", Integer.class, "core-fixture-session"));
            // The deterministic mock produces identical alternatives; supervision falls back to a single pending observation.
            assertThat(beforeEvents).extracting(event -> event.path("type").asText()).contains("turn.completed");
            JsonNode pending = json("GET", "/v1/training/desktop/pending", null, null, 200);
            assertThat(pending.path("pending").size()).isEqualTo(1);
            var oldWorker = new JdbcPostReplyMemoryJobStore(jdbc, mapper);
            // Seed a synthetic queued operation using the real persisted request/response;
            // the new Core must reclaim it, then skip its write because consent is absent.
            String completedEvent = jdbc.queryForObject("SELECT event_id FROM turn_event WHERE turn_id = ? AND event_type = 'turn.completed'",
                    String.class, turnId);
            var storedRequest = mapper.readValue(jdbc.queryForObject("SELECT request_json::text FROM turn_runtime WHERE turn_id = ?",
                    String.class, turnId), TurnRequest.class);
            var storedResponse = mapper.readValue(jdbc.queryForObject("SELECT result_json::text FROM turn_runtime WHERE turn_id = ?",
                    String.class, turnId), ChatResponse.class);
            new PostReplyMemoryJobEnqueuer(oldWorker, mapper, java.time.Clock.systemUTC()).enqueueFromEvent(
                    completedEvent, turnId, storedRequest, storedResponse.response(), "fixture-job-trace", false,
                    PostReplyMemoryJob.CancellationPolicy.PROCESS_COMPLETED_REPLY);
            var claimed = oldWorker.claim("fixture-old-worker", 1, Duration.ofSeconds(30), Instant.now()).getFirst();
            stopCore(core);
            core = null;
            jdbc.update("UPDATE post_reply_memory_job SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE job_id = ?",
                    claimed.jobId());
            var journal = new PostgresTurnJournal(jdbc, mapper,
                    new TransactionTemplate(new DataSourceTransactionManager(dataSource)), "fixture-interruption");
            var partial = journal.accept(new TurnRequest("core-fixture-user", "desktop_pet", "partial-fixture-session", "partial question"),
                    "partial-core-key", Instant.now().plusSeconds(300)).record();
            assertThat(journal.claimExecution(partial, "fixture-lost-provider", Duration.ofSeconds(30))).isTrue();
            partial.transitionTo(TurnStage.GENERATING);
            journal.appendExecution(partial, "fixture-lost-provider", "text.delta", Map.of("delta", "persisted partial", "index", 1),
                    new EventMetadata(partial.getTraceId(), "fixture", Instant.now(), "fixture"));
            jdbc.update("UPDATE turn_runtime SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE turn_id = ?",
                    partial.getTurnId());

            core = startCore(schema, true, 2);
            assertThat(mapper.readTree(fixture.resolve("ready.json").toFile()).path("restored_context_messages").asInt()).isEqualTo(2);
            assertThat(json("POST", "/v1/turns", request, "core-fixture-key", 202).path("turn_id").asText()).isEqualTo(turnId);
            assertThat(status(turnId).path("status").asText()).isEqualTo("completed");
            var restoredSnapshot = json("GET", "/v1/sessions/core-fixture-session/snapshot", null, null, 200);
            // Snapshot creation time is request metadata, rather than persisted conversation state.
            ((com.fasterxml.jackson.databind.node.ObjectNode) snapshot).remove("created_at");
            ((com.fasterxml.jackson.databind.node.ObjectNode) restoredSnapshot).remove("created_at");
            assertThat(restoredSnapshot).isEqualTo(snapshot);
            assertThat(replay("core-fixture-session", 0, 0, beforeEvents.size())).isEqualTo(beforeEvents);
            long cursor = beforeEvents.getFirst().path("sequence").asLong();
            assertThat(replay("core-fixture-session", 0, cursor, beforeEvents.size() - 1))
                    .isEqualTo(beforeEvents.subList(1, beforeEvents.size()));
            assertThat(json("GET", "/v1/training/desktop/pending", null, null, 200)).isEqualTo(pending);
            assertThat(status(partial.getTurnId(), "partial-fixture-session").path("status").asText()).isEqualTo("failed");
            var partialEvents = replay("partial-fixture-session", 0, 0, 2);
            assertThat(partialEvents).extracting(event -> event.path("type").asText()).containsExactly("text.delta", "turn.failed");
            assertThat(partialEvents.getFirst().path("data").path("delta").asText()).isEqualTo("persisted partial");
            assertThat(partialEvents.getLast().path("data").toString()).contains("PROVIDER_STREAM_INTERRUPTED");
            await(() -> "SKIPPED".equals(jdbc.queryForObject("SELECT status FROM post_reply_memory_job WHERE job_id = ?",
                    String.class, claimed.jobId())), "expired lease recovered by new Core worker");
            assertThat(jdbc.queryForObject("SELECT attempts FROM post_reply_memory_job WHERE job_id = ?", Integer.class, claimed.jobId())).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT response_digest FROM post_reply_memory_job WHERE job_id = ?", String.class, claimed.jobId()))
                    .isEqualTo(claimed.responseDigest());
            assertThat(oldWorker.acknowledge(claimed.jobId(), "fixture-old-worker", PostReplyMemoryJob.Status.SUCCEEDED, Instant.now())).isFalse();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM post_reply_memory_job WHERE turn_id = ?", Integer.class, turnId)).isEqualTo(1);
            String candidate = pending.path("pending").get(0).path("candidates").get(0).path("candidate_id").asText();
            var feedback = Map.of("turn_id", turnId, "selected_candidate_id", candidate, "consent_to_training", true);
            JsonNode receipt = json("POST", "/v1/training/desktop/feedback", feedback, "core-feedback-key", 201);
            String feedbackId = receipt.path("feedback_id").asText();
            assertThat(feedbackId).isNotBlank();
            stopCore(core);
            core = null;

            core = startCore(schema, true, 3);
            assertThat(json("POST", "/v1/training/desktop/feedback", feedback, "core-feedback-key", 201).path("feedback_id").asText())
                    .isEqualTo(feedbackId);
            JsonNode recovered = json("GET", "/v1/training/desktop/pending", null, null, 200);
            assertThat(recovered.path("pending").size()).isZero();
            assertThat(recovered.path("receipts").get(0).path("feedback_id").asText()).isEqualTo(feedbackId);
            assertThat(recovered.path("receipts").get(0).path("status").asText()).isEqualTo("training_candidate_saved");
            json("POST", "/v1/training/desktop/feedback/" + feedbackId + "/revoke", Map.of(), "core-revoke-key", 200);
            assertThat(json("GET", "/v1/training/desktop/pending", null, null, 200).path("receipts").get(0).path("status").asText())
                    .isEqualTo("revoked");
            assertThat(Files.readAllLines(fixture.resolve("feedback.jsonl"))).hasSize(2);
            assertThat(replay("partial-fixture-session", 0, 0, 2)).isEqualTo(partialEvents);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM turn_runtime WHERE session_id = ?", Integer.class, "core-fixture-session"))
                    .isEqualTo(1);
            // A recovered session must remain writable, not merely replayable.
            JsonNode resumed = json("POST", "/v1/turns", Map.of(
                    "user_id", "core-fixture-user", "client_id", "desktop_pet",
                    "session_id", "core-fixture-session", "message", "follow up after process restart",
                    "formal_memory_allowed", false, "retrieval_mode", "NONE"), "resumed-dialogue-key", 202);
            String resumedId = resumed.path("turn_id").asText();
            await(() -> status(resumedId).path("status").asText().equals("completed"), "resumed session completed a new turn");
            var contextProjection = new PostgresSessionContextPersistence(jdbc, mapper);
            var resumedContext = contextProjection.load("core-fixture-user", "desktop_pet", "core-fixture-session").orElseThrow();
            assertThat(resumedContext.activePath()).hasSize(4);
            assertThat(resumedContext.activePath()).extracting(SessionContextStore.MessageNode::content)
                    .contains("restart fixture conversation", "follow up after process restart");
            JsonNode separate = json("POST", "/v1/turns", Map.of(
                    "user_id", "core-fixture-user", "client_id", "desktop_pet",
                    "session_id", "separate-dialogue-session", "message", "independent conversation after restart",
                    "formal_memory_allowed", false, "retrieval_mode", "NONE"), "separate-dialogue-key", 202, "separate-dialogue-session");
            String separateId = separate.path("turn_id").asText();
            await(() -> status(separateId, "separate-dialogue-session").path("status").asText().equals("completed"), "independent session completed a new turn");
            var separateContext = contextProjection.load("core-fixture-user", "desktop_pet", "separate-dialogue-session").orElseThrow();
            assertThat(separateContext.activePath()).hasSize(2);
            assertThat(separateContext.activePath()).extracting(SessionContextStore.MessageNode::content)
                    .doesNotContain("restart fixture conversation", "follow up after process restart");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM post_reply_memory_job WHERE turn_id IN (?, ?)",
                    Integer.class, resumedId, separateId)).isZero();
            System.out.println("P0 full Core recovery: three JVM starts; authenticated HTTP/idempotency/SSE cursor/context/pending/receipt/withdrawal/partial failure/job lease PASS; schema=" + schema);
        } finally {
            if (core != null) stopCore(core);
            if (created) {
                assertThat(schema).matches("meguri_p0_core_[a-f0-9]{32}");
                admin.execute("DROP SCHEMA \"" + schema + "\" CASCADE");
                assertThat(admin.queryForObject("SELECT count(*) FROM pg_namespace WHERE nspname = ?", Integer.class, schema)).isZero();
            }
        }
    }

    private Process startCore(String schema, boolean memoryWorker, int generation) throws Exception {
        Files.deleteIfExists(fixture.resolve("ready.json"));
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-Duser.home=" + fixture, "-Djdk.net.unixdomain.tmpdir=" + Path.of("../../tmp").toAbsolutePath().normalize(),
                "-cp", System.getProperty("java.class.path"), PostgresCoreProcessProbe.class.getName(),
                schema, fixture.toString(), Boolean.toString(memoryWorker))
                .redirectErrorStream(true).redirectOutput(fixture.resolve("core-" + generation + ".log").toFile());
        // Never inherit live provider, bridge, user-data or datasource configuration into a fixture Core.
        builder.environment().keySet().removeIf(key -> {
            String normalized = key.toUpperCase(java.util.Locale.ROOT);
            return normalized.startsWith("MEGURI_") || normalized.startsWith("SPRING_")
                    || normalized.equals("JAVA_TOOL_OPTIONS") || normalized.equals("JDK_JAVA_OPTIONS")
                    || normalized.equals("_JAVA_OPTIONS");
        });
        builder.environment().put("MEGURI_RECOVERY_TEST_DSN_FILE", System.getenv("MEGURI_RECOVERY_TEST_DSN_FILE"));
        Process process = builder.start();
        try {
            await(() -> {
                if (!process.isAlive()) throw new IllegalStateException("Fixture Core exited; inspect " + fixture.resolve("core-" + generation + ".log"));
                return Files.isRegularFile(fixture.resolve("ready.json"));
            }, "Core " + generation + " readiness", 60);
            var ready = mapper.readTree(fixture.resolve("ready.json").toFile());
            port = ready.path("port").asInt();
            assertThat(json("GET", "/health", null, null, 200).path("llm_provider").asText()).isEqualTo("mock");
            System.out.println("Full Core fixture " + generation + " pid=" + ready.path("pid").asLong() + " HTTP ready");
            return process;
        } catch (Throwable error) {
            stopCore(process);
            throw error;
        }
    }

    private void assertDescriptorSetOrderSurvives(JdbcTemplate jdbc) throws Exception {
        String stored = jdbc.queryForObject("SELECT descriptor::text FROM capability_definition WHERE capability_id = ? AND version = '1'",
                String.class, "meguri.prompt.default");
        var descriptor = mapper.readValue(stored, CapabilityDescriptor.class);
        var json = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(stored);
        var modes = new ArrayList<JsonNode>();
        json.path("allowedModes").forEach(modes::add);
        java.util.Collections.reverse(modes);
        var reversed = json.putArray("allowedModes");
        modes.forEach(reversed::add);
        jdbc.update("UPDATE capability_definition SET descriptor = ?::jsonb WHERE capability_id = ? AND version = '1'",
                mapper.writeValueAsString(json), descriptor.id());
        var store = new JdbcCapabilityRuntimeStore(jdbc, mapper);
        // A previous JVM may serialize a Set in a different order. It is still the same immutable definition.
        store.saveDefinition(descriptor);
        assertThatThrownBy(() -> store.saveDefinition(descriptor.withHealth(CapabilityDescriptor.Health.DEGRADED)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("different metadata");
    }

    private void stopCore(Process process) throws Exception {
        process.destroyForcibly();
        assertThat(process.waitFor(15, TimeUnit.SECONDS)).as("isolated Core stopped before restart/cleanup").isTrue();
    }

    private JsonNode status(String turnId) { return status(turnId, "core-fixture-session"); }
    private JsonNode status(String turnId, String session) {
        try { return json("GET", "/v1/turns/" + turnId, null, null, 200, session); }
        catch (Exception error) { throw new IllegalStateException("fixture HTTP status check failed", error); }
    }

    private JsonNode json(String method, String path, Object body, String key, int expected) throws Exception {
        return json(method, path, body, key, expected, "core-fixture-session");
    }
    private JsonNode json(String method, String path, Object body, String key, int expected, String session) throws Exception {
        var builder = request(path, session).header("Content-Type", "application/json");
        if (key != null) builder.header("Idempotency-Key", key);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(method + " " + path + " fixture response: " + response.body()).isEqualTo(expected);
        return mapper.readTree(response.body());
    }

    private List<JsonNode> replay(String session, long after, long lastEvent, int count) throws Exception {
        var builder = request("/v1/sessions/" + session + "/events?after_sequence=" + after, session)
                .header("Accept", "text/event-stream");
        if (lastEvent > 0) builder.header("Last-Event-ID", Long.toString(lastEvent));
        var response = http.send(builder.GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        try (var input = response.body()) {
            var read = CompletableFuture.supplyAsync(() -> {
                var events = new ArrayList<JsonNode>();
                try {
                    var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
                    String line;
                    while (events.size() < count && (line = reader.readLine()) != null) {
                        if (line.startsWith("data:")) events.add(mapper.readTree(line.substring(5).trim()));
                    }
                    return events;
                } catch (Exception error) { throw new IllegalStateException("fixture SSE read failed", error); }
            });
            var result = read.get(15, TimeUnit.SECONDS);
            assertThat(result).hasSize(count);
            return result;
        }
    }

    private HttpRequest.Builder request(String path, String session) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer isolated-p0-core-test-token")
                .header("X-Meguri-Tenant-ID", "meguri-local").header("X-Meguri-User-ID", "core-fixture-user")
                .header("X-Meguri-Client-ID", "desktop_pet").header("X-Meguri-Session-ID", session);
    }

    private void await(BooleanSupplier condition, String name) throws Exception { await(condition, name, 20); }
    private void await(BooleanSupplier condition, String name, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(100);
        assertThat(condition.getAsBoolean()).as(name).isTrue();
    }
    private DriverManagerDataSource source(String url, String[] auth) {
        return new DriverManagerDataSource(url, URLDecoder.decode(auth[0], StandardCharsets.UTF_8),
                URLDecoder.decode(auth[1], StandardCharsets.UTF_8));
    }
}

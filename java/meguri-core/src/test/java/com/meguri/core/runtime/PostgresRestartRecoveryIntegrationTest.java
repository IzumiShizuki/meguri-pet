package com.meguri.core.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.dto.*;
import com.meguri.core.memory.job.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.support.TransactionTemplate;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** Opt-in real PostgreSQL component reconstruction; never runs against public runtime tables. */
@EnabledIfEnvironmentVariable(named = "MEGURI_RECOVERY_TEST_DSN_FILE", matches = ".+")
class PostgresRestartRecoveryIntegrationTest {
    @TempDir Path processFixture;
    @Test
    void isolatedPostgresPreservesCompletedReplayContextPartialTextAndJobIdentity() throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var uri = URI.create(Files.readString(Path.of(System.getenv("MEGURI_RECOVERY_TEST_DSN_FILE")))
                .trim().replace("postgresql+asyncpg://", "postgresql://"));
        assertThat(uri.getScheme()).isIn("postgresql", "postgres");
        String[] auth = uri.getRawUserInfo().split(":", 2);
        String host = uri.getHost().contains(":") ? "[" + uri.getHost() + "]" : uri.getHost();
        String baseUrl = "jdbc:postgresql://" + host + ":" + (uri.getPort() < 0 ? 5432 : uri.getPort()) + uri.getRawPath();
        String schema = "meguri_p0_recovery_" + UUID.randomUUID().toString().replace("-", "");
        assertThat(schema).matches("meguri_p0_recovery_[a-f0-9]{32}");
        var admin = source(baseUrl, auth);
        var adminJdbc = new JdbcTemplate(admin);
        assertThat(adminJdbc.queryForObject("SELECT count(*) FROM pg_namespace WHERE nspname = ?", Integer.class, schema)).isZero();
        boolean created = false;
        try {
            adminJdbc.execute("CREATE SCHEMA \"" + schema + "\"");
            created = true;
            var dataSource = source(baseUrl + "?currentSchema=" + schema, auth);
            var jdbc = new JdbcTemplate(dataSource);
            assertThat(jdbc.queryForObject("SELECT current_schema()", String.class)).isEqualTo(schema);
            String version = jdbc.queryForObject("SHOW server_version", String.class);
            var transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
            var original = new PostgresTurnJournal(jdbc, mapper, transactions, "p0-fixture");
            var sessions = new SessionContextStore(20, new PostgresSessionContextPersistence(jdbc, mapper));
            var request = new TurnRequest("fixture-user", "desktop_pet", "fixture-session", "fixture message");
            var completed = original.accept(request, "fixture-key", Instant.now().plusSeconds(300)).record();
            original.append(completed, "text.delta", Map.of("delta", "persisted answer", "index", 1), metadata(completed));
            var state = new RuntimeState("desktop_pet", Mode.PRIVATE, Relationship.SIBLING, "default", "08:00",
                    false, false, false, List.of(ExpressionTag.NEUTRAL));
            assertThat(completed.tryComplete(new ChatResponse(completed.getTurnId(), request.sessionId(),
                    new LlmResponse("persisted answer"), state, new ResolvedExpression(ExpressionTag.NEUTRAL, Intensity.LOW, "default"),
                    MemoryStatus.PENDING, "p0-fixture"))).isTrue();
            var completedEvent = original.append(completed, "turn.completed", Map.of("reply", "persisted answer"), metadata(completed));
            sessions.appendNode(request.userId(), request.clientId(), request.sessionId(), "fixture-user-node", null,
                    new SessionContextStore.Message("user", request.message()));
            sessions.appendNode(request.userId(), request.clientId(), request.sessionId(), "fixture-assistant-node", "fixture-user-node",
                    new SessionContextStore.Message("assistant", "persisted answer"));
            var before = original.events(request.sessionId());

            // Reconstruct stores with no provider or tool implementation available to call.
            var reconstructed = new PostgresTurnJournal(jdbc, mapper, transactions, "p0-reconstructed");
            var replay = reconstructed.accept(request, "fixture-key", Instant.now().plusSeconds(300));
            assertThat(replay.created()).isFalse();
            assertThat(replay.record().getTurnId()).isEqualTo(completed.getTurnId());
            assertThat(replay.record().getStatus()).isEqualTo(TurnStatus.COMPLETED);
            assertThat(reconstructed.events(request.sessionId())).extracting(EventEnvelope::getEventId).containsExactlyElementsOf(
                    before.stream().map(EventEnvelope::getEventId).toList());
            assertThat(reconstructed.events(request.sessionId())).extracting(EventEnvelope::getSequence).containsExactlyElementsOf(
                    before.stream().map(EventEnvelope::getSequence).toList());
            var restoredSessions = new SessionContextStore(20, new PostgresSessionContextPersistence(jdbc, mapper));
            assertThat(restoredSessions.recent(request.userId(), request.clientId(), request.sessionId()))
                    .extracting(SessionContextStore.Message::content).containsExactly("fixture message", "persisted answer");

            var interrupted = reconstructed.accept(new TurnRequest("fixture-user", "desktop_pet", "partial-session", "next"),
                    "partial-key", Instant.now().plusSeconds(300)).record();
            assertThat(reconstructed.claimExecution(interrupted, "fixture-owner", Duration.ofSeconds(30))).isTrue();
            interrupted.transitionTo(TurnStage.GENERATING);
            reconstructed.appendExecution(interrupted, "fixture-owner", "text.delta", Map.of("delta", "partial text", "index", 1), metadata(interrupted));
            jdbc.update("UPDATE turn_runtime SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE turn_id = ?", interrupted.getTurnId());
            var recovered = new PostgresTurnJournal(jdbc, mapper, transactions, "p0-recovery");
            assertThat(recovered.turn(interrupted.getTurnId()).getFailureCode()).isEqualTo("PROVIDER_STREAM_INTERRUPTED");
            assertThat(recovered.events("partial-session")).extracting(EventEnvelope::getType).containsExactly("text.delta", "turn.failed");
            assertThat(recovered.events("partial-session").getFirst().data()).containsEntry("delta", "partial text");
            var onceMore = new PostgresTurnJournal(jdbc, mapper, transactions, "p0-recovery-again");
            assertThat(onceMore.events("partial-session")).hasSize(2);

            new ResourceDatabasePopulator(new ClassPathResource("db/post-reply-memory-job.sql")).execute(dataSource);
            var jobStore = new JdbcPostReplyMemoryJobStore(jdbc, mapper);
            var enqueuer = new PostReplyMemoryJobEnqueuer(jobStore, mapper, Clock.systemUTC());
            var delivery = new PostReplyMemoryOutboxDelivery(recovered, enqueuer);
            var claimed = recovered.claimOutbox("fixture-outbox-a", 64, Duration.ofSeconds(30));
            assertThat(claimed).extracting(item -> item.event().getEventId()).contains(completedEvent.getEventId());
            delivery.deliver(completedEvent.getEventId(), completedEvent);
            jdbc.update("UPDATE turn_outbox SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE claim_owner = ?", "fixture-outbox-a");
            var reclaimed = onceMore.claimOutbox("fixture-outbox-b", 64, Duration.ofSeconds(30));
            assertThat(reclaimed).extracting(item -> item.event().getEventId()).contains(completedEvent.getEventId());
            new PostReplyMemoryOutboxDelivery(onceMore, new PostReplyMemoryJobEnqueuer(
                    new JdbcPostReplyMemoryJobStore(jdbc, mapper), mapper, Clock.systemUTC())).deliver(completedEvent.getEventId(), completedEvent);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM post_reply_memory_job", Integer.class)).isEqualTo(1);
            var firstClaim = jobStore.claim("fixture-worker-a", 1, Duration.ofSeconds(30), Instant.now()).getFirst();
            jdbc.update("UPDATE post_reply_memory_job SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE job_id = ?", firstClaim.jobId());
            var secondClaim = new JdbcPostReplyMemoryJobStore(jdbc, mapper).claim("fixture-worker-b", 1, Duration.ofSeconds(30), Instant.now()).getFirst();
            assertThat(secondClaim.jobId()).isEqualTo(firstClaim.jobId());
            assertThat(secondClaim.responseDigest()).isEqualTo(firstClaim.responseDigest());
            assertThat(secondClaim.attempts()).isEqualTo(2);
            assertThat(jobStore.acknowledge(firstClaim.jobId(), "fixture-worker-a", PostReplyMemoryJob.Status.SUCCEEDED, Instant.now())).isFalse();
            assertThat(jobStore.acknowledge(secondClaim.jobId(), "fixture-worker-b", PostReplyMemoryJob.Status.SUCCEEDED, Instant.now())).isTrue();
            assertThat(onceMore.turn(completed.getTurnId()).getResult().response().reply()).isEqualTo("persisted answer");
            runFreshProcess(schema, "write");
            runFreshProcess(schema, "verify");
            System.out.println("P0 recovery evidence: PostgreSQL " + version + "; isolated schema=" + schema
                    + "; store reconstruction plus independent writer/reader JVMs; completed replay/context/partial failure/outbox/job identities and pending feedback PASS.");
        } finally {
            if (created) {
                assertThat(schema).matches("meguri_p0_recovery_[a-f0-9]{32}");
                adminJdbc.execute("DROP SCHEMA \"" + schema + "\" CASCADE");
                assertThat(adminJdbc.queryForObject("SELECT count(*) FROM pg_namespace WHERE nspname = ?", Integer.class, schema)).isZero();
            }
        }
    }

    private void runFreshProcess(String schema, String phase) throws Exception {
        var javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        Path output = processFixture.resolve(phase + ".log");
        var process = new ProcessBuilder(javaExecutable, "-Djdk.net.unixdomain.tmpdir=" + processFixture,
                "-cp", System.getProperty("java.class.path"), PostgresRecoveryProcessProbe.class.getName(),
                schema, phase, processFixture.toString())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertThat(process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)).as("fresh JVM " + phase).isTrue();
            assertThat(process.exitValue()).as("fresh JVM " + phase + ": " + Files.readString(output)).isZero();
            System.out.println(Files.readString(output).trim());
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private static DriverManagerDataSource source(String url, String[] auth) {
        return new DriverManagerDataSource(url, URLDecoder.decode(auth[0], StandardCharsets.UTF_8),
                URLDecoder.decode(auth[1], StandardCharsets.UTF_8));
    }
    private static EventMetadata metadata(TurnRecord record) {
        return new EventMetadata(record.getTraceId(), "p0-fixture", Instant.now(), "p0-fixture");
    }
}

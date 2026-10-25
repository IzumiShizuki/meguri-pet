package com.meguri.core.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.dto.*;
import com.meguri.core.memory.DesktopMemoryIdentity;
import com.meguri.core.training.TrainingFeedbackService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Fixture writer exits before a separate JVM reads its persisted state. */
public final class PostgresRecoveryProcessProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !args[0].matches("meguri_p0_recovery_[a-f0-9]{32}")) throw new IllegalArgumentException("isolated schema required");
        var uri = URI.create(Files.readString(Path.of(System.getenv("MEGURI_RECOVERY_TEST_DSN_FILE"))).trim()
                .replace("postgresql+asyncpg://", "postgresql://"));
        String[] auth = uri.getRawUserInfo().split(":", 2);
        String host = uri.getHost().contains(":") ? "[" + uri.getHost() + "]" : uri.getHost();
        var source = new DriverManagerDataSource("jdbc:postgresql://" + host + ":" + (uri.getPort() < 0 ? 5432 : uri.getPort())
                + uri.getRawPath() + "?currentSchema=" + args[0], URLDecoder.decode(auth[0], StandardCharsets.UTF_8),
                URLDecoder.decode(auth[1], StandardCharsets.UTF_8));
        var jdbc = new JdbcTemplate(source);
        require(args[0].equals(jdbc.queryForObject("SELECT current_schema()", String.class)), "isolated search path");
        var mapper = new ObjectMapper().findAndRegisterModules();
        var journal = new PostgresTurnJournal(jdbc, mapper, new TransactionTemplate(new DataSourceTransactionManager(source)), "fresh-process");
        var sessions = new SessionContextStore(20, new PostgresSessionContextPersistence(jdbc, mapper));
        var feedback = new TrainingFeedbackService(mapper, Path.of(args[2], "feedback.jsonl"), 0, 0, () -> 1);
        var request = new TurnRequest("process-user", "desktop_pet", "process-session", "process question").withTenantId("meguri-local");
        var acceptance = journal.accept(request, "process-restart-key", Instant.now().plusSeconds(300));
        var turn = acceptance.record();
        if ("write".equals(args[1])) {
            require(acceptance.created(), "new writer turn");
            var state = new RuntimeState("desktop_pet", Mode.PRIVATE, Relationship.SIBLING, "default", "08:00",
                    false, false, false, List.of(ExpressionTag.NEUTRAL));
            require(turn.tryComplete(new ChatResponse(turn.getTurnId(), request.sessionId(), new LlmResponse("process answer"), state,
                    new ResolvedExpression(ExpressionTag.NEUTRAL, Intensity.LOW, "default"), MemoryStatus.PENDING, "fixture")), "completed writer");
            journal.append(turn, "turn.completed", Map.of("reply", "process answer"), new EventMetadata(turn.getTraceId(), "fixture", Instant.now(), "fixture"));
            sessions.appendNode(request.userId(), request.clientId(), request.sessionId(), "process-message", null,
                    new SessionContextStore.Message("assistant", "process answer"));
            feedback.registerSingle(turn.getTurnId(), request, List.of(), new LlmResponse("process answer"), "fixture", "fixture");
            Files.writeString(Path.of(args[2], "turn-id.txt"), turn.getTurnId());
        } else if ("verify".equals(args[1])) {
            require(!acceptance.created() && turn.getStatus() == TurnStatus.COMPLETED, "same completed turn");
            require(turn.getTurnId().equals(Files.readString(Path.of(args[2], "turn-id.txt"))), "stable turn identity");
            require(journal.events(request.sessionId()).size() == 1 && journal.events(request.sessionId()).getFirst().getSequence() == 1, "replayed event");
            require(sessions.recent(request.userId(), request.clientId(), request.sessionId()).getFirst().content().equals("process answer"), "restored context");
            var pending = feedback.pending(new DesktopMemoryIdentity.Scope("meguri-local", request.userId(), request.clientId(), request.sessionId(), false));
            require(((List<?>) pending.get("pending")).size() == 1, "restored pending feedback");
        } else throw new IllegalArgumentException("unsupported fixture phase");
        System.out.println("Fresh JVM " + args[1] + " pid=" + ProcessHandle.current().pid() + " PASS");
    }
    private static void require(boolean condition, String check) {
        if (!condition) throw new IllegalStateException(check);
    }
}

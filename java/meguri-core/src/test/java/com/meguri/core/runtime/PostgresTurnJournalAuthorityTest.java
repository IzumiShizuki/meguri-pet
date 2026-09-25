package com.meguri.core.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.dto.TurnRequest;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PostgresTurnJournalAuthorityTest {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @Test
    void turnAndTurnsRefreshExistingRecordsFromPostgres() {
        StubJdbcTemplate jdbc = new StubJdbcTemplate();
        PostgresTurnJournal journal = journal(jdbc);
        TurnRecord accepted = record("turn-shared", TurnStatus.ACCEPTED, TurnStage.CREATED, 1);
        jdbc.authoritative = List.of(accepted);

        TurnRecord cached = journal.turn("turn-shared");
        assertThat(cached.getStatus()).isEqualTo(TurnStatus.ACCEPTED);

        TurnRecord remotelyFailed = record("turn-shared", TurnStatus.FAILED, TurnStage.FAILED, 7);
        jdbc.authoritative = List.of(remotelyFailed);

        assertThat(journal.turn("turn-shared")).isSameAs(cached);
        assertThat(cached.getStatus()).isEqualTo(TurnStatus.FAILED);
        assertThat(cached.getVersion()).isEqualTo(7);
        assertThat(journal.turns()).containsOnlyKeys("turn-shared");
        assertThat(journal.turns().get("turn-shared")).isSameAs(cached);

        cached.transitionTo(TurnStage.FAILED);
        jdbc.authoritative = List.of(record("turn-shared", TurnStatus.ACCEPTED, TurnStage.CREATED, 7));
        assertThat(journal.turn("turn-shared").getStage()).isEqualTo(TurnStage.FAILED);

        jdbc.authoritative = List.of();
        assertThat(journal.turn("turn-shared")).isNull();
        assertThat(journal.turns()).isEmpty();
    }

    @Test
    void recoveryConvergesEveryNonTerminalStageBehindOwnerFence() {
        StubJdbcTemplate jdbc = new StubJdbcTemplate();
        PostgresTurnJournal journal = journal(jdbc);
        List<TurnRecord> interrupted = List.of(
                record("created", TurnStatus.ACCEPTED, TurnStage.CREATED, 0),
                record("planning", TurnStatus.RUNNING, TurnStage.PLANNING, 0),
                record("retrieving", TurnStatus.RUNNING, TurnStage.RETRIEVING, 0),
                record("generating", TurnStatus.RUNNING, TurnStage.GENERATING, 0),
                record("finalizing", TurnStatus.RUNNING, TurnStage.FINALIZING, 0));
        jdbc.authoritative = interrupted;
        jdbc.recoverable = interrupted;

        journal.recoverInterruptedTurns();

        assertThat(interrupted).allSatisfy(record -> {
            assertThat(record.getStatus()).isEqualTo(TurnStatus.FAILED);
            assertThat(record.getStage()).isEqualTo(TurnStage.FAILED);
            assertThat(record.getDone()).isDone();
        });
        assertThat(interrupted.stream()
                .filter(record -> "PROVIDER_STREAM_INTERRUPTED".equals(record.getFailureCode())))
                .extracting(TurnRecord::getTurnId)
                .containsExactly("generating");
        assertThat(jdbc.terminalEventWrites).isEqualTo(interrupted.size());
        assertThat(jdbc.lastRecoverySql)
                .contains("status NOT IN ('completed', 'failed', 'cancelled')")
                .contains("owner_id IS NULL")
                .contains("lease_until < CURRENT_TIMESTAMP");
        assertThat(jdbc.executionAppendSql).contains("owner_id = ? AND lease_until > CURRENT_TIMESTAMP");
    }

    @Test
    void activeLeaseOrLostCasCannotBeConvergedByRecovery() {
        StubJdbcTemplate jdbc = new StubJdbcTemplate();
        PostgresTurnJournal journal = journal(jdbc);
        TurnRecord activelyOwned = record("active", TurnStatus.RUNNING, TurnStage.GENERATING, 4);
        TurnRecord staleCandidateThatLostCas = record(
                "lost-cas", TurnStatus.RUNNING, TurnStage.RETRIEVING, 8);
        jdbc.authoritative = List.of(activelyOwned, staleCandidateThatLostCas);
        // The active row is excluded by PostgreSQL; the stale snapshot then loses its version CAS.
        jdbc.recoverable = List.of(staleCandidateThatLostCas);
        jdbc.claimSucceeds = false;

        journal.recoverInterruptedTurns();

        assertThat(activelyOwned.getStage()).isEqualTo(TurnStage.GENERATING);
        assertThat(staleCandidateThatLostCas.getStage()).isEqualTo(TurnStage.RETRIEVING);
        assertThat(jdbc.terminalEventWrites).isZero();
        assertThat(jdbc.lastRecoverySql)
                .contains("owner_id IS NULL OR lease_until IS NULL")
                .contains("lease_until < CURRENT_TIMESTAMP");
    }

    @Test
    void heartbeatCannotReviveAnExpiredExecutionLease() {
        StubJdbcTemplate jdbc = new StubJdbcTemplate();
        PostgresTurnJournal journal = journal(jdbc);
        TurnRecord record = record("heartbeat", TurnStatus.RUNNING, TurnStage.GENERATING, 3);

        assertThat(journal.heartbeatExecution(
                record, "owner-a", Duration.ofSeconds(30))).isTrue();

        assertThat(jdbc.heartbeatSql)
                .contains("owner_id = ? AND version = ?")
                .contains("lease_until > CURRENT_TIMESTAMP");
    }

    @Test
    void recoveryConvergesPersistedCancellationToCancelled() {
        StubJdbcTemplate jdbc = new StubJdbcTemplate();
        PostgresTurnJournal journal = journal(jdbc);
        TurnRecord cancelled = record(
                "cancelled-recovery", TurnStatus.RUNNING, TurnStage.GENERATING, 0);
        cancelled.requestCancel();
        jdbc.authoritative = List.of(cancelled);
        jdbc.recoverable = List.of(cancelled);

        journal.recoverInterruptedTurns();

        assertThat(cancelled.getStatus()).isEqualTo(TurnStatus.CANCELLED);
        assertThat(cancelled.getStage()).isEqualTo(TurnStage.CANCELLED);
        assertThat(cancelled.getDone()).isDone();
        assertThat(cancelled.getFailureCode()).isNull();
        assertThat(jdbc.terminalEventWrites).isEqualTo(1);
    }

    private static PostgresTurnJournal journal(StubJdbcTemplate jdbc) {
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        return new PostgresTurnJournal(jdbc, MAPPER, transactions, "recovery-test", false, false);
    }

    private static TurnRecord record(String id, TurnStatus status, TurnStage stage, long version) {
        Instant accepted = Instant.parse("2026-07-29T00:00:00Z");
        TurnRecord record = new TurnRecord(
                id, "trace-" + id, new TurnRequest("user", "website", "session-" + id, "hello"),
                accepted, accepted.plusSeconds(90));
        record.restore(status, stage, null, null,
                status == TurnStatus.FAILED ? "REMOTE_FAILURE" : null,
                status == TurnStatus.FAILED ? "remote failure" : null, null, version);
        return record;
    }

    private static final class StubJdbcTemplate extends JdbcTemplate {
        private List<TurnRecord> authoritative = List.of();
        private List<TurnRecord> recoverable = List.of();
        private boolean claimSucceeds = true;
        private int terminalEventWrites;
        private String lastRecoverySql = "";
        private String executionAppendSql = "";
        private String heartbeatSql = "";
        private long nextSequence;

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            if (sql.contains("owner_id IS NULL")) {
                lastRecoverySql = sql;
                return cast(recoverable);
            }
            if (sql.contains("FROM turn_runtime WHERE turn_id = ?")) {
                String id = String.valueOf(args[0]);
                return cast(authoritative.stream()
                        .filter(record -> record.getTurnId().equals(id)).toList());
            }
            if (sql.contains("FROM turn_runtime")) return cast(authoritative);
            return List.of();
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper) {
            return query(sql, rowMapper, new Object[0]);
        }

        @Override
        public int update(String sql, Object... args) {
            if (sql.contains("SET owner_id = ?") && sql.contains("status NOT IN")) {
                return claimSucceeds ? 1 : 0;
            }
            if (sql.contains("owner_id = ? AND lease_until > CURRENT_TIMESTAMP")) {
                executionAppendSql = sql;
            }
            if (sql.contains("SET lease_until = ?") && sql.contains("heartbeat_at")) {
                heartbeatSql = sql;
            }
            if (sql.contains("INSERT INTO turn_event")) terminalEventWrites++;
            return 1;
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            if (requiredType == Long.class && sql.contains("RETURNING last_sequence")) {
                return requiredType.cast(++nextSequence);
            }
            return null;
        }

        @SuppressWarnings("unchecked")
        private static <T> List<T> cast(List<?> values) {
            return (List<T>) new ArrayList<>(values);
        }
    }
}

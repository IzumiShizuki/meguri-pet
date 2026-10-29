package com.meguri.core.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.dto.TurnRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Deterministic two-thread reproduction of the database-row / record-monitor inversion. */
class PostgresTurnJournalIdempotencyConcurrencyTest {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void duplicateAcceptanceReleasesRowLockBeforeWaitingForExecutionWriter(boolean retry) throws Exception {
        RowLockJdbc jdbc = new RowLockJdbc();
        PostgresTurnJournal journal = journal(jdbc);
        TurnRequest request = new TurnRequest("synthetic", "website", "isolated", "hello");
        Instant deadline = Instant.now().plusSeconds(60);
        TurnJournal.Acceptance initial = accept(journal, request, deadline, retry);
        TurnRecord cached = initial.record();
        assertThat(initial.created()).isTrue();
        jdbc.armContention = true;

        try (var workers = Executors.newFixedThreadPool(2)) {
            var writer = workers.submit(() -> {
                synchronized (cached) {
                    jdbc.recordMonitorHeld.countDown();
                    await(jdbc.idempotencyRowLocked, "duplicate request did not lock its row");
                    // Actual production writer: monitor first, database UPDATE second.
                    return journal.claimExecution(cached, "test-owner", Duration.ofSeconds(30));
                }
            });
            await(jdbc.recordMonitorHeld, "execution writer did not acquire record monitor");
            var duplicate = workers.submit(() -> accept(journal, request, deadline, retry));

            assertThat(writer.get(5, TimeUnit.SECONDS)).isTrue();
            TurnJournal.Acceptance replay = duplicate.get(5, TimeUnit.SECONDS);
            assertThat(replay.created()).isFalse();
            assertThat(replay.record()).isSameAs(cached);
            assertThat(replay.record().getTurnId()).isEqualTo(initial.record().getTurnId());
            assertThat(replay.record().getVersion()).isEqualTo(1);
            assertThat(replay.record().getRetryOfTurnId()).isEqualTo(retry ? "failed-parent" : null);
            assertThat(jdbc.insertions.get()).isEqualTo(1);
            assertThat(jdbc.claims.get()).isEqualTo(1);
        }
        // Readback and another duplicate must still use the canonical cached record.
        jdbc.armContention = false;
        assertThat(journal.turn(cached.getTurnId())).isSameAs(cached);
        assertThat(accept(journal, request, deadline, retry).record()).isSameAs(cached);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void movingCacheRefreshOutsideTransactionKeepsPayloadConflictRejection(boolean retry) {
        RowLockJdbc jdbc = new RowLockJdbc();
        PostgresTurnJournal journal = journal(jdbc);
        Instant deadline = Instant.now().plusSeconds(60);
        accept(journal, new TurnRequest("synthetic", "website", "isolated", "first"), deadline, retry);

        assertThatThrownBy(() -> accept(journal,
                new TurnRequest("synthetic", "website", "isolated", "different"), deadline, retry))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(jdbc.rowLock.isLocked()).isFalse();
        assertThat(jdbc.insertions.get()).isEqualTo(1);
    }

    @Test
    void retryReplayStillRejectsAnotherFailedPredecessor() {
        RowLockJdbc jdbc = new RowLockJdbc();
        PostgresTurnJournal journal = journal(jdbc);
        TurnRequest request = new TurnRequest("synthetic", "website", "isolated", "hello");
        Instant deadline = Instant.now().plusSeconds(60);
        journal.acceptRetry(request, "same-key", deadline, "failed-parent");

        assertThatThrownBy(() -> journal.acceptRetry(
                request, "same-key", deadline, "another-failed-parent"))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(jdbc.rowLock.isLocked()).isFalse();
        assertThat(jdbc.insertions.get()).isEqualTo(1);
    }

    private static TurnJournal.Acceptance accept(
            PostgresTurnJournal journal, TurnRequest request, Instant deadline, boolean retry) {
        return retry ? journal.acceptRetry(request, "same-key", deadline, "failed-parent")
                : journal.accept(request, "same-key", deadline);
    }

    private static PostgresTurnJournal journal(RowLockJdbc jdbc) {
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            TransactionCallback<Object> callback = invocation.getArgument(0);
            try {
                return callback.doInTransaction(mock(TransactionStatus.class));
            } finally {
                // A SELECT FOR UPDATE lock survives through the transaction callback.
                if (jdbc.rowLock.isHeldByCurrentThread()) jdbc.rowLock.unlock();
            }
        });
        return new PostgresTurnJournal(jdbc, MAPPER, transactions, "isolated-test", false, false);
    }

    private static void await(CountDownLatch latch, String failure) {
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) throw new AssertionError(failure);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure, error);
        }
    }

    /** Models SQL row-lock lifetime, not journal internals; no database or sleeps required. */
    private static final class RowLockJdbc extends JdbcTemplate {
        private final ReentrantLock rowLock = new ReentrantLock();
        private final CountDownLatch recordMonitorHeld = new CountDownLatch(1);
        private final CountDownLatch idempotencyRowLocked = new CountDownLatch(1);
        private final CountDownLatch writerAttemptedUpdate = new CountDownLatch(1);
        private final AtomicInteger insertions = new AtomicInteger();
        private final AtomicInteger claims = new AtomicInteger();
        private final AtomicLong version = new AtomicLong();
        private volatile boolean armContention;
        private Map<String, Object> stored;

        @Override
        public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
            if (sql.contains("SELECT turn_id, payload_hash")) {
                if (stored == null) return List.of();
                assertThat(sql).contains("FOR UPDATE");
                rowLock.lock();
                if (armContention) {
                    idempotencyRowLocked.countDown();
                    await(writerAttemptedUpdate, "writer did not reach the locked database UPDATE");
                }
                return mapRow(mapper);
            }
            if (sql.contains("FROM turn_runtime WHERE turn_id = ?")) return mapRow(mapper);
            throw new AssertionError("Unexpected query in row-lock fixture: " + sql);
        }

        @Override
        public int update(String sql, Object... args) {
            if (sql.contains("INSERT INTO turn_runtime")) {
                stored = new HashMap<>();
                String[] fields = {"turn_id", "trace_id", "user_id", "client_id", "session_id",
                        "idempotency_key", "payload_hash", "request_json", "status", "stage",
                        "accepted_at", "deadline_at", "retry_of_turn_id"};
                for (int i = 0; i < fields.length; i++) stored.put(fields[i], args[i]);
                insertions.incrementAndGet();
                return 1;
            }
            if (sql.contains("SET owner_id = ?") && sql.contains("status NOT IN")) {
                writerAttemptedUpdate.countDown();
                try {
                    // Bounded acquisition makes a regression fail instead of hanging
                    // the test JVM. The latches guarantee contention before this wait.
                    if (!rowLock.tryLock(2, TimeUnit.SECONDS)) {
                        throw new AssertionError("Execution UPDATE blocked by idempotent row lock while holding record monitor");
                    }
                    try {
                        claims.incrementAndGet();
                        version.incrementAndGet();
                        return 1;
                    } finally {
                        rowLock.unlock();
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(error);
                }
            }
            throw new AssertionError("Unexpected update in row-lock fixture: " + sql);
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> type, Object... args) {
            if (type == Integer.class && sql.contains("status = 'failed'")) return type.cast(1);
            throw new AssertionError("Unexpected scalar query in row-lock fixture: " + sql);
        }

        private <T> List<T> mapRow(RowMapper<T> mapper) {
            try {
                ResultSet row = mock(ResultSet.class);
                for (var entry : stored.entrySet()) {
                    if (entry.getValue() instanceof Timestamp timestamp) {
                        when(row.getTimestamp(entry.getKey())).thenReturn(timestamp);
                    } else {
                        when(row.getString(entry.getKey())).thenReturn((String) entry.getValue());
                    }
                }
                when(row.getLong("version")).thenReturn(version.get());
                return List.of(mapper.mapRow(row, 0));
            } catch (SQLException error) {
                throw new AssertionError(error);
            }
        }
    }
}

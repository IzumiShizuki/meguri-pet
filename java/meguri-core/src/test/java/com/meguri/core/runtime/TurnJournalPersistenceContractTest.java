package com.meguri.core.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.dto.ClientCapabilities;
import com.meguri.core.dto.EventEnvelope;
import com.meguri.core.dto.EventMetadata;
import com.meguri.core.dto.TurnRequest;
import com.meguri.core.memory.NoopMemoryGateway;
import com.meguri.core.training.TrainingFeedbackService;
import com.meguri.core.weather.WeatherConversationService;
import com.meguri.core.websearch.NoopWebSearchGateway;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class TurnJournalPersistenceContractTest {
    @Test
    void persistsFrozenManifestAndEveryLifecycleStage() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        RecordingJournal journal = new RecordingJournal(mapper);
        TurnOrchestrator runtime = new TurnOrchestrator(
                null,
                null,
                new RuntimeStateMachine(),
                new ExpressionResolver(),
                Duration.ofMillis(1),
                mapper,
                new NoopMemoryGateway(),
                new NoopWebSearchGateway(),
                TrainingFeedbackService.disabled(mapper),
                WeatherConversationService.disabled(),
                journal);

        TurnRecord record = runtime.start(new TurnRequest(
                "user", "website", "durable-session", "hello",
                List.of(), new ClientCapabilities(true, true, false, false), null, null, false));
        record.getDone().join();

        assertThat(journal.snapshots)
                .anySatisfy(snapshot -> {
                    assertThat(snapshot.status()).isEqualTo("accepted");
                    assertThat(snapshot.stage()).isEqualTo("created");
                    assertThat(snapshot.manifestPresent()).isTrue();
                })
                .extracting(PersistedSnapshot::stage)
                .contains("planning", "retrieving", "generating", "finalizing", "completed");
        assertThat(journal.snapshots.getLast().status()).isEqualTo("completed");
        assertThat(journal.snapshots.getLast().resultPresent()).isTrue();
        runtime.reset();
    }

    @Test
    void postgresSchemaCarriesDurabilityAndOutboxConstraints() throws IOException {
        byte[] bytes;
        try (var stream = getClass().getClassLoader().getResourceAsStream("db/turn-runtime.sql")) {
            assertThat(stream).isNotNull();
            bytes = stream.readAllBytes();
        }
        String sql = new String(bytes, StandardCharsets.UTF_8).toLowerCase(java.util.Locale.ROOT);

        assertThat(sql)
                .contains("create table if not exists turn_runtime")
                .contains("uq_turn_runtime_idempotency")
                .contains("create table if not exists turn_session_sequence")
                .contains("unique (session_id, sequence)")
                .contains("create table if not exists turn_outbox")
                .contains("create table if not exists session_context_graph")
                .contains("event_id varchar(128) not null unique")
                .contains("where status = 'pending'");
    }

    @Test
    void terminalAppendFailureRollsBackMemoryBeforeRecordingFailure() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        FailingCompletionJournal journal = new FailingCompletionJournal(mapper);
        TurnOrchestrator runtime = new TurnOrchestrator(
                null,
                null,
                new RuntimeStateMachine(),
                new ExpressionResolver(),
                Duration.ofMillis(1),
                mapper,
                new NoopMemoryGateway(),
                new NoopWebSearchGateway(),
                TrainingFeedbackService.disabled(mapper),
                WeatherConversationService.disabled(),
                journal);

        TurnRecord record = runtime.start(new TurnRequest(
                "user", "website", "terminal-rollback-session", "hello",
                List.of(), new ClientCapabilities(true, true, false, false), null, null, false));
        record.getDone().get(5, TimeUnit.SECONDS);

        assertThat(record.getStatus()).isEqualTo(TurnStatus.FAILED);
        assertThat(record.getStage()).isEqualTo(TurnStage.FAILED);
        assertThat(record.getResult()).isNull();
        assertThat(record.getError()).contains("injected turn.completed append failure");
        assertThat(journal.events(record.getRequest().getSessionId()))
                .extracting(EventEnvelope::getType)
                .contains("turn.failed")
                .doesNotContain("turn.completed");
        runtime.reset();
    }

    private record PersistedSnapshot(
            String status,
            String stage,
            boolean manifestPresent,
            boolean resultPresent) { }

    private static final class RecordingJournal implements TurnJournal {
        private final InMemoryTurnJournal delegate;
        private final CopyOnWriteArrayList<PersistedSnapshot> snapshots = new CopyOnWriteArrayList<>();

        private RecordingJournal(ObjectMapper mapper) {
            delegate = new InMemoryTurnJournal(mapper);
        }

        @Override
        public Acceptance accept(TurnRequest request, String idempotencyKey, Instant deadlineAt) {
            return delegate.accept(request, idempotencyKey, deadlineAt);
        }

        @Override
        public TurnRecord create(TurnRequest request, Instant deadlineAt) {
            return delegate.create(request, deadlineAt);
        }

        @Override
        public TurnRecord turn(String turnId) {
            return delegate.turn(turnId);
        }

        @Override
        public Map<String, TurnRecord> turns() {
            return delegate.turns();
        }

        @Override
        public List<EventEnvelope> events(String sessionId) {
            return delegate.events(sessionId);
        }

        @Override
        public long lastSequence(String sessionId) {
            return delegate.lastSequence(sessionId);
        }

        @Override
        public EventEnvelope append(TurnRecord record, String type,
                                    Map<String, Object> data, EventMetadata metadata) {
            persist(record);
            return delegate.append(record, type, data, metadata);
        }

        @Override
        public void persist(TurnRecord record) {
            snapshots.add(new PersistedSnapshot(
                    record.statusValue(),
                    record.getStage().wireValue(),
                    record.getManifest() != null,
                    record.getResult() != null));
        }

        @Override
        public void clear() {
            delegate.clear();
        }
    }

    private static final class FailingCompletionJournal implements TurnJournal {
        private final InMemoryTurnJournal delegate;

        private FailingCompletionJournal(ObjectMapper mapper) {
            delegate = new InMemoryTurnJournal(mapper);
        }

        @Override
        public Acceptance accept(TurnRequest request, String idempotencyKey, Instant deadlineAt) {
            return delegate.accept(request, idempotencyKey, deadlineAt);
        }

        @Override
        public TurnRecord create(TurnRequest request, Instant deadlineAt) {
            return delegate.create(request, deadlineAt);
        }

        @Override
        public TurnRecord turn(String turnId) {
            return delegate.turn(turnId);
        }

        @Override
        public Map<String, TurnRecord> turns() {
            return delegate.turns();
        }

        @Override
        public List<EventEnvelope> events(String sessionId) {
            return delegate.events(sessionId);
        }

        @Override
        public long lastSequence(String sessionId) {
            return delegate.lastSequence(sessionId);
        }

        @Override
        public EventEnvelope append(TurnRecord record, String type,
                                    Map<String, Object> data, EventMetadata metadata) {
            if ("turn.completed".equals(type)) {
                throw new IllegalStateException("injected turn.completed append failure");
            }
            return delegate.append(record, type, data, metadata);
        }

        @Override
        public void clear() {
            delegate.clear();
        }
    }
}

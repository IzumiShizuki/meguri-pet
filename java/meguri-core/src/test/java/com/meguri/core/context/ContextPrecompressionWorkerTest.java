package com.meguri.core.context;

import com.meguri.core.runtime.NoopSessionContextPersistence;
import com.meguri.core.runtime.SessionContextPersistence;
import com.meguri.core.runtime.SessionContextStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ContextPrecompressionWorkerTest {
    @Test
    void claimsJobCreatesOneIdempotentSummaryAndCompletesIt() {
        SessionContextStore sessions = new SessionContextStore(
                20, new NoopSessionContextPersistence());
        List<String> sourceIds = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            String messageId = "message-" + index;
            sessions.appendNode("user", "website", "conversation", messageId, null,
                    new SessionContextStore.Message(
                            index % 2 == 0 ? "user" : "assistant", "message " + index));
            sourceIds.add(messageId);
        }
        InMemoryContextRuntimePersistence persistence =
                new InMemoryContextRuntimePersistence();
        Instant now = Instant.now();
        persistence.enqueuePrecompression(new ContextRuntimePersistence.PrecompressionJob(
                "job-1", "key-1", "user", "website", "conversation", 8L,
                sourceIds, "model", ContextRuntimePersistence.JobStatus.PENDING,
                0, now, null, null, now));
        try (ContextPrecompressionWorker worker = new ContextPrecompressionWorker(
                sessions, persistence, Clock.fixed(now, ZoneOffset.UTC),
                Duration.ofSeconds(30), Duration.ofSeconds(1), 10, 3, 1_000)) {
            assertThat(worker.runOnce()).isEqualTo(
                    new ContextPrecompressionWorker.Result(1, 1, 1, 0));
            assertThat(worker.runOnce()).isEqualTo(
                    new ContextPrecompressionWorker.Result(0, 0, 0, 0));
        }
        assertThat(sessions.activeSummaries("user", "website", "conversation"))
                .singleElement()
                .satisfies(summary -> {
                    assertThat(summary.sourceMessageIds()).containsExactlyElementsOf(
                            sourceIds.subList(0, 4));
                    assertThat(summary.content()).contains("message 0", "message 3")
                            .doesNotContain("message 7");
                });
    }

    @Test
    void claimedJobReloadsFreshGraphWrittenByAnotherInstance() {
        SharedPersistence shared = new SharedPersistence();
        SessionContextStore staleWorkerView = new SessionContextStore(20, shared);
        SessionContextStore writer = new SessionContextStore(20, shared);
        List<String> sourceIds = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            String messageId = "remote-message-" + index;
            writer.appendNode("user", "website", "shared", messageId, null,
                    new SessionContextStore.Message(index % 2 == 0 ? "user" : "assistant",
                            "remote message " + index));
            sourceIds.add(messageId);
        }
        assertThat(staleWorkerView.graph("user", "website", "shared").revision()).isZero();

        Instant now = Instant.now();
        InMemoryContextRuntimePersistence jobs = new InMemoryContextRuntimePersistence();
        jobs.enqueuePrecompression(new ContextRuntimePersistence.PrecompressionJob(
                "remote-job", "remote-key", "user", "website", "shared", 8L,
                sourceIds, "model", ContextRuntimePersistence.JobStatus.PENDING,
                0, now, null, null, now));

        try (ContextPrecompressionWorker worker = new ContextPrecompressionWorker(
                staleWorkerView, jobs, Clock.systemUTC(), Duration.ofSeconds(30),
                Duration.ofSeconds(1), 10, 3, 1_000)) {
            assertThat(worker.runOnce()).isEqualTo(
                    new ContextPrecompressionWorker.Result(1, 1, 1, 0));
        }
        assertThat(staleWorkerView.graph("user", "website", "shared").revision()).isEqualTo(9);
        assertThat(staleWorkerView.activeSummaries("user", "website", "shared")).hasSize(1);
    }

    private static final class SharedPersistence implements SessionContextPersistence {
        private final Map<String, SessionContextStore.GraphSnapshot> values = new LinkedHashMap<>();

        @Override
        public synchronized List<SessionContextStore.GraphSnapshot> loadAll() {
            return List.copyOf(values.values());
        }

        @Override
        public synchronized Optional<SessionContextStore.GraphSnapshot> load(
                String userId, String clientId, String sessionId) {
            return Optional.ofNullable(values.get(key(userId, clientId, sessionId)));
        }

        @Override
        public synchronized void save(SessionContextStore.GraphSnapshot snapshot) {
            values.put(key(snapshot.userId(), snapshot.clientId(), snapshot.sessionId()), snapshot);
        }

        @Override
        public synchronized void clear() {
            values.clear();
        }

        private static String key(String userId, String clientId, String sessionId) {
            return userId + "\0" + clientId + "\0" + sessionId;
        }
    }
}

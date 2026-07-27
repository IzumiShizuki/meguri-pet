package com.meguri.core.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.List;
import java.util.Objects;

/** PostgreSQL projection for complete Companion Context graph snapshots. */
public final class PostgresSessionContextPersistence implements SessionContextPersistence {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public PostgresSessionContextPersistence(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        if (jdbc.getDataSource() == null) throw new IllegalStateException("context persistence requires a datasource");
        new ResourceDatabasePopulator(new ClassPathResource("db/turn-runtime.sql"))
                .execute(jdbc.getDataSource());
    }

    @Override
    public List<SessionContextStore.GraphSnapshot> loadAll() {
        return jdbc.query("""
                        SELECT snapshot_json
                        FROM session_context_graph
                        ORDER BY user_id, client_id, session_id
                        """,
                (rs, rowNum) -> read(rs.getString("snapshot_json")));
    }

    @Override
    public void save(SessionContextStore.GraphSnapshot snapshot) {
        int updated = jdbc.update("""
                INSERT INTO session_context_graph (
                    user_id, client_id, session_id, revision, snapshot_json, updated_at
                ) VALUES (?, ?, ?, ?, CAST(? AS jsonb), CURRENT_TIMESTAMP)
                ON CONFLICT (user_id, client_id, session_id) DO UPDATE
                SET revision = EXCLUDED.revision,
                    snapshot_json = EXCLUDED.snapshot_json,
                    updated_at = CURRENT_TIMESTAMP
                WHERE session_context_graph.revision = EXCLUDED.revision - 1
                """,
                snapshot.userId(), snapshot.clientId(), snapshot.sessionId(),
                snapshot.revision(), write(snapshot));
        if (updated != 1) {
            throw new IllegalStateException(
                    "session context revision conflict for " + snapshot.sessionId());
        }
    }

    @Override
    public void clear() {
        jdbc.update("DELETE FROM session_context_graph");
    }

    private String write(SessionContextStore.GraphSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("failed to serialize session context graph", error);
        }
    }

    private SessionContextStore.GraphSnapshot read(String value) {
        try {
            return objectMapper.readValue(value, SessionContextStore.GraphSnapshot.class);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("failed to deserialize session context graph", error);
        }
    }
}

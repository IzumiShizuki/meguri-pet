package com.meguri.core.retrieval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.Objects;
import java.util.Optional;

/** PostgreSQL authority for immutable, content-bearing retrieval traces. */
public final class PostgresRetrievalTraceRepository implements RetrievalTraceRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public PostgresRetrievalTraceRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        ResourceDatabasePopulator schema = new ResourceDatabasePopulator(
                new ClassPathResource("db/retrieval-runtime.sql"));
        schema.setContinueOnError(false);
        schema.execute(Objects.requireNonNull(
                jdbc.getDataSource(), "retrieval trace data source"));
    }

    @Override
    public void save(RetrievalTrace trace) {
        Objects.requireNonNull(trace, "trace");
        jdbc.update("""
                INSERT INTO meguri_retrieval_trace (
                    trace_id, snapshot_id, knowledge_revision, valid_at,
                    algorithm_revision, completed_at, trace_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (trace_id) DO NOTHING
                """,
                trace.traceId(),
                trace.snapshotId(),
                trace.revision(),
                java.sql.Timestamp.from(trace.validAt()),
                trace.algorithmRevision(),
                java.sql.Timestamp.from(trace.completedAt()),
                write(trace));
    }

    @Override
    public Optional<RetrievalTrace> find(String traceId) {
        if (traceId == null || traceId.isBlank()) return Optional.empty();
        return jdbc.query("""
                        SELECT trace_json::text
                        FROM meguri_retrieval_trace
                        WHERE trace_id = ?
                        """,
                result -> result.next()
                        ? Optional.of(read(result.getString(1)))
                        : Optional.empty(),
                traceId.trim());
    }

    private String write(RetrievalTrace trace) {
        try {
            return mapper.writeValueAsString(trace);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("failed to serialize retrieval trace", error);
        }
    }

    private RetrievalTrace read(String value) {
        try {
            return mapper.readValue(value, RetrievalTrace.class);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("failed to deserialize retrieval trace", error);
        }
    }
}

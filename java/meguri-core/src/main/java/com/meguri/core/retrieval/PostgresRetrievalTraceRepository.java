package com.meguri.core.retrieval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.Objects;
import java.util.Optional;

/** PostgreSQL authority for immutable, content-free retrieval trace projections. */
public final class PostgresRetrievalTraceRepository implements RetrievalTraceRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public PostgresRetrievalTraceRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this(jdbc, mapper, true);
    }

    PostgresRetrievalTraceRepository(
            JdbcTemplate jdbc, ObjectMapper mapper, boolean initializeSchema) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        if (!initializeSchema) return;
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
                    algorithm_revision, completed_at, projection_version, trace_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (trace_id) DO NOTHING
                """,
                trace.traceId(),
                trace.snapshotId(),
                trace.revision(),
                java.sql.Timestamp.from(trace.validAt()),
                trace.algorithmRevision(),
                java.sql.Timestamp.from(trace.completedAt()),
                RetrievalTraceProjection.VERSION,
                write(RetrievalTraceProjection.capture(trace)));
    }

    @Override
    public Optional<RetrievalTrace> find(String traceId) {
        if (traceId == null || traceId.isBlank()) return Optional.empty();
        return findProjection(traceId).map(RetrievalTraceProjection::toRedactedTrace);
    }

    @Override
    public Optional<RetrievalTraceProjection> findProjection(String traceId) {
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

    private String write(RetrievalTraceProjection trace) {
        try {
            return mapper.writeValueAsString(trace);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("failed to serialize retrieval trace", error);
        }
    }

    private RetrievalTraceProjection read(String value) {
        try {
            return mapper.readValue(value, RetrievalTraceProjection.class);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("failed to deserialize retrieval trace", error);
        }
    }
}

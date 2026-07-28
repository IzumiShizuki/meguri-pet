package com.meguri.core.capability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** PostgreSQL MCP configuration store. Credential values are never accepted. */
public final class JdbcMcpSourceStore implements McpSourceStore {
    private static final TypeReference<Map<String, String>> STRING_MAP =
            new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcMcpSourceStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        ResourceDatabasePopulator schema = new ResourceDatabasePopulator(
                new ClassPathResource("db/capability-runtime.sql"));
        schema.setContinueOnError(false);
        schema.execute(Objects.requireNonNull(
                jdbc.getDataSource(), "MCP source store data source"));
    }

    @Override
    public List<McpSourceManager.SourceConfiguration> configurations() {
        return jdbc.query("""
                SELECT source_id, endpoint, maximum_protocol,
                       authorization_environment, headers,
                       allow_insecure_localhost
                FROM capability_mcp_source
                ORDER BY source_id
                """, (result, row) -> read(result));
    }

    @Override
    public Optional<McpSourceManager.SourceConfiguration> find(String sourceId) {
        List<McpSourceManager.SourceConfiguration> matches = jdbc.query("""
                SELECT source_id, endpoint, maximum_protocol,
                       authorization_environment, headers,
                       allow_insecure_localhost
                FROM capability_mcp_source
                WHERE source_id = ?
                """, (result, row) -> read(result), sourceId);
        if (matches.size() > 1) {
            throw new IllegalStateException("duplicate MCP source configuration");
        }
        return matches.stream().findFirst();
    }

    @Override
    public void save(McpSourceManager.SourceConfiguration configuration) {
        jdbc.update("""
                INSERT INTO capability_mcp_source (
                    source_id, endpoint, maximum_protocol,
                    authorization_environment, headers,
                    allow_insecure_localhost, updated_at
                ) VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)
                ON CONFLICT (source_id) DO UPDATE
                SET endpoint = EXCLUDED.endpoint,
                    maximum_protocol = EXCLUDED.maximum_protocol,
                    authorization_environment = EXCLUDED.authorization_environment,
                    headers = EXCLUDED.headers,
                    allow_insecure_localhost = EXCLUDED.allow_insecure_localhost,
                    updated_at = EXCLUDED.updated_at
                """,
                configuration.id(),
                configuration.endpoint().toASCIIString(),
                configuration.maximumProtocol(),
                configuration.authorizationEnvironment(),
                write(configuration.headers()),
                configuration.allowInsecureLocalhost(),
                Timestamp.from(Instant.now()));
    }

    @Override
    public void delete(String sourceId) {
        jdbc.update(
                "DELETE FROM capability_mcp_source WHERE source_id = ?",
                sourceId);
    }

    private McpSourceManager.SourceConfiguration read(
            java.sql.ResultSet result) throws java.sql.SQLException {
        return new McpSourceManager.SourceConfiguration(
                result.getString("source_id"),
                URI.create(result.getString("endpoint")),
                result.getInt("maximum_protocol"),
                result.getString("authorization_environment"),
                readHeaders(result.getString("headers")),
                result.getBoolean("allow_insecure_localhost"));
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException(
                    "failed to serialize MCP source configuration", error);
        }
    }

    private Map<String, String> readHeaders(String value) {
        try {
            return mapper.readValue(value, STRING_MAP);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException(
                    "failed to deserialize MCP source configuration", error);
        }
    }
}

package com.meguri.core.adapter.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.adapter.application.ClientBindingRepository;
import com.meguri.core.adapter.domain.AdapterClientCapabilities;
import com.meguri.core.adapter.domain.ClientBinding;
import com.meguri.core.adapter.domain.ClientPermissions;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** PostgreSQL binding repository; one client instance cannot silently change identity. */
public final class JdbcClientBindingRepository implements ClientBindingRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcClientBindingRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        if (jdbc.getDataSource() == null) {
            throw new IllegalStateException("client binding repository requires a datasource");
        }
        new ResourceDatabasePopulator(new ClassPathResource("db/adapter-runtime.sql"))
                .execute(jdbc.getDataSource());
    }

    @Override
    public ClientBinding save(ClientBinding binding) {
        Optional<ClientBinding> current = find(binding.clientInstanceId());
        current.ifPresent(existing -> {
            if (!existing.tenantId().equals(binding.tenantId())
                    || !existing.meguriUserId().equals(binding.meguriUserId())
                    || !existing.clientId().equals(binding.clientId())
                    || actorBindingChanged(existing, binding)) {
                throw new IllegalStateException(
                        "client instance is already bound to another identity");
            }
        });
        jdbc.update("""
                INSERT INTO client_binding (
                    client_instance_id, tenant_id, meguri_user_id, client_id,
                    platform_actor_hash, client_version,
                    selected_protocol_version, server_capabilities_revision,
                    capabilities, permissions, last_seen_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?)
                ON CONFLICT (client_instance_id) DO UPDATE SET
                    platform_actor_hash = COALESCE(
                        client_binding.platform_actor_hash, EXCLUDED.platform_actor_hash),
                    client_version = EXCLUDED.client_version,
                    selected_protocol_version = EXCLUDED.selected_protocol_version,
                    server_capabilities_revision = EXCLUDED.server_capabilities_revision,
                    capabilities = EXCLUDED.capabilities,
                    permissions = EXCLUDED.permissions,
                    last_seen_at = EXCLUDED.last_seen_at
                """,
                binding.clientInstanceId(), binding.tenantId(), binding.meguriUserId(),
                binding.clientId(), binding.platformActorHash(), binding.clientVersion(),
                binding.selectedProtocolVersion(),
                binding.serverCapabilitiesRevision(), write(binding.capabilities()),
                write(binding.permissions()), Timestamp.from(binding.lastSeenAt()));
        return binding;
    }

    @Override
    public Optional<ClientBinding> find(String clientInstanceId) {
        List<ClientBinding> rows = jdbc.query("""
                        SELECT client_instance_id, tenant_id, meguri_user_id, client_id,
                               platform_actor_hash,
                               client_version, selected_protocol_version,
                               server_capabilities_revision, capabilities, permissions,
                               last_seen_at
                        FROM client_binding
                        WHERE client_instance_id = ?
                        """,
                (rs, rowNumber) -> new ClientBinding(
                        rs.getString("client_instance_id"),
                        rs.getString("tenant_id"),
                        rs.getString("meguri_user_id"),
                        rs.getString("client_id"),
                        rs.getString("platform_actor_hash"),
                        rs.getString("client_version"),
                        rs.getString("selected_protocol_version"),
                        rs.getString("server_capabilities_revision"),
                        read(rs.getString("capabilities"), AdapterClientCapabilities.class),
                        read(rs.getString("permissions"), ClientPermissions.class),
                        rs.getTimestamp("last_seen_at").toInstant()),
                clientInstanceId);
        return rows.stream().findFirst();
    }

    private static boolean actorBindingChanged(
            ClientBinding current, ClientBinding incoming) {
        return current.platformActorHash() != null
                && !current.platformActorHash().equals(incoming.platformActorHash());
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("failed to encode client binding", error);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("failed to decode client binding", error);
        }
    }
}

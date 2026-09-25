package com.meguri.core.skill;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class JdbcSkillCatalogRepository implements SkillCatalogRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcSkillCatalogRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this(jdbc, mapper, true);
    }

    JdbcSkillCatalogRepository(JdbcTemplate jdbc, ObjectMapper mapper, boolean initializeSchema) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        if (initializeSchema) {
            new ResourceDatabasePopulator(new ClassPathResource("db/skill-runtime.sql"))
                    .execute(Objects.requireNonNull(jdbc.getDataSource(), "Skill store data source"));
        }
    }

    @Override public void saveManifest(SkillManifest manifest) {
        jdbc.update("""
                INSERT INTO external_skill(skill_id, source_id, external_id, manifest, updated_at)
                VALUES (?, ?, ?, ?::jsonb, ?)
                ON CONFLICT (skill_id) DO UPDATE SET manifest = EXCLUDED.manifest, updated_at = EXCLUDED.updated_at
                """, manifest.id(), manifest.sourceId(), manifest.externalId(), write(manifest), Timestamp.from(Instant.now()));
    }
    @Override public void saveRevision(SkillRevision revision) {
        jdbc.update("""
                INSERT INTO external_skill_revision(skill_id, digest, revision, imported_at)
                VALUES (?, ?, ?::jsonb, ?)
                ON CONFLICT (skill_id, digest) DO UPDATE SET revision = EXCLUDED.revision
                """, revision.skillId(), revision.digest(), write(revision), Timestamp.from(revision.importedAt()));
    }
    @Override public void saveBinding(SkillBinding binding) {
        jdbc.update("""
                INSERT INTO external_skill_binding(skill_id, active_digest, state, updated_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (skill_id) DO UPDATE SET active_digest = EXCLUDED.active_digest,
                    state = EXCLUDED.state, updated_at = EXCLUDED.updated_at
                """, binding.skillId(), binding.activeDigest(), binding.state().name(), Timestamp.from(binding.updatedAt()));
    }
    @Override public Optional<SkillCatalogEntry> find(String skillId) {
        return jdbc.query("SELECT manifest FROM external_skill WHERE skill_id = ?",
                (rs, row) -> read(rs.getString(1), SkillManifest.class), skillId).stream().findFirst().map(this::entry);
    }
    @Override public Optional<SkillCatalogEntry> findBySource(String sourceId, String externalId) {
        return jdbc.query("SELECT manifest FROM external_skill WHERE source_id = ? AND external_id = ?",
                (rs, row) -> read(rs.getString(1), SkillManifest.class), sourceId, externalId).stream().findFirst().map(this::entry);
    }
    @Override public List<SkillCatalogEntry> list() {
        return jdbc.query("SELECT manifest FROM external_skill ORDER BY lower(manifest->>'name'), skill_id",
                (rs, row) -> read(rs.getString(1), SkillManifest.class)).stream().map(this::entry).toList();
    }
    @Override public void appendAudit(SkillAuditEvent event) {
        jdbc.update("""
                INSERT INTO external_skill_audit(event_id, source_id, external_id, skill_id, digest,
                    actor, transition, status, failure_code, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, event.eventId(), event.sourceId(), event.externalId(), event.skillId(), event.digest(),
                event.actor(), event.transition(), event.status(), event.failureCode(), Timestamp.from(event.occurredAt()));
    }
    @Override public List<SkillAuditEvent> audit() {
        return jdbc.query("SELECT * FROM external_skill_audit ORDER BY occurred_at, event_id", (rs, row) -> new SkillAuditEvent(
                rs.getString("event_id"), rs.getString("source_id"), rs.getString("external_id"),
                rs.getString("skill_id"), rs.getString("digest"), rs.getString("actor"),
                rs.getString("transition"), rs.getString("status"), rs.getString("failure_code"),
                rs.getTimestamp("occurred_at").toInstant()));
    }

    private SkillCatalogEntry entry(SkillManifest manifest) {
        List<SkillRevision> revisions = jdbc.query(
                "SELECT revision FROM external_skill_revision WHERE skill_id = ? ORDER BY imported_at",
                (rs, row) -> read(rs.getString(1), SkillRevision.class), manifest.id());
        SkillBinding binding = jdbc.query("SELECT active_digest, state, updated_at FROM external_skill_binding WHERE skill_id = ?",
                (rs, row) -> new SkillBinding(manifest.id(), rs.getString("active_digest"),
                        SkillBinding.ActivationState.valueOf(rs.getString("state")), rs.getTimestamp("updated_at").toInstant()),
                manifest.id()).stream().findFirst().orElse(new SkillBinding(
                        manifest.id(), null, SkillBinding.ActivationState.DISABLED, Instant.now()));
        return new SkillCatalogEntry(manifest, revisions, binding);
    }
    private String write(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new IllegalStateException("Skill metadata serialization failed", error); }
    }
    private <T> T read(String value, Class<T> type) {
        try { return mapper.readValue(value, type); }
        catch (JsonProcessingException error) { throw new IllegalStateException("Skill metadata deserialization failed", error); }
    }
}

package com.meguri.core.persona;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.dto.Relationship;
import com.meguri.core.persona.profile.*;
import com.meguri.core.persona.relationship.*;
import com.meguri.core.persona.runtime.*;
import com.meguri.core.persona.scene.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** PostgreSQL authority for every mutable or versioned Persona Runtime aggregate. */
public final class PostgresPersonaRuntimeRepository implements PersonaProfileRepository,
        RelationshipStateRepository, SceneStateRepository, RuntimeOverrideRepository,
        InteractionStateRepository, UserProfileRepository, PersonaAuditRepository {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private static final TypeReference<Set<String>> STRING_SET = new TypeReference<>() { };
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() { };
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;

    public PostgresPersonaRuntimeRepository(JdbcTemplate jdbc, ObjectMapper json,
                                            TransactionTemplate transactions) {
        this(jdbc, json, transactions, true);
    }

    PostgresPersonaRuntimeRepository(JdbcTemplate jdbc, ObjectMapper json,
                                     TransactionTemplate transactions, boolean initializeSchema) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.json = Objects.requireNonNull(json, "json");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        if (initializeSchema) {
            if (jdbc.getDataSource() == null) throw new IllegalStateException("persona persistence requires a datasource");
            new ResourceDatabasePopulator(new ClassPathResource("db/persona-runtime.sql")).execute(jdbc.getDataSource());
        }
    }

    @Override public Optional<PersonaProfile> findActive(String personaId) {
        return profiles("SELECT * FROM persona_profile_revision WHERE persona_id = ? AND status = 'ACTIVE'", personaId)
                .stream().findFirst();
    }
    @Override public Optional<PersonaProfile> findRevision(String personaId, String revision) {
        return profiles("SELECT * FROM persona_profile_revision WHERE persona_id = ? AND revision = ?", personaId, revision)
                .stream().findFirst();
    }

    @Override public Optional<UserProfile> find(String userId) {
        return jdbc.query("SELECT * FROM persona_user_profile WHERE user_id = ?",
                (rs, row) -> new UserProfile(rs.getString("user_id"),
                        rs.getString("revision"), rs.getString("display_name"),
                        rs.getString("locale"),
                        read(rs.getString("communication_preferences"), STRING_SET)),
                userId).stream().findFirst();
    }

    @Override public void save(UserProfile profile) {
        jdbc.update("""
                INSERT INTO persona_user_profile
                    (user_id, revision, display_name, locale,
                     communication_preferences, updated_at)
                VALUES (?, ?, ?, ?, CAST(? AS jsonb), CURRENT_TIMESTAMP)
                ON CONFLICT (user_id) DO UPDATE SET
                    revision = EXCLUDED.revision,
                    display_name = EXCLUDED.display_name,
                    locale = EXCLUDED.locale,
                    communication_preferences = EXCLUDED.communication_preferences,
                    updated_at = CURRENT_TIMESTAMP
                """, profile.userId(), profile.revision(), profile.displayName(),
                profile.locale(), write(profile.communicationPreferences()));
    }
    @Override public void publish(PersonaProfile profile) {
        publish(profile, systemMutation("PROFILE_PUBLISHED", profile.revision(), "persona-profile-v1", profile.personaId()));
    }
    @Override public void publish(PersonaProfile profile, PersonaMutation mutation) {
        requireActive(profile);
        transactions.executeWithoutResult(status -> {
            Optional<PersonaProfile> existing = findRevision(profile.personaId(), profile.revision());
            if (existing.isPresent()) {
                if (!sameRevision(existing.get(), profile)) throw new IllegalStateException("persona revision is immutable");
                return;
            }
            PersonaProfile previous = findActive(profile.personaId()).orElse(null);
            jdbc.update("UPDATE persona_profile_revision SET status = 'DEPRECATED' WHERE persona_id = ? AND status = 'ACTIVE'",
                    profile.personaId());
            int inserted = jdbc.update("""
                    INSERT INTO persona_profile_revision
                    (persona_id, revision, core_traits, speech_style, hard_boundaries,
                     canonical_source_revision, status, created_at)
                    VALUES (?, ?, CAST(? AS jsonb), CAST(? AS jsonb), CAST(? AS jsonb), ?, 'ACTIVE', ?)
                    """, profile.personaId(), profile.revision(), write(profile.coreTraits()), write(profile.speechStyle()),
                    write(profile.hardBoundaries()), profile.canonicalSourceRevision(), Timestamp.from(mutation.occurredAt()));
            if (inserted != 1) throw new IllegalStateException("persona revision was not published");
            appendAudit(profile.personaId(), PersonaAuditEvent.StateType.PROFILE, previous, profile,
                    mutation, revisionNumber(profile.revision()));
        });
    }

    @Override public Optional<RelationshipState> findRelationship(String userId) {
        return jdbc.query("SELECT * FROM relationship_state WHERE user_id = ?", (rs, row) -> relationship(rs), userId)
                .stream().findFirst();
    }
    @Override public RelationshipState save(RelationshipState state, long expectedVersion) {
        RelationshipState previous = findRelationship(state.userId()).orElse(null);
        return saveTransition(previous, state, expectedVersion,
                systemMutation("RELATIONSHIP_UPDATED", state.userId(), "relationship-policy-v1", state.userId()));
    }
    @Override public RelationshipState saveTransition(RelationshipState previous, RelationshipState state,
                                                      long expectedVersion, PersonaMutation mutation) {
        requireNextVersion(state.version(), expectedVersion, "relationship");
        transactions.executeWithoutResult(status -> {
            int changed = expectedVersion == 0
                    ? jdbc.update("""
                        INSERT INTO relationship_state
                        (user_id, stage, familiarity, trust, recent_tension, boundary_flags, version, source, updated_at)
                        VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?) ON CONFLICT (user_id) DO NOTHING
                        """, state.userId(), state.stage().value(), state.familiarity(), state.trust(),
                            state.recentTension(), write(state.boundaryFlags()), state.version(), state.source().name(),
                            Timestamp.from(state.updatedAt()))
                    : jdbc.update("""
                        UPDATE relationship_state SET stage = ?, familiarity = ?, trust = ?, recent_tension = ?,
                            boundary_flags = CAST(? AS jsonb), version = ?, source = ?, updated_at = ?
                        WHERE user_id = ? AND version = ?
                        """, state.stage().value(), state.familiarity(), state.trust(), state.recentTension(),
                            write(state.boundaryFlags()), state.version(), state.source().name(), Timestamp.from(state.updatedAt()),
                            state.userId(), expectedVersion);
            optimistic(changed, "relationship", state.userId());
            appendAudit(state.userId(), PersonaAuditEvent.StateType.RELATIONSHIP, previous, state,
                    mutation, state.version());
        });
        return state;
    }

    @Override public Optional<SceneState> findScene(String conversationId) {
        return jdbc.query("SELECT * FROM persona_scene_state WHERE conversation_id = ?", (rs, row) -> scene(rs), conversationId)
                .stream().findFirst();
    }
    @Override public SceneState save(SceneState state) {
        SceneState previous = findScene(state.conversationId()).orElse(null);
        long expected = previous == null ? 0 : previous.version();
        SceneState versioned = state.version() == expected + 1 ? state : new SceneState(state.conversationId(),
                state.type(), state.phase(), state.confidence(), state.evidenceCount(), state.startedAt(),
                state.expiresAt(), state.changedAt(), state.cooldownUntil(), expected + 1);
        return save(previous, versioned, expected,
                systemMutation("SCENE_UPDATED", state.conversationId(), "scene-policy-v1", "persona-runtime"));
    }
    @Override public SceneState save(SceneState previous, SceneState state, long expectedVersion, PersonaMutation mutation) {
        requireNextVersion(state.version(), expectedVersion, "scene");
        transactions.executeWithoutResult(status -> {
            int changed = expectedVersion == 0
                    ? jdbc.update("""
                        INSERT INTO persona_scene_state
                        (conversation_id, scene_type, phase, confidence, evidence_count, started_at,
                         expires_at, changed_at, cooldown_until, version)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (conversation_id) DO NOTHING
                        """, state.conversationId(), state.type().name(), state.phase().name(), state.confidence(),
                            state.evidenceCount(), ts(state.startedAt()), ts(state.expiresAt()), ts(state.changedAt()),
                            ts(state.cooldownUntil()), state.version())
                    : jdbc.update("""
                        UPDATE persona_scene_state SET scene_type = ?, phase = ?, confidence = ?, evidence_count = ?,
                            started_at = ?, expires_at = ?, changed_at = ?, cooldown_until = ?, version = ?
                        WHERE conversation_id = ? AND version = ?
                        """, state.type().name(), state.phase().name(), state.confidence(), state.evidenceCount(),
                            ts(state.startedAt()), ts(state.expiresAt()), ts(state.changedAt()), ts(state.cooldownUntil()),
                            state.version(), state.conversationId(), expectedVersion);
            optimistic(changed, "scene", state.conversationId());
            appendAudit(state.conversationId(), PersonaAuditEvent.StateType.SCENE, previous, state, mutation, state.version());
        });
        return state;
    }

    @Override public void save(PersonaOverride value) {
        save(value, value.version() - 1,
                systemMutation("OVERRIDE_SAVED", value.id(), "override-policy-v1", value.scopeId()));
    }
    @Override public void save(PersonaOverride value, long expectedVersion, PersonaMutation mutation) {
        requireNextVersion(value.version(), expectedVersion, "override");
        PersonaOverride previous = override(value.id()).orElse(null);
        transactions.executeWithoutResult(status -> {
            int changed = expectedVersion == 0
                    ? jdbc.update("""
                        INSERT INTO persona_runtime_override
                        (override_id, override_type, value, scope, scope_id, source, expires_at, version, created_at, updated_at)
                        VALUES (?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (override_id) DO NOTHING
                        """, value.id(), value.type().name(), write(value.value()), value.scope().name(), value.scopeId(),
                            value.source().name(), ts(value.expiresAt()), value.version(), ts(value.createdAt()), ts(mutation.occurredAt()))
                    : jdbc.update("""
                        UPDATE persona_runtime_override SET value = CAST(? AS jsonb), expires_at = ?, version = ?, updated_at = ?
                        WHERE override_id = ? AND version = ?
                        """, write(value.value()), ts(value.expiresAt()), value.version(), ts(mutation.occurredAt()),
                            value.id(), expectedVersion);
            optimistic(changed, "override", value.id());
            appendAudit(value.id(), PersonaAuditEvent.StateType.OVERRIDE, previous, value, mutation, value.version());
        });
    }
    @Override public List<PersonaOverride> active(String turnId, String sessionId, String clientId,
                                                 String userId, Instant now) {
        return jdbc.query("""
                SELECT * FROM persona_runtime_override
                WHERE (expires_at IS NULL OR expires_at > ?)
                  AND ((scope = 'TURN' AND scope_id = ?) OR (scope = 'SESSION' AND scope_id = ?)
                    OR (scope = 'CLIENT' AND scope_id = ?) OR (scope = 'ALL_CLIENTS' AND scope_id = ?))
                ORDER BY CASE scope WHEN 'ALL_CLIENTS' THEN 1 WHEN 'CLIENT' THEN 2
                                    WHEN 'SESSION' THEN 3 WHEN 'TURN' THEN 4 END ASC,
                         override_id
                """, (rs, row) -> override(rs), ts(now), turnId, sessionId, clientId, userId);
    }

    @Override public Optional<InteractionStateSnapshot> findInteraction(String turnId) {
        return jdbc.query("SELECT * FROM persona_interaction_state WHERE turn_id = ?", (rs, row) -> interaction(rs), turnId)
                .stream().findFirst();
    }
    @Override public InteractionStateSnapshot freeze(InteractionStateSnapshot snapshot, PersonaMutation mutation) {
        return transactions.execute(status -> {
            int inserted = jdbc.update("""
                    INSERT INTO persona_interaction_state
                    (turn_id, session_id, user_id, state_json, version, created_at)
                    VALUES (?, ?, ?, CAST(? AS jsonb), ?, ?) ON CONFLICT (turn_id) DO NOTHING
                    """, snapshot.turnId(), snapshot.sessionId(), snapshot.userId(), write(snapshot.state()),
                    snapshot.version(), ts(snapshot.createdAt()));
            if (inserted == 1) {
                appendAudit(snapshot.turnId(), PersonaAuditEvent.StateType.INTERACTION, null, snapshot,
                        mutation, snapshot.version());
                return snapshot;
            }
            InteractionStateSnapshot existing = findInteraction(snapshot.turnId()).orElseThrow();
            if (!existing.sameFrozenValue(snapshot)) throw new ConcurrentModificationException("interaction already frozen");
            return existing;
        });
    }

    @Override public List<PersonaAuditEvent> auditLog(String subjectId, PersonaAuditEvent.StateType stateType) {
        return jdbc.query("""
                SELECT * FROM persona_state_audit WHERE subject_id = ? AND state_type = ?
                ORDER BY created_at, audit_id
                """, (rs, row) -> audit(rs), subjectId, stateType.name());
    }

    private List<PersonaProfile> profiles(String sql, Object... args) {
        return jdbc.query(sql, (rs, row) -> new PersonaProfile(rs.getString("persona_id"), rs.getString("revision"),
                read(rs.getString("core_traits"), STRING_LIST), read(rs.getString("speech_style"), STRING_MAP),
                read(rs.getString("hard_boundaries"), STRING_LIST), rs.getString("canonical_source_revision"),
                PersonaProfile.Status.valueOf(rs.getString("status"))), args);
    }
    private RelationshipState relationship(ResultSet rs) throws SQLException {
        return new RelationshipState(rs.getString("user_id"), Relationship.fromValue(rs.getString("stage")),
                rs.getDouble("familiarity"), rs.getDouble("trust"), rs.getDouble("recent_tension"),
                read(rs.getString("boundary_flags"), STRING_SET), rs.getLong("version"),
                rs.getTimestamp("updated_at").toInstant(), RelationshipState.Source.valueOf(rs.getString("source")));
    }
    private SceneState scene(ResultSet rs) throws SQLException {
        return new SceneState(rs.getString("conversation_id"), SceneState.Type.valueOf(rs.getString("scene_type")),
                SceneState.Phase.valueOf(rs.getString("phase")), rs.getDouble("confidence"), rs.getInt("evidence_count"),
                instant(rs, "started_at"), instant(rs, "expires_at"), instant(rs, "changed_at"),
                instant(rs, "cooldown_until"), rs.getLong("version"));
    }
    private Optional<PersonaOverride> override(String id) {
        return jdbc.query("SELECT * FROM persona_runtime_override WHERE override_id = ?", (rs, row) -> override(rs), id)
                .stream().findFirst();
    }
    private PersonaOverride override(ResultSet rs) throws SQLException {
        return new PersonaOverride(rs.getString("override_id"), PersonaOverride.Type.valueOf(rs.getString("override_type")),
                read(rs.getString("value"), STRING_MAP), PersonaOverride.Scope.valueOf(rs.getString("scope")),
                rs.getString("scope_id"), PersonaOverride.Source.valueOf(rs.getString("source")), instant(rs, "expires_at"),
                rs.getLong("version"), instant(rs, "created_at"));
    }
    private InteractionStateSnapshot interaction(ResultSet rs) throws SQLException {
        return new InteractionStateSnapshot(rs.getString("turn_id"), rs.getString("session_id"), rs.getString("user_id"),
                read(rs.getString("state_json"), InteractionState.class), rs.getLong("version"), instant(rs, "created_at"));
    }
    private PersonaAuditEvent audit(ResultSet rs) throws SQLException {
        PersonaMutation mutation = new PersonaMutation(rs.getString("trigger_type"), rs.getString("trigger_id"),
                rs.getString("policy_revision"), rs.getString("actor_id"), instant(rs, "occurred_at"));
        return new PersonaAuditEvent(rs.getString("audit_id"), rs.getString("subject_id"),
                PersonaAuditEvent.StateType.valueOf(rs.getString("state_type")), rs.getString("old_value"),
                rs.getString("new_value"), mutation, rs.getLong("version"), instant(rs, "created_at"));
    }
    private void appendAudit(String subject, PersonaAuditEvent.StateType type, Object oldValue, Object newValue,
                             PersonaMutation mutation, long version) {
        jdbc.update("""
                INSERT INTO persona_state_audit
                (audit_id, subject_id, state_type, old_value, new_value, trigger_type, trigger_id,
                 policy_revision, actor_id, occurred_at, version, created_at)
                VALUES (?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), subject, type.name(), write(oldValue), write(newValue), mutation.triggerType(),
                mutation.triggerId(), mutation.policyRevision(), mutation.actorId(), ts(mutation.occurredAt()), version);
    }
    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new IllegalStateException("failed to serialize persona state", error); }
    }
    private <T> T read(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        catch (JsonProcessingException error) { throw new IllegalStateException("failed to deserialize persona state", error); }
    }
    private <T> T read(String value, TypeReference<T> type) {
        try { return json.readValue(value, type); }
        catch (JsonProcessingException error) { throw new IllegalStateException("failed to deserialize persona state", error); }
    }
    private static void optimistic(int changed, String type, String id) {
        if (changed != 1) throw new ConcurrentModificationException(type + " version conflict: " + id);
    }
    private static void requireNextVersion(long version, long expectedVersion, String type) {
        if (expectedVersion < 0 || version != expectedVersion + 1)
            throw new IllegalArgumentException(type + " version must equal expectedVersion + 1");
    }
    private static void requireActive(PersonaProfile profile) {
        if (profile.status() != PersonaProfile.Status.ACTIVE) throw new IllegalArgumentException("only active profiles can be published");
    }
    private static boolean sameRevision(PersonaProfile left, PersonaProfile right) {
        return left.personaId().equals(right.personaId()) && left.revision().equals(right.revision())
                && left.coreTraits().equals(right.coreTraits()) && left.speechStyle().equals(right.speechStyle())
                && left.hardBoundaries().equals(right.hardBoundaries())
                && left.canonicalSourceRevision().equals(right.canonicalSourceRevision());
    }
    private static long revisionNumber(String revision) { return Integer.toUnsignedLong(revision.hashCode()); }
    private static PersonaMutation systemMutation(String type, String id, String policy, String actor) {
        return new PersonaMutation(type, id, policy, actor, Instant.now());
    }
    private static Timestamp ts(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(ResultSet rs, String name) throws SQLException {
        Timestamp value = rs.getTimestamp(name); return value == null ? null : value.toInstant();
    }
}

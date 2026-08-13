package com.meguri.core.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@DisplayName("PostgreSQL external Skill repository contract")
class JdbcSkillCatalogRepositoryContractTest {
    private static final Instant NOW = Instant.parse("2026-08-13T00:00:00Z");

    @Test
    void should_upsert_validation_transition_for_the_same_immutable_digest() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        JdbcSkillCatalogRepository repository = new JdbcSkillCatalogRepository(
                jdbc, new ObjectMapper().findAndRegisterModules(), false);
        SkillManifest manifest = manifest();
        SkillRevision revision = new SkillRevision(
                manifest.id(), "a".repeat(64), "D:\\private\\package", "rev-1", manifest,
                new SkillValidationReport(
                        SkillValidationReport.ValidationState.VALID,
                        true, true, List.of(), NOW),
                NOW);

        repository.saveRevision(revision);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> values = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(sql.capture(), values.capture());
        assertThat(sql.getValue().replaceAll("\\s+", " "))
                .contains("ON CONFLICT (skill_id, digest) DO UPDATE SET revision = EXCLUDED.revision");
        assertThat((String) values.getValue()[2])
                .contains("\"state\":\"VALID\"")
                .contains("\"packagePath\":\"D:\\\\private\\\\package\"");
    }

    @Test
    void should_persist_metadata_only_audit_without_a_body_or_secret_column() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        JdbcSkillCatalogRepository repository = new JdbcSkillCatalogRepository(
                jdbc, new ObjectMapper().findAndRegisterModules(), false);

        repository.appendAudit(new SkillAuditEvent(
                "event-1", "modelscope", "@MiniMax-AI/minimax-pdf", manifest().id(),
                "b".repeat(64), "alice", "VALIDATE", "INVALID",
                "SKILL_DANGEROUS_BINARY", NOW));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), any(Object[].class));
        assertThat(sql.getValue().toLowerCase())
                .contains("external_skill_audit", "failure_code")
                .doesNotContain("body", "content", "secret_value");
    }

    @Test
    void should_restore_revision_history_active_binding_and_audit_after_repository_restart() {
        RestartableJdbcTemplate jdbc = new RestartableJdbcTemplate();
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        JdbcSkillCatalogRepository beforeRestart = new JdbcSkillCatalogRepository(jdbc, mapper, false);
        SkillManifest manifest = manifest();
        SkillRevision first = revision(manifest, "a".repeat(64), "rev-1", NOW);
        SkillRevision second = revision(manifest, "b".repeat(64), "rev-2", NOW.plusSeconds(60));
        beforeRestart.saveManifest(manifest);
        beforeRestart.saveRevision(first);
        beforeRestart.saveRevision(second);
        beforeRestart.saveBinding(new SkillBinding(
                manifest.id(), second.digest(), SkillBinding.ActivationState.ENABLED, NOW.plusSeconds(90)));
        beforeRestart.appendAudit(new SkillAuditEvent(
                "event-restart", manifest.sourceId(), manifest.externalId(), manifest.id(),
                second.digest(), "alice", "ACTIVATE", "ENABLED", null, NOW.plusSeconds(90)));

        JdbcSkillCatalogRepository afterRestart = new JdbcSkillCatalogRepository(jdbc, mapper, false);
        SkillCatalogEntry restored = afterRestart.find(manifest.id()).orElseThrow();

        assertThat(restored.revisions()).extracting(SkillRevision::sourceRevision)
                .containsExactly("rev-1", "rev-2");
        assertThat(restored.binding().state()).isEqualTo(SkillBinding.ActivationState.ENABLED);
        assertThat(restored.binding().activeDigest()).isEqualTo(second.digest());
        assertThat(restored.activeRevision()).isEqualTo(second);
        assertThat(afterRestart.audit()).singleElement().satisfies(event -> {
            assertThat(event.transition()).isEqualTo("ACTIVATE");
            assertThat(event.digest()).isEqualTo(second.digest());
        });
    }

    private static SkillRevision revision(
            SkillManifest manifest, String digest, String sourceRevision, Instant importedAt) {
        SkillManifest snapshot = new SkillManifest(
                manifest.id(), manifest.sourceId(), manifest.externalId(), manifest.name(),
                manifest.description(), manifest.license(), manifest.tags(), manifest.requires(),
                manifest.always(), sourceRevision, importedAt);
        return new SkillRevision(
                manifest.id(), digest, "D:\\private\\package\\" + digest, sourceRevision, snapshot,
                new SkillValidationReport(
                        SkillValidationReport.ValidationState.VALID,
                        true, true, List.of(), importedAt),
                importedAt);
    }

    private static SkillManifest manifest() {
        return new SkillManifest(
                "skill_1234567890abcdef12345678", "modelscope",
                "@MiniMax-AI/minimax-pdf", "Minimax PDF", "Parse PDF", "MIT",
                List.of("pdf"), SkillRequirement.none(), false, "rev-1", NOW);
    }

    private static final class RestartableJdbcTemplate extends JdbcTemplate {
        private final Map<String, String> manifests = new LinkedHashMap<>();
        private final Map<String, LinkedHashMap<String, String>> revisions = new LinkedHashMap<>();
        private final Map<String, BindingRow> bindings = new LinkedHashMap<>();
        private final List<SkillAuditEvent> audit = new ArrayList<>();

        @Override public int update(String sql, Object... args) {
            String normalized = sql.toLowerCase(java.util.Locale.ROOT);
            if (normalized.contains("insert into external_skill_revision")) {
                revisions.computeIfAbsent(String.valueOf(args[0]), ignored -> new LinkedHashMap<>())
                        .put(String.valueOf(args[1]), String.valueOf(args[2]));
            } else if (normalized.contains("insert into external_skill_binding")) {
                bindings.put(String.valueOf(args[0]), new BindingRow(
                        (String) args[1], String.valueOf(args[2]), (Timestamp) args[3]));
            } else if (normalized.contains("insert into external_skill_audit")) {
                audit.add(new SkillAuditEvent(
                        (String) args[0], (String) args[1], (String) args[2], (String) args[3],
                        (String) args[4], (String) args[5], (String) args[6], (String) args[7],
                        (String) args[8], ((Timestamp) args[9]).toInstant()));
            } else if (normalized.contains("insert into external_skill")) {
                manifests.put(String.valueOf(args[0]), String.valueOf(args[3]));
            } else {
                throw new AssertionError("unexpected update: " + sql);
            }
            return 1;
        }

        @Override public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
            String normalized = sql.toLowerCase(java.util.Locale.ROOT);
            try {
                if (normalized.contains("select manifest from external_skill where skill_id")) {
                    String value = manifests.get(String.valueOf(args[0]));
                    return value == null ? List.of() : List.of(map(mapper, resultSet(Map.of(1, value)), 0));
                }
                if (normalized.contains("select revision from external_skill_revision")) {
                    var values = revisions.getOrDefault(String.valueOf(args[0]), new LinkedHashMap<>()).values();
                    ArrayList<T> result = new ArrayList<>();
                    int row = 0;
                    for (String value : values) result.add(map(mapper, resultSet(Map.of(1, value)), row++));
                    return result;
                }
                if (normalized.contains("select active_digest")) {
                    BindingRow value = bindings.get(String.valueOf(args[0]));
                    if (value == null) return List.of();
                    return List.of(map(mapper, resultSet(Map.of(
                            "active_digest", value.activeDigest(),
                            "state", value.state(),
                            "updated_at", value.updatedAt())), 0));
                }
                throw new AssertionError("unexpected query: " + sql);
            } catch (SQLException error) {
                throw new IllegalStateException(error);
            }
        }

        @Override public <T> List<T> query(String sql, RowMapper<T> mapper) {
            if (!sql.toLowerCase(java.util.Locale.ROOT).contains("from external_skill_audit")) {
                return query(sql, mapper, new Object[0]);
            }
            try {
                ArrayList<T> result = new ArrayList<>();
                int row = 0;
                for (SkillAuditEvent event : audit) {
                    result.add(map(mapper, resultSet(Map.ofEntries(
                            Map.entry("event_id", event.eventId()),
                            Map.entry("source_id", event.sourceId()),
                            Map.entry("external_id", event.externalId()),
                            Map.entry("skill_id", event.skillId()),
                            Map.entry("digest", event.digest()),
                            Map.entry("actor", event.actor()),
                            Map.entry("transition", event.transition()),
                            Map.entry("status", event.status()),
                            Map.entry("failure_code", event.failureCode() == null ? "" : event.failureCode()),
                            Map.entry("occurred_at", Timestamp.from(event.occurredAt())))), row++));
                }
                return result;
            } catch (SQLException error) {
                throw new IllegalStateException(error);
            }
        }

        private static ResultSet resultSet(Map<?, ?> values) throws SQLException {
            ResultSet result = mock(ResultSet.class);
            for (Map.Entry<?, ?> entry : values.entrySet()) {
                if (entry.getKey() instanceof Integer index) {
                    org.mockito.Mockito.when(result.getString(index)).thenReturn((String) entry.getValue());
                } else if (entry.getValue() instanceof Timestamp timestamp) {
                    org.mockito.Mockito.when(result.getTimestamp(String.valueOf(entry.getKey()))).thenReturn(timestamp);
                } else {
                    org.mockito.Mockito.when(result.getString(String.valueOf(entry.getKey())))
                            .thenReturn((String) entry.getValue());
                }
            }
            return result;
        }

        private static <T> T map(RowMapper<T> mapper, ResultSet result, int row) throws SQLException {
            return mapper.mapRow(result, row);
        }

        private record BindingRow(String activeDigest, String state, Timestamp updatedAt) { }
    }
}

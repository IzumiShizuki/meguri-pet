package com.meguri.core.persona;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.dto.Relationship;
import com.meguri.core.persona.relationship.RelationshipState;
import com.meguri.core.persona.profile.UserProfileRepository;
import com.meguri.core.persona.runtime.PersonaOverride;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ConcurrentModificationException;
import java.util.Set;
import java.util.function.Consumer;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PersonaPostgresContractTest {
    @Test void schemaDefinesAllAuthoritiesVersionsAndAuditMetadata() throws Exception {
        String schema = Files.readString(Path.of("src/main/resources/db/persona-runtime.sql"));
        assertThat(schema).contains("persona_profile_revision", "relationship_state", "persona_scene_state",
                "persona_runtime_override", "persona_interaction_state", "persona_state_audit",
                "persona_user_profile", "communication_preferences jsonb NOT NULL",
                "subject_id text NOT NULL", "actor_id text NOT NULL", "occurred_at timestamptz NOT NULL",
                "version bigint NOT NULL");
        assertThat(schema).doesNotContain("user_id text NOT NULL, state_type");
    }

    @Test void postgresRelationshipRejectsStaleVersionBeforeWritingAudit() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked") Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transactions).executeWithoutResult(any());
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        var repository = new PostgresPersonaRuntimeRepository(jdbc,
                new ObjectMapper().findAndRegisterModules(), transactions, false);
        var previous = new RelationshipState("u", Relationship.SIBLING, 0, 0, 0, Set.of(), 0,
                Instant.EPOCH, RelationshipState.Source.SYSTEM);
        var next = new RelationshipState("u", Relationship.PURSUIT, 0, 0, 0, Set.of(), 1,
                Instant.EPOCH, RelationshipState.Source.USER_EXPLICIT);
        var mutation = new PersonaMutation("USER_EXPLICIT", "trigger", "policy-r1", "u", Instant.EPOCH);
        assertThatThrownBy(() -> repository.saveTransition(previous, next, 0, mutation))
                .isInstanceOf(ConcurrentModificationException.class).hasMessageContaining("relationship");
        verify(jdbc, times(1)).update(anyString(), any(Object[].class));
    }

    @Test void postgresOverrideQueryFeedsReducerFromLowestToHighestPriority() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        PostgresPersonaRuntimeRepository repository =
                new PostgresPersonaRuntimeRepository(jdbc,
                        new ObjectMapper().findAndRegisterModules(), transactions, false);
        UserProfileRepository userProfiles = repository;
        assertThat(userProfiles).isSameAs(repository);
        when(jdbc.query(anyString(),
                org.mockito.ArgumentMatchers.<RowMapper<PersonaOverride>>any(),
                any(Object[].class))).thenReturn(java.util.List.of());

        repository.active("turn", "session", "client", "user", Instant.EPOCH);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(),
                org.mockito.ArgumentMatchers.<RowMapper<PersonaOverride>>any(),
                any(Object[].class));
        assertThat(sql.getValue().replaceAll("\\s+", " "))
                .contains("WHEN 'ALL_CLIENTS' THEN 1 WHEN 'CLIENT' THEN 2")
                .contains("WHEN 'SESSION' THEN 3 WHEN 'TURN' THEN 4 END ASC");
    }
}

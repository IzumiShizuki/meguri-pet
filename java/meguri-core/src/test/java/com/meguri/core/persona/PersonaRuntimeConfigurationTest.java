package com.meguri.core.persona;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.dto.Mode;
import com.meguri.core.dto.Relationship;
import com.meguri.core.persona.presentation.PresentationResolver;
import com.meguri.core.persona.profile.PersonaProfile;
import com.meguri.core.persona.profile.PersonaProfileRepository;
import com.meguri.core.persona.prompt.PromptPolicyComposer;
import com.meguri.core.persona.relationship.RelationshipState;
import com.meguri.core.persona.relationship.RelationshipStateRepository;
import com.meguri.core.persona.relationship.RelationshipTransitionService;
import com.meguri.core.persona.runtime.ClientCapabilityState;
import com.meguri.core.persona.runtime.InteractionState;
import com.meguri.core.persona.runtime.InteractionStateRepository;
import com.meguri.core.persona.runtime.PersonaStateReducer;
import com.meguri.core.persona.runtime.RuntimeOverrideRepository;
import com.meguri.core.persona.scene.SceneStateRepository;
import com.meguri.core.persona.scene.SceneTransitionService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PersonaRuntimeConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PersonaRuntimeConfiguration.class);

    @Test
    void inMemoryModeProvidesOneCompleteRuntimeWithDeterministicDefaults() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-29T12:00:00Z"), ZoneOffset.UTC);
        runner.withBean(Clock.class, () -> clock)
                .withPropertyValues(
                        "meguri.persona.mode=in-memory",
                        "meguri.persona.default-temporal-mode=private",
                        "meguri.persona.policy-revision=policy-test-r7",
                        "meguri.persona.scene-ttl=12m",
                        "meguri.persona.scene-cooldown=45s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertUniqueRuntimeBeans(context);

                    PersonaProfileRepository profiles = context.getBean(PersonaProfileRepository.class);
                    RelationshipStateRepository relationships = context.getBean(RelationshipStateRepository.class);
                    SceneStateRepository scenes = context.getBean(SceneStateRepository.class);
                    assertThat(profiles.findActive("meguri")).get()
                            .extracting(PersonaProfile::revision).isEqualTo("meguri-default-v1");
                    assertThat(relationships.findRelationship("offline-user")).get()
                            .extracting(RelationshipState::stage).isEqualTo(Relationship.SIBLING);
                    assertThat(scenes.findScene("offline-conversation")).get()
                            .satisfies(scene -> assertThat(scene.type().name()).isEqualTo("CASUAL"));

                    var state = context.getBean(PersonaRuntimeFacade.class).resolve(request("turn-1", null));
                    assertThat(state.mode()).isEqualTo(Mode.PRIVATE);
                    assertThat(state.policyRevision()).isEqualTo("policy-test-r7");
                    assertThat(state.scene()).isNotNull();
                });
    }

    @Test
    void postgresModeUsesOneAggregateRepositoryForAllAuthorityInterfaces() {
        postgresRunner().run(context -> {
            assertThat(context).hasNotFailed();
            assertUniqueRuntimeBeans(context);
            PostgresPersonaRuntimeRepository aggregate = context.getBean(PostgresPersonaRuntimeRepository.class);
            assertThat(context.getBean(PersonaProfileRepository.class)).isSameAs(aggregate);
            assertThat(context.getBean(RelationshipStateRepository.class)).isSameAs(aggregate);
            assertThat(context.getBean(SceneStateRepository.class)).isSameAs(aggregate);
            assertThat(context.getBean(InteractionStateRepository.class)).isSameAs(aggregate);
            assertThat(context.getBean(RuntimeOverrideRepository.class)).isSameAs(aggregate);
        });
    }

    @Test
    void postgresModeFailsClosedWithoutJdbcTemplate() {
        runner.withPropertyValues("meguri.persona.mode=postgres")
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(TransactionTemplate.class, () -> mock(TransactionTemplate.class))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasMessageContaining("JdbcTemplate");
                });
    }

    @Test
    void resolvedTurnSnapshotDoesNotChangeAfterAuthoritativeRepositoriesAdvance() {
        runner.withBean(Clock.class, () -> Clock.fixed(Instant.parse("2026-07-29T12:00:00Z"), ZoneOffset.UTC))
                .run(context -> {
                    PersonaRuntimeFacade facade = context.getBean(PersonaRuntimeFacade.class);
                    var frozen = facade.resolve(request("turn-frozen", Mode.WORK));

                    context.getBean(PersonaProfileRepository.class).publish(new PersonaProfile(
                            "meguri", "meguri-next-v2", List.of("changed"), Map.of(), List.of(),
                            "canon-v2", PersonaProfile.Status.ACTIVE));
                    RelationshipTransitionService relationshipService = context.getBean(RelationshipTransitionService.class);
                    relationshipService.transition("offline-user", 0,
                            Relationship.PURSUIT, RelationshipState.Source.USER_EXPLICIT,
                            "approved-change", "policy-v2");

                    var later = facade.resolve(request("turn-later", Mode.WORK));
                    assertThat(frozen.persona().revision()).isEqualTo("meguri-default-v1");
                    assertThat(frozen.relationship().stage()).isEqualTo(Relationship.SIBLING);
                    assertThat(later.persona().revision()).isEqualTo("meguri-next-v2");
                    assertThat(later.relationship().stage()).isEqualTo(Relationship.PURSUIT);
                    assertThat(relationshipService.auditLog("offline-user")).hasSize(1);
                });
    }

    @Test
    void requestContractHasNoRelationshipProfileOverride() {
        assertThat(PersonaRuntimeFacade.Request.class.getRecordComponents())
                .extracting(component -> component.getName())
                .doesNotContain("relationshipProfile", "relationship_profile");
    }

    private ApplicationContextRunner postgresRunner() {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        try {
            when(dataSource.getConnection()).thenReturn(connection);
            when(connection.getAutoCommit()).thenReturn(true);
            when(connection.createStatement()).thenReturn(statement);
            when(statement.execute(org.mockito.ArgumentMatchers.anyString())).thenReturn(false);
            when(statement.getUpdateCount()).thenReturn(-1);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
        return runner.withPropertyValues("meguri.persona.mode=postgres")
                .withBean(JdbcTemplate.class, () -> new JdbcTemplate(dataSource))
                .withBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules())
                .withBean(TransactionTemplate.class, () -> mock(TransactionTemplate.class));
    }

    private static void assertUniqueRuntimeBeans(org.springframework.context.ApplicationContext context) {
        assertThat(context.getBeansOfType(PersonaProfileRepository.class)).hasSize(1);
        assertThat(context.getBeansOfType(RelationshipStateRepository.class)).hasSize(1);
        assertThat(context.getBeansOfType(RelationshipTransitionService.class)).hasSize(1);
        assertThat(context.getBeansOfType(SceneStateRepository.class)).hasSize(1);
        assertThat(context.getBeansOfType(SceneTransitionService.class)).hasSize(1);
        assertThat(context.getBeansOfType(InteractionStateRepository.class)).hasSize(1);
        assertThat(context.getBeansOfType(RuntimeOverrideRepository.class)).hasSize(1);
        assertThat(context.getBeansOfType(PersonaStateReducer.class)).hasSize(1);
        assertThat(context.getBeansOfType(PersonaRuntimeFacade.class)).hasSize(1);
        assertThat(context.getBeansOfType(PromptPolicyComposer.class)).hasSize(1);
        assertThat(context.getBeansOfType(PresentationResolver.class)).hasSize(1);
    }

    private static PersonaRuntimeFacade.Request request(String turnId, Mode mode) {
        return new PersonaRuntimeFacade.Request("meguri", "offline-user", "offline-conversation",
                "offline-session", turnId, mode,
                new InteractionState("conversation", InteractionState.Urgency.NORMAL, Set.of(), null, Set.of()),
                new ClientCapabilityState("test", true, true, true, false, true));
    }
}

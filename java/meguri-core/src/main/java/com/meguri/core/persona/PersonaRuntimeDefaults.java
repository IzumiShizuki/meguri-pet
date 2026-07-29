package com.meguri.core.persona;

import com.meguri.core.dto.Relationship;
import com.meguri.core.persona.profile.InMemoryPersonaProfileRepository;
import com.meguri.core.persona.profile.PersonaProfile;
import com.meguri.core.persona.profile.PersonaProfileRepository;
import com.meguri.core.persona.relationship.InMemoryRelationshipStateRepository;
import com.meguri.core.persona.relationship.RelationshipState;
import com.meguri.core.persona.relationship.RelationshipStateRepository;
import com.meguri.core.persona.relationship.RelationshipTransitionService;
import com.meguri.core.persona.runtime.InMemoryInteractionStateRepository;
import com.meguri.core.persona.runtime.InMemoryRuntimeOverrideRepository;
import com.meguri.core.persona.runtime.InteractionStateRepository;
import com.meguri.core.persona.runtime.RuntimeOverrideRepository;
import com.meguri.core.persona.runtime.PersonaStateReducer;
import com.meguri.core.persona.scene.InMemorySceneStateRepository;
import com.meguri.core.persona.scene.SceneState;
import com.meguri.core.persona.scene.SceneStateRepository;
import com.meguri.core.persona.scene.SceneTransitionService;

import java.time.Instant;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Deterministic offline authority used when no PostgreSQL persona store is selected. */
public final class PersonaRuntimeDefaults {
    private PersonaRuntimeDefaults() {
    }

    public static PersonaProfileRepository profiles() {
        InMemoryPersonaProfileRepository repository = new InMemoryPersonaProfileRepository();
        repository.publish(meguriProfile());
        return repository;
    }

    public static RelationshipStateRepository relationships() {
        return new DefaultingRelationshipRepository();
    }

    public static SceneStateRepository scenes() {
        return new DefaultingSceneRepository();
    }

    public static InteractionStateRepository interactions() {
        return new InMemoryInteractionStateRepository();
    }

    public static RuntimeOverrideRepository overrides() {
        return new InMemoryRuntimeOverrideRepository();
    }

    public static PersonaRuntimeFacade facade(Clock clock) {
        RelationshipStateRepository relationshipRepository = relationships();
        SceneStateRepository sceneRepository = scenes();
        return new PersonaRuntimeFacade(
                profiles(),
                new RelationshipTransitionService(relationshipRepository, clock),
                new SceneTransitionService(sceneRepository, clock,
                        Duration.ofMinutes(20), Duration.ofMinutes(2)),
                overrides(), interactions(), new PersonaStateReducer(), clock,
                "persona-policy-v1");
    }

    public static PersonaProfile meguriProfile() {
        return new PersonaProfile("meguri", "meguri-default-v1",
                List.of("warm", "curious", "reliable"),
                Map.of("tone", "gentle and direct", "language", "follow the user"),
                List.of("relationship_state_bypass", "untrusted_policy_override"),
                "meguri-canon-v1", PersonaProfile.Status.ACTIVE);
    }

    public static RelationshipState defaultRelationship(String userId) {
        return new RelationshipState(userId, Relationship.SIBLING, 0, 0, 0, Set.of(),
                0, Instant.EPOCH, RelationshipState.Source.SYSTEM);
    }

    public static SceneState defaultScene(String conversationId) {
        return new SceneState(conversationId, SceneState.Type.CASUAL, SceneState.Phase.ACTIVE,
                0.5, 2, Instant.EPOCH, Instant.MAX, Instant.EPOCH, Instant.EPOCH, 0);
    }

    private static final class DefaultingRelationshipRepository
            implements RelationshipStateRepository, PersonaAuditRepository {
        private final InMemoryRelationshipStateRepository delegate = new InMemoryRelationshipStateRepository();

        @Override
        public Optional<RelationshipState> findRelationship(String userId) {
            return Optional.of(delegate.findRelationship(userId).orElseGet(() -> defaultRelationship(userId)));
        }

        @Override
        public RelationshipState save(RelationshipState state, long expectedVersion) {
            return delegate.save(state, expectedVersion);
        }

        @Override
        public RelationshipState saveTransition(RelationshipState previous, RelationshipState state,
                                                long expectedVersion, PersonaMutation mutation) {
            return delegate.saveTransition(previous, state, expectedVersion, mutation);
        }

        @Override
        public List<PersonaAuditEvent> auditLog(String subjectId, PersonaAuditEvent.StateType stateType) {
            return delegate.auditLog(subjectId, stateType);
        }
    }

    private static final class DefaultingSceneRepository
            implements SceneStateRepository, PersonaAuditRepository {
        private final InMemorySceneStateRepository delegate = new InMemorySceneStateRepository();

        @Override
        public Optional<SceneState> findScene(String conversationId) {
            return Optional.of(delegate.findScene(conversationId).orElseGet(() -> defaultScene(conversationId)));
        }

        @Override
        public SceneState save(SceneState state) {
            return delegate.save(state);
        }

        @Override
        public SceneState save(SceneState previous, SceneState state, long expectedVersion,
                               PersonaMutation mutation) {
            return delegate.save(previous, state, expectedVersion, mutation);
        }

        @Override
        public List<PersonaAuditEvent> auditLog(String subjectId, PersonaAuditEvent.StateType stateType) {
            return delegate.auditLog(subjectId, stateType);
        }
    }
}

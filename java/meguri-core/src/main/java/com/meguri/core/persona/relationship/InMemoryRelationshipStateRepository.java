package com.meguri.core.persona.relationship;

import com.meguri.core.persona.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.ConcurrentModificationException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryRelationshipStateRepository implements RelationshipStateRepository, PersonaAuditRepository {
    private final ConcurrentHashMap<String, RelationshipState> states = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<PersonaAuditEvent> audits = new CopyOnWriteArrayList<>();
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    @Override public Optional<RelationshipState> findRelationship(String userId) { return Optional.ofNullable(states.get(userId)); }
    @Override public RelationshipState save(RelationshipState state, long expectedVersion) {
        states.compute(state.userId(), (id, current) -> {
            long actual = current == null ? 0 : current.version();
            if (actual != expectedVersion || state.version() != expectedVersion + 1) throw new ConcurrentModificationException("relationship version conflict");
            return state;
        });
        return state;
    }
    @Override public RelationshipState saveTransition(RelationshipState previous, RelationshipState state,
                                                      long expectedVersion, PersonaMutation mutation) {
        save(state, expectedVersion);
        audits.add(new PersonaAuditEvent(UUID.randomUUID().toString(), state.userId(),
                PersonaAuditEvent.StateType.RELATIONSHIP, json(previous), json(state), mutation,
                state.version(), mutation.occurredAt()));
        return state;
    }
    @Override public List<PersonaAuditEvent> auditLog(String subjectId, PersonaAuditEvent.StateType stateType) {
        return audits.stream().filter(a -> a.subjectId().equals(subjectId) && a.stateType() == stateType).toList();
    }
    private static String json(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (Exception error) { throw new IllegalStateException("failed to serialize persona audit", error); }
    }
}

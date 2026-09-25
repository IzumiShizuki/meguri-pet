package com.meguri.core.persona.scene;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.persona.*;
import java.time.Instant;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class InMemorySceneStateRepository implements SceneStateRepository, PersonaAuditRepository {
    private final ConcurrentHashMap<String, SceneState> states = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<PersonaAuditEvent> audits = new CopyOnWriteArrayList<>();
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    @Override public Optional<SceneState> findScene(String conversationId) { return Optional.ofNullable(states.get(conversationId)); }
    @Override public SceneState save(SceneState state) { states.put(state.conversationId(), state); return state; }
    @Override public SceneState save(SceneState previous, SceneState state, long expectedVersion, PersonaMutation mutation) {
        states.compute(state.conversationId(), (id, current) -> {
            long actual = current == null ? 0 : current.version();
            if (actual != expectedVersion || state.version() != expectedVersion + 1)
                throw new ConcurrentModificationException("scene version conflict");
            return state;
        });
        audits.add(new PersonaAuditEvent(UUID.randomUUID().toString(), state.conversationId(),
                PersonaAuditEvent.StateType.SCENE, json(previous), json(state), mutation,
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

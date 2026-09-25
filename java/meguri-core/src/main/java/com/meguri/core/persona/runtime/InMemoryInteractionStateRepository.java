package com.meguri.core.persona.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.persona.*;
import java.time.Instant;
import java.util.List;
import java.util.ConcurrentModificationException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class InMemoryInteractionStateRepository implements InteractionStateRepository, PersonaAuditRepository {
    private final ConcurrentHashMap<String, InteractionStateSnapshot> values = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<PersonaAuditEvent> audits = new CopyOnWriteArrayList<>();
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    @Override public Optional<InteractionStateSnapshot> findInteraction(String turnId) { return Optional.ofNullable(values.get(turnId)); }
    @Override public InteractionStateSnapshot freeze(InteractionStateSnapshot snapshot, PersonaMutation mutation) {
        InteractionStateSnapshot existing = values.putIfAbsent(snapshot.turnId(), snapshot);
        if (existing != null && !existing.sameFrozenValue(snapshot)) throw new ConcurrentModificationException("interaction already frozen");
        if (existing == null) audits.add(new PersonaAuditEvent(UUID.randomUUID().toString(), snapshot.turnId(),
                PersonaAuditEvent.StateType.INTERACTION, "null", json(snapshot), mutation, snapshot.version(), mutation.occurredAt()));
        return existing == null ? snapshot : existing;
    }
    @Override public List<PersonaAuditEvent> auditLog(String subjectId, PersonaAuditEvent.StateType stateType) {
        return audits.stream().filter(a -> a.subjectId().equals(subjectId) && a.stateType() == stateType).toList();
    }
    private static String json(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (Exception error) { throw new IllegalStateException("failed to serialize persona audit", error); }
    }
}

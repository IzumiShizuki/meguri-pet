package com.meguri.core.persona.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.persona.*;
import java.time.Instant;
import java.util.ConcurrentModificationException;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.UUID;

public final class InMemoryRuntimeOverrideRepository implements RuntimeOverrideRepository, PersonaAuditRepository {
    private final ConcurrentHashMap<String, PersonaOverride> values = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<PersonaAuditEvent> audits = new CopyOnWriteArrayList<>();
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    @Override public void save(PersonaOverride override) {
        save(override, override.version() - 1, new PersonaMutation("OVERRIDE_SAVED", override.id(),
                "override-policy-v1", override.scopeId(), override.createdAt()));
    }
    @Override public void save(PersonaOverride override, long expectedVersion, PersonaMutation mutation) {
        PersonaOverride[] previous = new PersonaOverride[1];
        values.compute(override.id(), (id, current) -> {
            previous[0] = current;
            long actual = current == null ? 0 : current.version();
            if (actual != expectedVersion || override.version() != expectedVersion + 1)
                throw new ConcurrentModificationException("override version conflict");
            return override;
        });
        audits.add(new PersonaAuditEvent(UUID.randomUUID().toString(), override.id(),
                PersonaAuditEvent.StateType.OVERRIDE, json(previous[0]), json(override), mutation,
                override.version(), mutation.occurredAt()));
    }
    @Override public List<PersonaOverride> active(String turnId, String sessionId, String clientId, String userId, Instant now) {
        return values.values().stream().filter(v -> matches(v, turnId, sessionId, clientId, userId))
                .filter(v -> v.activeAt(now))
                .sorted(Comparator.comparingInt((PersonaOverride v) -> v.scope().ordinal()).reversed()
                        .thenComparing(PersonaOverride::id)).toList();
    }
    private static boolean matches(PersonaOverride v, String turn, String session, String client, String user) {
        return switch (v.scope()) { case TURN -> v.scopeId().equals(turn); case SESSION -> v.scopeId().equals(session);
            case CLIENT -> v.scopeId().equals(client); case ALL_CLIENTS -> v.scopeId().equals(user); };
    }
    @Override public List<PersonaAuditEvent> auditLog(String subjectId, PersonaAuditEvent.StateType stateType) {
        return audits.stream().filter(a -> a.subjectId().equals(subjectId) && a.stateType() == stateType).toList();
    }
    private static String json(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (Exception error) { throw new IllegalStateException("failed to serialize persona audit", error); }
    }
}

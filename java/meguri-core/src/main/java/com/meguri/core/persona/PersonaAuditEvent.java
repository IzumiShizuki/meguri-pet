package com.meguri.core.persona;

import java.time.Instant;

public record PersonaAuditEvent(String auditId, String subjectId, StateType stateType,
                                String oldValueJson, String newValueJson,
                                PersonaMutation mutation, long version, Instant createdAt) {
    public enum StateType { PROFILE, RELATIONSHIP, SCENE, INTERACTION, OVERRIDE }
}

package com.meguri.core.persona;

import java.util.List;

public interface PersonaAuditRepository {
    List<PersonaAuditEvent> auditLog(String subjectId, PersonaAuditEvent.StateType stateType);
}

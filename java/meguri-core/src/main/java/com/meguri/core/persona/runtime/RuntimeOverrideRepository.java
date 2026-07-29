package com.meguri.core.persona.runtime;

import com.meguri.core.persona.PersonaMutation;
import java.time.Instant;
import java.util.List;

public interface RuntimeOverrideRepository {
    void save(PersonaOverride override);
    default void save(PersonaOverride override, long expectedVersion, PersonaMutation mutation) { save(override); }
    List<PersonaOverride> active(String turnId, String sessionId, String clientId, String userId, Instant now);
}

package com.meguri.core.persona.profile;

import com.meguri.core.persona.PersonaMutation;
import java.util.Optional;

public interface PersonaProfileRepository {
    Optional<PersonaProfile> findActive(String personaId);
    Optional<PersonaProfile> findRevision(String personaId, String revision);
    void publish(PersonaProfile profile);
    default void publish(PersonaProfile profile, PersonaMutation mutation) { publish(profile); }
}

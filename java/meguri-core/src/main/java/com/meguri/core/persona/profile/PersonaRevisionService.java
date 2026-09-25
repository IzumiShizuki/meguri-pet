package com.meguri.core.persona.profile;

import com.meguri.core.persona.PersonaMutation;

/** Publishes immutable revisions and prevents accidental revision reuse. */
public final class PersonaRevisionService {
    private final PersonaProfileRepository repository;
    public PersonaRevisionService(PersonaProfileRepository repository) { this.repository = repository; }
    public PersonaProfile publish(PersonaProfile profile) {
        repository.findRevision(profile.personaId(), profile.revision()).ifPresent(existing -> {
            if (!existing.equals(profile)) throw new IllegalStateException("persona revision is immutable");
        });
        repository.publish(profile); return profile;
    }
    public PersonaProfile publish(PersonaProfile profile, PersonaMutation mutation) {
        repository.findRevision(profile.personaId(), profile.revision()).ifPresent(existing -> {
            if (!existing.equals(profile)) throw new IllegalStateException("persona revision is immutable");
        });
        repository.publish(profile, mutation);
        return profile;
    }
}

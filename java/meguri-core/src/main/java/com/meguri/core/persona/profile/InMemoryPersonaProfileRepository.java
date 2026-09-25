package com.meguri.core.persona.profile;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryPersonaProfileRepository implements PersonaProfileRepository {
    private final ConcurrentHashMap<String, PersonaProfile> active = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PersonaProfile> revisions = new ConcurrentHashMap<>();

    @Override public Optional<PersonaProfile> findActive(String personaId) { return Optional.ofNullable(active.get(personaId)); }
    @Override public Optional<PersonaProfile> findRevision(String personaId, String revision) { return Optional.ofNullable(revisions.get(personaId + "@" + revision)); }

    @Override public void publish(PersonaProfile profile) {
        if (profile.status() != PersonaProfile.Status.ACTIVE) throw new IllegalArgumentException("only active profiles can be published");
        PersonaProfile existing = revisions.putIfAbsent(profile.personaId() + "@" + profile.revision(), profile);
        if (existing != null && !existing.equals(profile)) throw new IllegalStateException("persona revision is immutable");
        active.compute(profile.personaId(), (id, current) -> {
            if (current != null && current.revision().equals(profile.revision())) return current;
            return profile;
        });
    }
}

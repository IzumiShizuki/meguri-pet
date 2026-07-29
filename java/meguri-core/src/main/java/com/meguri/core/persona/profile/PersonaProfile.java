package com.meguri.core.persona.profile;

import java.util.List;
import java.util.Map;

/** Immutable, versioned persona constitution. */
public record PersonaProfile(String personaId, String revision, List<String> coreTraits,
                             Map<String, String> speechStyle, List<String> hardBoundaries,
                             String canonicalSourceRevision, Status status) {
    public PersonaProfile {
        if (personaId == null || personaId.isBlank()) throw new IllegalArgumentException("personaId must not be blank");
        if (revision == null || revision.isBlank()) throw new IllegalArgumentException("revision must not be blank");
        coreTraits = coreTraits == null ? List.of() : List.copyOf(coreTraits);
        speechStyle = speechStyle == null ? Map.of() : Map.copyOf(speechStyle);
        hardBoundaries = hardBoundaries == null ? List.of() : List.copyOf(hardBoundaries);
        canonicalSourceRevision = canonicalSourceRevision == null ? "unknown" : canonicalSourceRevision;
        status = status == null ? Status.ACTIVE : status;
    }

    public enum Status { ACTIVE, DEPRECATED }
}

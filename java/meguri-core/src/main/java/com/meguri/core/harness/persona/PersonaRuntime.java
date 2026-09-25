package com.meguri.core.harness.persona;

import com.meguri.core.dto.RuntimeState;
import com.meguri.core.dto.TurnRequest;

import java.util.Map;

/** Deterministic Persona reducer used before model generation. */
public interface PersonaRuntime {
    PersonaSnapshot reduce(TurnRequest request);

    record PersonaSnapshot(RuntimeState state, String revision, Map<String, Provenance> provenance) {
        public PersonaSnapshot {
            if (state == null) throw new IllegalArgumentException("state must not be null");
            if (revision == null || revision.isBlank()) throw new IllegalArgumentException("revision must not be blank");
            provenance = provenance == null ? Map.of() : Map.copyOf(provenance);
        }
    }

    record Provenance(String source, String scope, int priority) {
        public Provenance {
            if (source == null || source.isBlank()) throw new IllegalArgumentException("source must not be blank");
            scope = scope == null || scope.isBlank() ? "turn" : scope;
        }
    }
}

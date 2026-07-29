package com.meguri.core.persona.runtime;

import java.time.Instant;
import java.util.Map;

public record PersonaOverride(String id, Type type, Map<String, String> value, Scope scope,
                              String scopeId, Source source, Instant expiresAt, long version, Instant createdAt) {
    public PersonaOverride(String id, Type type, Map<String, String> value, Scope scope,
                           String scopeId, Source source, Instant expiresAt) {
        this(id, type, value, scope, scopeId, source, expiresAt, 1, Instant.EPOCH);
    }
    public PersonaOverride {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id must not be blank");
        if (type == Type.RELATIONSHIP) throw new IllegalArgumentException("relationship is not an override domain");
        value = value == null ? Map.of() : Map.copyOf(value);
        if (scope == null || scopeId == null || scopeId.isBlank()) throw new IllegalArgumentException("override scope is required");
        source = source == null ? Source.USER_EXPLICIT : source;
        if (version < 1) throw new IllegalArgumentException("override version must be positive");
        createdAt = createdAt == null ? Instant.EPOCH : createdAt;
    }
    public boolean activeAt(Instant now) { return expiresAt == null || expiresAt.isAfter(now); }
    public enum Type { STYLE, MODE, BOUNDARY, CAPABILITY, RELATIONSHIP }
    public enum Scope { TURN, SESSION, CLIENT, ALL_CLIENTS }
    public enum Source { USER_EXPLICIT, SYSTEM_EVENT }
}

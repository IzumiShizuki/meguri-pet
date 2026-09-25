package com.meguri.core.persona.relationship;

import com.meguri.core.dto.Relationship;
import java.time.Instant;
import java.util.Set;

public record RelationshipState(String userId, Relationship stage, double familiarity, double trust,
                                double recentTension, Set<String> boundaryFlags, long version,
                                Instant updatedAt, Source source) {
    public RelationshipState {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("userId must not be blank");
        if (stage == null) throw new IllegalArgumentException("stage must not be null");
        checkUnit(familiarity, "familiarity"); checkUnit(trust, "trust"); checkUnit(recentTension, "recentTension");
        boundaryFlags = boundaryFlags == null ? Set.of() : Set.copyOf(boundaryFlags);
        if (version < 0) throw new IllegalArgumentException("version must not be negative");
        updatedAt = updatedAt == null ? Instant.EPOCH : updatedAt;
        source = source == null ? Source.SYSTEM : source;
    }
    private static void checkUnit(double value, String field) { if (value < 0 || value > 1) throw new IllegalArgumentException(field + " must be in [0,1]"); }
    public enum Source { SYSTEM, USER_EXPLICIT, APPROVED_EVENT }
}

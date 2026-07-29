package com.meguri.core.persona;

import java.time.Instant;

/** Metadata required for every authoritative persona-state mutation. */
public record PersonaMutation(String triggerType, String triggerId, String policyRevision,
                              String actorId, Instant occurredAt) {
    public PersonaMutation {
        triggerType = required(triggerType, "triggerType");
        triggerId = required(triggerId, "triggerId");
        policyRevision = required(policyRevision, "policyRevision");
        actorId = required(actorId, "actorId");
        if (occurredAt == null) throw new IllegalArgumentException("occurredAt is required");
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }
}

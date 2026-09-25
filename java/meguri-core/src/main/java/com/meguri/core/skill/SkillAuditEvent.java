package com.meguri.core.skill;

import java.time.Instant;

public record SkillAuditEvent(
        String eventId, String sourceId, String externalId, String skillId, String digest,
        String actor, String transition, String status, String failureCode, Instant occurredAt) {
    public SkillAuditEvent {
        eventId = SkillManifest.required(eventId, "eventId");
        actor = SkillManifest.required(actor, "actor");
        transition = SkillManifest.required(transition, "transition");
        status = SkillManifest.required(status, "status");
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
    }
}

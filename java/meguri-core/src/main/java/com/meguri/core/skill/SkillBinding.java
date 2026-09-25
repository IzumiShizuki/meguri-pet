package com.meguri.core.skill;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;

public record SkillBinding(
        String skillId,
        String activeDigest,
        ActivationState state,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant updatedAt) {
    public enum ActivationState { DISABLED, ENABLED }
    public SkillBinding {
        skillId = SkillManifest.required(skillId, "skillId");
        activeDigest = activeDigest == null || activeDigest.isBlank() ? null : activeDigest.toLowerCase();
        state = state == null ? ActivationState.DISABLED : state;
        updatedAt = updatedAt == null ? Instant.now() : updatedAt;
        if (state == ActivationState.ENABLED && activeDigest == null) {
            throw new IllegalArgumentException("enabled binding requires activeDigest");
        }
    }
}

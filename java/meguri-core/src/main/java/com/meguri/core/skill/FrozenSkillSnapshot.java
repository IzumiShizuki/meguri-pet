package com.meguri.core.skill;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public record FrozenSkillSnapshot(
        String turnId,
        String capabilitySnapshotId,
        List<Candidate> candidates,
        Set<String> viewedSkillIds,
        Set<String> viewedReferences,
        int remainingTokens,
        int remainingSkillViews,
        int remainingReferenceViews,
        Instant frozenAt) {
    public FrozenSkillSnapshot {
        turnId = SkillManifest.required(turnId, "turnId");
        capabilitySnapshotId = capabilitySnapshotId == null ? "pending" : capabilitySnapshotId;
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        viewedSkillIds = viewedSkillIds == null ? Set.of() : Set.copyOf(viewedSkillIds);
        viewedReferences = viewedReferences == null ? Set.of() : Set.copyOf(viewedReferences);
        if (remainingTokens < 0 || remainingSkillViews < 0 || remainingReferenceViews < 0) {
            throw new IllegalArgumentException("Skill disclosure budgets must be non-negative");
        }
        frozenAt = frozenAt == null ? Instant.now() : frozenAt;
    }

    public record Candidate(String skillId, String revision, String digest, String name,
                            String description, List<String> tags, double score) {
        public Candidate { tags = tags == null ? List.of() : List.copyOf(tags); }
    }
}

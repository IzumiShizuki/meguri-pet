package com.meguri.core.skill;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;
import java.util.List;

public record SkillRevision(
        String skillId,
        String digest,
        String packagePath,
        String sourceRevision,
        SkillManifest manifest,
        SkillValidationReport report,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant importedAt) {
    public SkillRevision {
        skillId = SkillManifest.required(skillId, "skillId");
        digest = SkillManifest.required(digest, "digest").toLowerCase();
        if (!digest.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("digest must be SHA-256");
        packagePath = SkillManifest.required(packagePath, "packagePath");
        sourceRevision = sourceRevision == null ? "" : sourceRevision.trim();
        report = report == null ? new SkillValidationReport(
                SkillValidationReport.ValidationState.QUARANTINED, false, false, List.of(), Instant.now()) : report;
        importedAt = importedAt == null ? Instant.now() : importedAt;
    }

    public SkillRevision(
            String skillId, String digest, String packagePath, String sourceRevision,
            SkillValidationReport report, Instant importedAt) {
        this(skillId, digest, packagePath, sourceRevision, null, report, importedAt);
    }
}

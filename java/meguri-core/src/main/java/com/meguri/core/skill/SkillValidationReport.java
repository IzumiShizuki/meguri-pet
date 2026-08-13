package com.meguri.core.skill;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.time.Instant;
import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SkillValidationReport(
        ValidationState state,
        boolean compatible,
        boolean redistributable,
        List<Issue> issues,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant checkedAt) {
    public enum ValidationState { QUARANTINED, VALID, INVALID }
    public enum Severity { WARNING, ERROR }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Issue(String code, Severity severity, String message, String path) {
        public Issue {
            code = SkillManifest.required(code, "issue.code");
            severity = severity == null ? Severity.ERROR : severity;
            message = message == null ? "" : message;
            path = path == null || path.isBlank() ? null : path.replace('\\', '/');
        }
    }
    public SkillValidationReport {
        state = state == null ? ValidationState.QUARANTINED : state;
        issues = issues == null ? List.of() : List.copyOf(issues);
        checkedAt = checkedAt == null ? Instant.now() : checkedAt;
        if (state == ValidationState.INVALID) compatible = false;
    }
    public boolean validAndCompatible() { return state == ValidationState.VALID && compatible; }
}

package com.meguri.core.skill;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record SkillManifest(
        String id,
        String sourceId,
        String externalId,
        String name,
        String description,
        String license,
        List<String> tags,
        SkillRequirement requires,
        boolean always,
        String sourceRevision,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant sourceUpdatedAt) {
    public static final String EXTERNAL_TRUST = "UNTRUSTED_EXTERNAL";
    public SkillManifest {
        id = required(id, "id");
        sourceId = required(sourceId, "sourceId");
        externalId = required(externalId, "externalId");
        name = required(name, "name");
        description = description == null ? "" : description.trim();
        license = license == null || license.isBlank() ? null : license.trim();
        tags = tags == null ? List.of() : tags.stream().filter(Objects::nonNull)
                .map(String::trim).filter(v -> !v.isBlank()).distinct().toList();
        requires = requires == null ? SkillRequirement.none() : requires;
        sourceRevision = sourceRevision == null ? "" : sourceRevision.trim();
    }

    /** External catalog entries have one non-configurable trust class. */
    @JsonIgnore
    public String trust() { return EXTERNAL_TRUST; }

    static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}

package com.meguri.core.react;

import java.util.List;

/**
 * Planner-safe L1 projection of an external Skill frozen for one Turn.
 *
 * <p>Revision, digest, package paths, and disclosure-ledger state deliberately
 * stay server-side. The Skill ID is retained because it is the schema-valid
 * argument required by {@code meguri.skill.view}.</p>
 */
public record ReactSkillCandidate(
        String skillId,
        String name,
        String description,
        List<String> tags) {

    public ReactSkillCandidate {
        skillId = ReactValues.required(skillId, "skillId");
        name = ReactValues.required(name, "name");
        description = description == null ? "" : description.trim();
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}

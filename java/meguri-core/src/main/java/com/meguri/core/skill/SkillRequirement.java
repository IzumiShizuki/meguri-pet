package com.meguri.core.skill;

import java.util.List;

public record SkillRequirement(List<String> tools, List<String> env) {
    public SkillRequirement {
        tools = tools == null ? List.of() : tools.stream().map(String::trim).filter(v -> !v.isBlank()).distinct().toList();
        env = env == null ? List.of() : env.stream().map(String::trim).filter(v -> !v.isBlank()).distinct().toList();
    }

    public static SkillRequirement none() { return new SkillRequirement(List.of(), List.of()); }
}

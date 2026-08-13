package com.meguri.core.skill;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SkillSourceRegistry {
    private final Map<String, SkillSourcePlugin> sources;
    public SkillSourceRegistry(List<SkillSourcePlugin> plugins) {
        LinkedHashMap<String, SkillSourcePlugin> copy = new LinkedHashMap<>();
        for (SkillSourcePlugin plugin : plugins == null ? List.<SkillSourcePlugin>of() : plugins) {
            if (copy.putIfAbsent(plugin.id(), plugin) != null) {
                throw new IllegalArgumentException("duplicate Skill source: " + plugin.id());
            }
        }
        sources = Map.copyOf(copy);
    }
    public SkillSourcePlugin require(String id) {
        SkillSourcePlugin plugin = sources.get(id);
        if (plugin == null) throw new SkillSourcePlugin.SourceException(
                "SKILL_SOURCE_DISABLED", "Skill source is unavailable", false);
        return plugin;
    }
    public List<String> ids() { return sources.keySet().stream().sorted().toList(); }
}

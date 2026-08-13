package com.meguri.core.skill;

import java.util.List;

public record SkillCatalogEntry(SkillManifest manifest, List<SkillRevision> revisions, SkillBinding binding) {
    public SkillCatalogEntry {
        revisions = revisions == null ? List.of() : List.copyOf(revisions);
        binding = binding == null ? new SkillBinding(manifest.id(), null,
                SkillBinding.ActivationState.DISABLED, null) : binding;
    }

    public SkillRevision activeRevision() {
        if (binding.state() != SkillBinding.ActivationState.ENABLED) return null;
        return revisions.stream().filter(v -> v.digest().equals(binding.activeDigest())).findFirst().orElse(null);
    }
}

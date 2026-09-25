package com.meguri.core.skill;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class InMemorySkillCatalogRepository implements SkillCatalogRepository {
    private final Map<String, SkillManifest> manifests = new LinkedHashMap<>();
    private final Map<String, LinkedHashMap<String, SkillRevision>> revisions = new LinkedHashMap<>();
    private final Map<String, SkillBinding> bindings = new LinkedHashMap<>();
    private final List<SkillAuditEvent> audit = new ArrayList<>();

    @Override public synchronized void saveManifest(SkillManifest manifest) { manifests.put(manifest.id(), manifest); }
    @Override public synchronized void saveRevision(SkillRevision revision) {
        revisions.computeIfAbsent(revision.skillId(), ignored -> new LinkedHashMap<>())
                .put(revision.digest(), revision);
    }
    @Override public synchronized void saveBinding(SkillBinding binding) { bindings.put(binding.skillId(), binding); }
    @Override public synchronized Optional<SkillCatalogEntry> find(String skillId) {
        SkillManifest manifest = manifests.get(skillId);
        return manifest == null ? Optional.empty() : Optional.of(entry(manifest));
    }
    @Override public synchronized Optional<SkillCatalogEntry> findBySource(String sourceId, String externalId) {
        return manifests.values().stream()
                .filter(v -> v.sourceId().equals(sourceId) && v.externalId().equals(externalId))
                .findFirst().map(this::entry);
    }
    @Override public synchronized List<SkillCatalogEntry> list() {
        return manifests.values().stream().map(this::entry)
                .sorted(Comparator.comparing(v -> v.manifest().name(), String.CASE_INSENSITIVE_ORDER)).toList();
    }
    @Override public synchronized void appendAudit(SkillAuditEvent event) { audit.add(event); }
    @Override public synchronized List<SkillAuditEvent> audit() { return List.copyOf(audit); }

    private SkillCatalogEntry entry(SkillManifest manifest) {
        List<SkillRevision> values = List.copyOf(revisions.getOrDefault(
                manifest.id(), new LinkedHashMap<>()).values());
        return new SkillCatalogEntry(manifest, values,
                bindings.getOrDefault(manifest.id(), new SkillBinding(
                        manifest.id(), null, SkillBinding.ActivationState.DISABLED, null)));
    }
}

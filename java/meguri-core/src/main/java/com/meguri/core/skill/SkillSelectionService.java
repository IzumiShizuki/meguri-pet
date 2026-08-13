package com.meguri.core.skill;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class SkillSelectionService {
    private static final int MAX_CANDIDATES = 8;
    private final SkillCatalogRepository repository;

    public SkillSelectionService(SkillCatalogRepository repository) { this.repository = repository; }

    public FrozenSkillSnapshot freeze(String turnId, String capabilitySnapshotId, String query, boolean agentMode) {
        if (!agentMode) return new FrozenSkillSnapshot(turnId, capabilitySnapshotId,
                List.of(), Set.of(), Set.of(), 4096, 3, 6, Instant.now());
        Set<String> queryTokens = tokens(query);
        List<FrozenSkillSnapshot.Candidate> candidates = repository.list().stream()
                .filter(entry -> entry.binding().state() == SkillBinding.ActivationState.ENABLED)
                .filter(entry -> entry.activeRevision() != null && entry.activeRevision().report().validAndCompatible())
                .map(entry -> candidate(entry, queryTokens))
                .filter(candidate -> candidate.score() > 0)
                .sorted(Comparator.comparingDouble(FrozenSkillSnapshot.Candidate::score).reversed()
                        .thenComparing(FrozenSkillSnapshot.Candidate::skillId))
                .limit(MAX_CANDIDATES).toList();
        return new FrozenSkillSnapshot(turnId, capabilitySnapshotId,
                candidates, Set.of(), Set.of(), 4096, 3, 6, Instant.now());
    }

    private static FrozenSkillSnapshot.Candidate candidate(SkillCatalogEntry entry, Set<String> queryTokens) {
        SkillRevision active = entry.activeRevision();
        SkillManifest manifest = active.manifest() == null ? entry.manifest() : active.manifest();
        Set<String> title = tokens(manifest.name());
        Set<String> description = tokens(manifest.description());
        Set<String> tags = tokens(String.join(" ", manifest.tags()));
        double score = overlap(queryTokens, description)
                + 3.0 * overlap(queryTokens, title)
                + 2.0 * overlap(queryTokens, tags);
        String normalizedQuery = String.join(" ", queryTokens);
        if (!normalizedQuery.isBlank() && manifest.name().toLowerCase(Locale.ROOT).contains(normalizedQuery)) score += 5.0;
        return new FrozenSkillSnapshot.Candidate(manifest.id(), active.sourceRevision(), active.digest(),
                manifest.name(), manifest.description(), manifest.tags(), score);
    }

    private static double overlap(Set<String> query, Set<String> value) {
        if (query.isEmpty()) return 0;
        long matches = query.stream().filter(value::contains).count();
        return (double) matches / query.size();
    }

    private static Set<String> tokens(String value) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        for (String word : normalized.split("\\s+")) {
            if (!word.isBlank()) tokens.add(word);
            if (word.codePointCount(0, word.length()) > 2 && !word.matches("[\\x00-\\x7f]+")) {
                int[] codepoints = word.codePoints().toArray();
                for (int index = 0; index < codepoints.length - 1; index++) {
                    tokens.add(new String(codepoints, index, 2));
                }
            }
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(tokens));
    }
}

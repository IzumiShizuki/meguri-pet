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
    private final java.util.function.Supplier<List<SkillCatalogEntry>> inventory;

    public SkillSelectionService(SkillCatalogRepository repository) { this.inventory = repository::list; }
    public SkillSelectionService(SkillManagementService management) { this.inventory = management::list; }

    public FrozenSkillSnapshot freeze(String turnId, String capabilitySnapshotId, String query, boolean agentMode) {
        return freeze(turnId, capabilitySnapshotId, query, agentMode, null);
    }

    public FrozenSkillSnapshot freeze(String turnId, String capabilitySnapshotId, String query, boolean agentMode,
                                      Set<String> exposedCapabilities) {
        if (!agentMode) return new FrozenSkillSnapshot(turnId, capabilitySnapshotId,
                List.of(), Set.of(), Set.of(), 4096, 3, 6, Instant.now());
        Set<String> queryTokens = tokens(query);
        List<FrozenSkillSnapshot.Candidate> candidates = inventory.get().stream()
                .filter(entry -> entry.binding().state() == SkillBinding.ActivationState.ENABLED)
                .filter(entry -> entry.activeRevision() != null
                        && entry.activeRevision().report().state() == SkillValidationReport.ValidationState.VALID)
                .map(entry -> candidate(entry, queryTokens, exposedCapabilities))
                .filter(candidate -> candidate.score() > 0)
                .sorted(Comparator.comparing(FrozenSkillSnapshot.Candidate::dependenciesBlocked)
                        .thenComparing(Comparator.comparingDouble(FrozenSkillSnapshot.Candidate::score).reversed())
                        .thenComparing(FrozenSkillSnapshot.Candidate::skillId))
                .limit(MAX_CANDIDATES).toList();
        return new FrozenSkillSnapshot(turnId, capabilitySnapshotId,
                candidates, Set.of(), Set.of(), 4096, 3, 6, Instant.now());
    }

    private static FrozenSkillSnapshot.Candidate candidate(SkillCatalogEntry entry, Set<String> queryTokens,
                                                           Set<String> exposedCapabilities) {
        SkillRevision active = entry.activeRevision();
        SkillManifest manifest = active.manifest() == null ? entry.manifest() : active.manifest();
        double score = relevance(queryTokens, manifest.name(), manifest.description(), manifest.tags());
        List<String> reasons = active.report().issues().stream()
                .filter(issue -> issue.code().startsWith("SKILL_REQUIREMENT_")).limit(16)
                .map(issue -> boundedReason(issue.code() + ": " + issue.message())).distinct().toList();
        FrozenSkillSnapshot.Readiness readiness = FrozenSkillSnapshot.Readiness.NOT_ASSESSED;
        if (!active.report().compatible()) readiness = FrozenSkillSnapshot.Readiness.MISSING_DEPENDENCIES;
        else if (exposedCapabilities != null) {
            reasons = manifest.requires().tools().stream().map(SkillPackageValidator::capabilityId)
                    .filter(id -> !exposedCapabilities.contains(id)).map(SkillSelectionService::boundedReason).distinct().limit(16).toList();
            readiness = reasons.isEmpty() ? FrozenSkillSnapshot.Readiness.DEPENDENCIES_EXPOSED
                    : FrozenSkillSnapshot.Readiness.NOT_EXPOSED_THIS_TURN;
        }
        return new FrozenSkillSnapshot.Candidate(manifest.id(), active.sourceRevision(), active.digest(),
                manifest.name(), manifest.description(), manifest.tags(), score, readiness, reasons);
    }

    private static String boundedReason(String value) {
        int end = Math.min(value.length(), 256);
        if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end);
    }

    /** The disclosure query must use the same matching semantics as candidate selection. */
    static double relevance(String query, FrozenSkillSnapshot.Candidate candidate) {
        return relevance(tokens(query), candidate.name(), candidate.description(), candidate.tags());
    }

    private static double relevance(Set<String> queryTokens, String name, String summary, List<String> labels) {
        Set<String> title = tokens(name);
        Set<String> description = tokens(summary);
        Set<String> tags = tokens(String.join(" ", labels));
        double score = overlap(queryTokens, description)
                + 3.0 * overlap(queryTokens, title)
                + 2.0 * overlap(queryTokens, tags);
        String normalizedQuery = String.join(" ", queryTokens);
        if (!normalizedQuery.isBlank() && name.toLowerCase(Locale.ROOT).contains(normalizedQuery)) score += 5.0;
        return score;
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

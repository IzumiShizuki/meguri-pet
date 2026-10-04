package com.meguri.core.observation.screen;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Explainable semantic change between two observations in the same scope. */
public record SemanticDiff(
        String scopeId,
        Optional<Instant> previousObservedAt,
        Instant currentObservedAt,
        boolean initial,
        boolean activityChanged,
        boolean contentChanged,
        Set<String> addedSignals,
        Set<String> removedSignals) {

    public SemanticDiff {
        if (scopeId == null || scopeId.isBlank()) {
            throw new IllegalArgumentException("scopeId must not be blank");
        }
        previousObservedAt = previousObservedAt == null ? Optional.empty() : previousObservedAt;
        currentObservedAt = Objects.requireNonNull(currentObservedAt, "currentObservedAt must not be null");
        addedSignals = addedSignals == null ? Set.of() : Set.copyOf(addedSignals);
        removedSignals = removedSignals == null ? Set.of() : Set.copyOf(removedSignals);
        if (initial && previousObservedAt.isPresent()) {
            throw new IllegalArgumentException("initial diff must not contain a previous timestamp");
        }
        if (!initial && previousObservedAt.isEmpty()) {
            throw new IllegalArgumentException("non-initial diff must contain a previous timestamp");
        }
    }

    public boolean meaningful() {
        return initial || activityChanged || contentChanged
                || !addedSignals.isEmpty() || !removedSignals.isEmpty();
    }

    public List<String> changedDimensions() {
        java.util.ArrayList<String> dimensions = new java.util.ArrayList<>();
        if (activityChanged) {
            dimensions.add("activity");
        }
        if (contentChanged) {
            dimensions.add("content");
        }
        if (!addedSignals.isEmpty()) {
            dimensions.add("signals.added");
        }
        if (!removedSignals.isEmpty()) {
            dimensions.add("signals.removed");
        }
        if (initial) {
            dimensions.add("initial");
        }
        return List.copyOf(dimensions);
    }
}

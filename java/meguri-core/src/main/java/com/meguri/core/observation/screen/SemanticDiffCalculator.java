package com.meguri.core.observation.screen;

import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Deterministic, low-noise comparison for semantic screen states. */
public final class SemanticDiffCalculator {
    public SemanticDiff compare(ScreenContext previous, ScreenContext current) {
        Objects.requireNonNull(current, "current must not be null");
        if (previous == null) {
            return new SemanticDiff(
                    current.scopeId(),
                    Optional.empty(),
                    current.observedAt(),
                    true,
                    false,
                    false,
                    current.signals(),
                    Set.of());
        }
        if (!previous.scopeId().equals(current.scopeId())) {
            throw new IllegalArgumentException("screen contexts must use the same observation scope");
        }
        Set<String> addedSignals = new HashSet<>(current.signals());
        addedSignals.removeAll(previous.signals());
        Set<String> removedSignals = new HashSet<>(previous.signals());
        removedSignals.removeAll(current.signals());
        return new SemanticDiff(
                current.scopeId(),
                Optional.of(previous.observedAt()),
                current.observedAt(),
                false,
                !normalize(previous.activity()).equals(normalize(current.activity())),
                !normalize(previous.content()).equals(normalize(current.content())),
                addedSignals,
                removedSignals);
    }

    private static String normalize(String value) {
        return value == null
                ? ""
                : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}

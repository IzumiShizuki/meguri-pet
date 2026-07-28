package com.meguri.core.retrieval;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

public record RetrievalPlan(
        RetrievalMode mode,
        QueryRewriteResult query,
        Set<SourceType> sources,
        Map<SourceType, Integer> sourceBudgets,
        Map<SourceType, Integer> sourceSeats,
        boolean graphEnabled,
        int graphMaxHops,
        int totalItemLimit,
        Instant deadline) {
    public RetrievalPlan {
        mode = mode == null ? RetrievalMode.NONE : mode;
        if (query == null) throw new IllegalArgumentException("query is required");
        sources = sources == null ? Set.of() : Set.copyOf(sources);
        sourceBudgets = immutablePositiveMap(sourceBudgets, "source budget");
        sourceSeats = immutablePositiveMap(sourceSeats, "source seat");
        deadline = deadline == null ? Instant.now() : deadline;
    }

    private static Map<SourceType, Integer> immutablePositiveMap(
            Map<SourceType, Integer> values, String label) {
        EnumMap<SourceType, Integer> copy = new EnumMap<>(SourceType.class);
        if (values != null) {
            values.forEach((source, value) -> {
                if (source == null || value == null || value < 0) {
                    throw new IllegalArgumentException(label + " entries must be non-negative");
                }
                copy.put(source, value);
            });
        }
        return Map.copyOf(copy);
    }
}

package com.meguri.core.retrieval;

import java.util.LinkedHashSet;
import java.util.List;

/** One independently degradable retrieval lane. */
public record RetrievalLaneResult(
        SourceType sourceType,
        Status status,
        String provider,
        List<RetrievalItem> items,
        List<String> degradations) {
    public RetrievalLaneResult {
        if (sourceType == null || status == null) {
            throw new IllegalArgumentException("lane sourceType and status are required");
        }
        provider = provider == null || provider.isBlank() ? "none" : provider;
        items = items == null ? List.of() : List.copyOf(items);
        if (items.stream().anyMatch(item -> item.sourceType() != sourceType)) {
            throw new IllegalArgumentException("lane items must have the lane sourceType");
        }
        degradations = degradations == null
                ? List.of() : List.copyOf(new LinkedHashSet<>(degradations));
    }

    public static RetrievalLaneResult success(
            SourceType source, String provider, List<RetrievalItem> items) {
        return new RetrievalLaneResult(source, Status.OK, provider, items, List.of());
    }

    public static RetrievalLaneResult skipped(SourceType source, String reason) {
        return new RetrievalLaneResult(source, Status.SKIPPED, "none", List.of(), List.of(reason));
    }

    public static RetrievalLaneResult degraded(
            SourceType source, String provider, List<RetrievalItem> items, String reason) {
        return new RetrievalLaneResult(source, Status.DEGRADED, provider, items, List.of(reason));
    }

    public enum Status {
        OK,
        DEGRADED,
        UNAVAILABLE,
        SKIPPED
    }
}

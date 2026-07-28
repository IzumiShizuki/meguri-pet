package com.meguri.core.retrieval;

import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Immutable typed retrieval result frozen before generation. */
public record RetrievalBundle(
        String traceId,
        RetrievalPlan plan,
        Instant completedAt,
        List<RetrievalItem> items,
        Map<SourceType, RetrievalLaneResult> lanes,
        List<String> degradations) {
    public RetrievalBundle {
        if (traceId == null || traceId.isBlank() || plan == null) {
            throw new IllegalArgumentException("traceId and plan are required");
        }
        completedAt = completedAt == null ? Instant.now() : completedAt;
        items = items == null ? List.of() : List.copyOf(items);
        if (items.stream().anyMatch(item -> !traceId.equals(item.traceId()))) {
            throw new IllegalArgumentException("all items must belong to the bundle trace");
        }
        EnumMap<SourceType, RetrievalLaneResult> laneCopy = new EnumMap<>(SourceType.class);
        if (lanes != null) laneCopy.putAll(lanes);
        lanes = Map.copyOf(laneCopy);
        degradations = degradations == null
                ? List.of() : List.copyOf(new LinkedHashSet<>(degradations));
    }
}

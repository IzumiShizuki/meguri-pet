package com.meguri.core.retrieval;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Content-bearing trace used for deterministic retrieval replay and diagnosis. */
public record RetrievalTrace(
        String traceId,
        RetrievalPlan plan,
        String snapshotId,
        long revision,
        Instant validAt,
        String algorithmRevision,
        Instant completedAt,
        List<RetrievalItem> items,
        Map<SourceType, RetrievalLaneResult> lanes,
        Map<String, RankTrace> ranks,
        List<RetrievalCandidateTrace> candidates,
        List<String> degradations) {
    public static final String ALGORITHM_REVISION = "retrieval-runtime-v2";

    public RetrievalTrace {
        if (traceId == null || traceId.isBlank() || plan == null
                || snapshotId == null || snapshotId.isBlank() || revision < 0) {
            throw new IllegalArgumentException("trace identity, plan, snapshot and revision are required");
        }
        validAt = validAt == null ? Instant.EPOCH : validAt;
        algorithmRevision = algorithmRevision == null || algorithmRevision.isBlank()
                ? "legacy-unversioned" : algorithmRevision.trim();
        completedAt = completedAt == null ? Instant.now() : completedAt;
        items = items == null ? List.of() : List.copyOf(items);
        lanes = lanes == null ? Map.of() : Map.copyOf(lanes);
        ranks = ranks == null ? Map.of() : Map.copyOf(ranks);
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        degradations = degradations == null ? List.of() : List.copyOf(degradations);
    }

    public static RetrievalTrace capture(
            RetrievalBundle bundle, RetrievalContext context) {
        Map<String, RankTrace> ranks = bundle.items().stream().collect(
                java.util.stream.Collectors.toMap(
                        item -> item.sourceType() + ":" + item.sourceId(),
                        RetrievalItem::rankTrace,
                        (left, right) -> left,
                        LinkedHashMap::new));
        return new RetrievalTrace(
                bundle.traceId(), bundle.plan(), context.snapshotId(), context.revision(),
                context.validAt(), ALGORITHM_REVISION, bundle.completedAt(), bundle.items(),
                bundle.lanes(), ranks, candidateDecisions(bundle), bundle.degradations());
    }

    public RetrievalBundle replayBundle() {
        return new RetrievalBundle(
                traceId, plan, completedAt, items, lanes, degradations);
    }

    private static List<RetrievalCandidateTrace> candidateDecisions(
            RetrievalBundle bundle) {
        java.util.Set<RetrievalItem> selected = java.util.Set.copyOf(bundle.items());
        java.util.ArrayList<RetrievalCandidateTrace> decisions = new java.util.ArrayList<>();
        for (SourceType source : SourceType.values()) {
            RetrievalLaneResult lane = bundle.lanes().get(source);
            if (lane == null) continue;
            java.util.HashSet<String> seen = new java.util.HashSet<>();
            for (int index = 0; index < lane.items().size(); index++) {
                RetrievalItem item = lane.items().get(index);
                boolean duplicate = !seen.add(item.sourceId());
                boolean included = !duplicate && selected.contains(item);
                String reason = included
                        ? "selected"
                        : duplicate ? "source_duplicate" : "bundle_budget_or_seat_limit";
                decisions.add(new RetrievalCandidateTrace(
                        source, item.sourceId(), index + 1, item.rankTrace(), item.citation(),
                        included, reason));
            }
        }
        return List.copyOf(decisions);
    }
}

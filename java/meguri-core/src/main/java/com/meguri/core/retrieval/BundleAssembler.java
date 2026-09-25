package com.meguri.core.retrieval;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Applies source seats and hard caps without comparing incomparable source scores. */
public final class BundleAssembler {
    public RetrievalBundle assemble(
            String traceId, RetrievalPlan plan, List<RetrievalLaneResult> laneResults) {
        if (laneResults == null) laneResults = List.of();
        EnumMap<SourceType, RetrievalLaneResult> lanes = mergeLanes(laneResults);
        EnumMap<SourceType, List<RetrievalItem>> candidates = new EnumMap<>(SourceType.class);
        lanes.forEach((source, lane) -> candidates.put(source, deduplicateAndSort(lane.items())));

        List<RetrievalItem> selected = new ArrayList<>();
        EnumMap<SourceType, Integer> taken = new EnumMap<>(SourceType.class);

        // Seats are reservations, not score boosts. Graph and Hybrid share KNOWLEDGE here.
        for (SourceType source : SourceType.values()) {
            int seat = Math.min(plan.sourceSeats().getOrDefault(source, 0),
                    plan.sourceBudgets().getOrDefault(source, 0));
                take(source, seat, candidates, selected, taken, plan);
        }
        boolean progressed = true;
        while (selected.size() < plan.totalItemLimit() && progressed) {
            progressed = false;
            for (SourceType source : SourceType.values()) {
                int before = selected.size();
                take(source, 1, candidates, selected, taken, plan);
                progressed |= selected.size() > before;
            }
        }
        List<String> degradations = lanes.values().stream()
                .flatMap(lane -> lane.degradations().stream())
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new), List::copyOf));
        return new RetrievalBundle(traceId, plan, Instant.now(), selected, lanes, degradations);
    }

    private static EnumMap<SourceType, RetrievalLaneResult> mergeLanes(
            List<RetrievalLaneResult> laneResults) {
        EnumMap<SourceType, List<RetrievalLaneResult>> grouped = new EnumMap<>(SourceType.class);
        for (RetrievalLaneResult lane : laneResults) {
            grouped.computeIfAbsent(lane.sourceType(), ignored -> new ArrayList<>()).add(lane);
        }
        EnumMap<SourceType, RetrievalLaneResult> merged = new EnumMap<>(SourceType.class);
        grouped.forEach((source, values) -> {
            List<RetrievalItem> items = values.stream().flatMap(value -> value.items().stream()).toList();
            List<String> degradations = values.stream()
                    .flatMap(value -> value.degradations().stream()).distinct().toList();
            RetrievalLaneResult.Status status = values.stream()
                    .map(RetrievalLaneResult::status)
                    .max(Comparator.comparingInt(BundleAssembler::severity))
                    .orElse(RetrievalLaneResult.Status.SKIPPED);
            String provider = values.stream().map(RetrievalLaneResult::provider)
                    .distinct().sorted().reduce((left, right) -> left + "+" + right).orElse("none");
            merged.put(source, new RetrievalLaneResult(source, status, provider, items, degradations));
        });
        return merged;
    }

    private static List<RetrievalItem> deduplicateAndSort(List<RetrievalItem> input) {
        Map<String, RetrievalItem> unique = new LinkedHashMap<>();
        input.stream().sorted(Comparator
                        .comparingDouble((RetrievalItem item) -> item.rankTrace().rrfScore()).reversed()
                        .thenComparing(RetrievalItem::sourceId))
                .forEach(item -> unique.putIfAbsent(item.sourceId(), item));
        return List.copyOf(unique.values());
    }

    private static void take(SourceType source, int count,
                             Map<SourceType, List<RetrievalItem>> candidates,
                             List<RetrievalItem> selected, Map<SourceType, Integer> taken,
                             RetrievalPlan plan) {
        int cap = candidates.containsKey(source) ? candidates.get(source).size() : 0;
        int already = taken.getOrDefault(source, 0);
        int sourceLimit = Math.min(cap, plan.sourceBudgets().getOrDefault(source, 0));
        for (int i = 0; i < count
                && selected.size() < plan.totalItemLimit()
                && already < sourceLimit; i++) {
            selected.add(candidates.get(source).get(already++));
        }
        taken.put(source, already);
    }

    private static int severity(RetrievalLaneResult.Status status) {
        return switch (status) {
            case UNAVAILABLE -> 4;
            case DEGRADED -> 3;
            case OK -> 2;
            case SKIPPED -> 1;
        };
    }
}

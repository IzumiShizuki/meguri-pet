package com.meguri.core.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Weighted reciprocal-rank fusion within one source; raw ranks remain in RankTrace. */
public final class WeightedRrfFusion {
    private final int rankConstant;
    private final Map<RankSignal, Double> weights;

    public WeightedRrfFusion() {
        this(60, Map.of(
                RankSignal.KEYWORD, 1.0,
                RankSignal.VECTOR, 1.0,
                RankSignal.STRUCTURED, 1.0));
    }

    public WeightedRrfFusion(int rankConstant, Map<RankSignal, Double> weights) {
        if (rankConstant < 1) throw new IllegalArgumentException("rankConstant must be positive");
        this.rankConstant = rankConstant;
        EnumMap<RankSignal, Double> copy = new EnumMap<>(RankSignal.class);
        if (weights != null) {
            weights.forEach((signal, weight) -> {
                if (signal == null || weight == null || !Double.isFinite(weight) || weight < 0) {
                    throw new IllegalArgumentException("RRF weights must be finite and non-negative");
                }
                copy.put(signal, weight);
            });
        }
        this.weights = Map.copyOf(copy);
    }

    public List<RetrievalItem> fuse(Map<RankSignal, List<RetrievalItem>> rankedLists) {
        if (rankedLists == null || rankedLists.isEmpty()) return List.of();
        SourceType source = null;
        Map<String, Aggregate> aggregates = new LinkedHashMap<>();
        for (RankSignal signal : RankSignal.values()) {
            List<RetrievalItem> list = rankedLists.getOrDefault(signal, List.of());
            for (int index = 0; index < list.size(); index++) {
                RetrievalItem item = list.get(index);
                if (source == null) source = item.sourceType();
                if (item.sourceType() != source) {
                    throw new IllegalArgumentException("RRF can only fuse candidates within one source");
                }
                Aggregate aggregate = aggregates.computeIfAbsent(
                        item.sourceId(), ignored -> new Aggregate(item));
                aggregate.ranks.putIfAbsent(signal, index + 1);
            }
        }
        List<RetrievalItem> fused = new ArrayList<>();
        for (Aggregate aggregate : aggregates.values()) {
            double score = aggregate.ranks.entrySet().stream()
                    .mapToDouble(entry -> weights.getOrDefault(entry.getKey(), 0.0)
                            / (rankConstant + entry.getValue()))
                    .sum();
            fused.add(aggregate.item.withRankTrace(new RankTrace(aggregate.ranks, score)));
        }
        fused.sort(Comparator
                .comparingDouble((RetrievalItem item) -> item.rankTrace().rrfScore()).reversed()
                .thenComparing(RetrievalItem::sourceId));
        return List.copyOf(fused);
    }

    private static final class Aggregate {
        private final RetrievalItem item;
        private final EnumMap<RankSignal, Integer> ranks = new EnumMap<>(RankSignal.class);

        private Aggregate(RetrievalItem item) {
            this.item = item;
        }
    }
}

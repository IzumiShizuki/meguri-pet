package com.meguri.core.retrieval;

import java.util.EnumMap;
import java.util.Map;

/** Original per-retriever ranks plus the derived RRF score. */
public record RankTrace(Map<RankSignal, Integer> ranks, double rrfScore,
                        Map<RankSignal, Double> scores) {
    public RankTrace(Map<RankSignal, Integer> ranks, double rrfScore) {
        this(ranks, rrfScore, Map.of());
    }

    public RankTrace {
        EnumMap<RankSignal, Integer> copy = new EnumMap<>(RankSignal.class);
        if (ranks != null) {
            ranks.forEach((signal, rank) -> {
                if (signal == null || rank == null || rank < 1) {
                    throw new IllegalArgumentException("rank trace entries require a positive rank");
                }
                copy.put(signal, rank);
            });
        }
        ranks = Map.copyOf(copy);
        if (!Double.isFinite(rrfScore) || rrfScore < 0) {
            throw new IllegalArgumentException("rrfScore must be finite and non-negative");
        }
        EnumMap<RankSignal, Double> scoreCopy = new EnumMap<>(RankSignal.class);
        if (scores != null) {
            scores.forEach((signal, score) -> {
                if (signal == null || score == null || !Double.isFinite(score)) {
                    throw new IllegalArgumentException("rank scores must be finite");
                }
                scoreCopy.put(signal, score);
            });
        }
        scores = Map.copyOf(scoreCopy);
    }

    public static RankTrace empty() {
        return new RankTrace(Map.of(), 0, Map.of());
    }
}

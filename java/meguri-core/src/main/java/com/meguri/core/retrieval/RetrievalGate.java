package com.meguri.core.retrieval;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/** Final server-side validation; client plans must pass through this gate. */
public final class RetrievalGate {
    public static final int MAX_GRAPH_HOPS = 3;
    public static final int MAX_TOTAL_ITEMS = 64;
    public static final int MAX_SOURCE_ITEMS = 32;

    public RetrievalPlan validate(RetrievalPlan requested) {
        if (requested == null) throw new IllegalArgumentException("retrieval plan is required");
        if (requested.graphMaxHops() < 1 || requested.graphMaxHops() > MAX_GRAPH_HOPS) {
            throw new IllegalArgumentException("graphMaxHops must be between 1 and 3");
        }
        if (requested.totalItemLimit() < 0 || requested.totalItemLimit() > MAX_TOTAL_ITEMS) {
            throw new IllegalArgumentException("totalItemLimit exceeds server bounds");
        }
        if (requested.mode() == RetrievalMode.FAST && requested.sources().contains(SourceType.WEB)) {
            throw new IllegalArgumentException("FAST mode forbids Web retrieval");
        }
        if (requested.mode() == RetrievalMode.NONE
                && (!requested.sources().isEmpty() || requested.graphEnabled())) {
            throw new IllegalArgumentException("NONE mode forbids all retrieval");
        }
        validateBudget(requested.sourceBudgets(), requested.totalItemLimit(), "budget");
        validateBudget(requested.sourceSeats(), requested.totalItemLimit(), "seat");
        if (!requested.sources().containsAll(requested.sourceBudgets().keySet())
                || !requested.sources().containsAll(requested.sourceSeats().keySet())) {
            throw new IllegalArgumentException("budgets and seats require an enabled source");
        }
        if (requested.graphEnabled() && !requested.sources().contains(SourceType.KNOWLEDGE)) {
            throw new IllegalArgumentException("Graph requires the Knowledge source");
        }
        return requested;
    }

    /** Clamps untrusted source limits before constructing a plan. */
    public Map<SourceType, Integer> boundedBudgets(Map<SourceType, Integer> requested) {
        EnumMap<SourceType, Integer> bounded = new EnumMap<>(SourceType.class);
        if (requested != null) {
            requested.forEach((source, value) -> {
                if (source != null && value != null && value > 0) {
                    bounded.put(source, Math.min(value, MAX_SOURCE_ITEMS));
                }
            });
        }
        return Map.copyOf(bounded);
    }

    private static void validateBudget(Map<SourceType, Integer> values, int total, String label) {
        int sum = 0;
        for (int value : values.values()) {
            if (value > MAX_SOURCE_ITEMS) {
                throw new IllegalArgumentException("source " + label + " exceeds server bounds");
            }
            sum = Math.addExact(sum, value);
        }
        if (label.equals("seat") && sum > total) {
            throw new IllegalArgumentException("source seats exceed totalItemLimit");
        }
    }
}

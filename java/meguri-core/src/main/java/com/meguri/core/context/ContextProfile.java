package com.meguri.core.context;

import java.util.EnumMap;
import java.util.Map;

/** Model-specific limits; ratios and source allocations are configuration, not runtime constants. */
public record ContextProfile(
        String modelId,
        int maxInputTokens,
        int reservedOutputTokens,
        int protocolOverheadTokens,
        double softCompactionRatio,
        double hardCompactionRatio,
        Map<ContextBundle.BlockType, SourceBudget> sourceBudgets) {
    public ContextProfile {
        if (modelId == null || modelId.isBlank()) throw new IllegalArgumentException("modelId must not be blank");
        if (maxInputTokens <= reservedOutputTokens + protocolOverheadTokens) {
            throw new IllegalArgumentException("model profile has no usable input budget");
        }
        if (softCompactionRatio <= 0 || hardCompactionRatio <= softCompactionRatio || hardCompactionRatio > 1) {
            throw new IllegalArgumentException("compaction ratios must satisfy 0 < soft < hard <= 1");
        }
        EnumMap<ContextBundle.BlockType, SourceBudget> normalized =
                new EnumMap<>(ContextBundle.BlockType.class);
        for (ContextBundle.BlockType type : ContextBundle.BlockType.values()) {
            normalized.put(type, sourceBudgets == null
                    ? new SourceBudget(0, Integer.MAX_VALUE)
                    : sourceBudgets.getOrDefault(type, new SourceBudget(0, Integer.MAX_VALUE)));
        }
        sourceBudgets = Map.copyOf(normalized);
    }

    public int usableInputTokens() {
        return maxInputTokens - reservedOutputTokens - protocolOverheadTokens;
    }

    public record SourceBudget(int minTokens, int maxTokens) {
        public SourceBudget {
            if (minTokens < 0 || maxTokens < minTokens) {
                throw new IllegalArgumentException("source budget must satisfy 0 <= min <= max");
            }
        }
    }
}

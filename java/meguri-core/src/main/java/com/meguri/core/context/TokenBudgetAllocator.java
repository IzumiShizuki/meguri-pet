package com.meguri.core.context;

import com.meguri.core.llm.ProviderTokenizer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Applies per-source limits and deterministic hard-threshold compaction using the target tokenizer. */
public final class TokenBudgetAllocator {
    private final ProviderTokenizer tokenizer;

    public TokenBudgetAllocator(ProviderTokenizer tokenizer) {
        this.tokenizer = tokenizer;
    }

    Allocation allocate(ContextProfile profile, List<ContextCandidate> input) {
        List<Measured> kept = new ArrayList<>();
        for (ContextCandidate candidate : input) {
            kept.add(new Measured(candidate, tokenizer.count(candidate.content())));
        }
        int before = total(kept);
        List<ContextBundle.Truncation> truncations = new ArrayList<>();

        for (ContextBundle.BlockType type : ContextBundle.BlockType.values()) {
            ContextProfile.SourceBudget budget = profile.sourceBudgets().get(type);
            compactType(kept, type, budget.maxTokens(), budget.minTokens(), truncations);
        }

        int hard = Math.max(1, (int) Math.floor(profile.usableInputTokens() * profile.hardCompactionRatio()));
        while (total(kept) > hard) {
            Measured removable = kept.stream()
                    .filter(item -> !item.candidate.required())
                    .filter(item -> item.candidate.automaticRehydration()
                            || sourceTokens(kept, item.candidate.type()) - item.tokens
                            >= profile.sourceBudgets().get(item.candidate.type()).minTokens())
                    .min(removalOrder()).orElse(null);
            if (removable == null) {
                throw new IllegalStateException("required context exceeds the model hard token threshold");
            }
            kept.remove(removable);
            truncations.add(truncation(removable,
                    removable.candidate.automaticRehydration()
                            ? "automatic_rehydration_budget" : "hard_threshold_low_priority_block"));
        }

        EnumMap<ContextBundle.BlockType, Integer> bySource = new EnumMap<>(ContextBundle.BlockType.class);
        List<ContextBundle.Block> blocks = kept.stream().map(item -> {
            bySource.merge(item.candidate.type(), item.tokens, Integer::sum);
            return new ContextBundle.Block(item.candidate.type(), item.candidate.sourceIds(),
                    item.candidate.trust(), item.tokens, item.candidate.content());
        }).toList();
        int soft = Math.max(1, (int) Math.floor(profile.usableInputTokens() * profile.softCompactionRatio()));
        ContextBundle.Budget budget = new ContextBundle.Budget(
                profile.usableInputTokens(), soft, hard, total(kept), tokenizer.name(), Map.copyOf(bySource));
        return new Allocation(blocks, budget, List.copyOf(truncations), before);
    }

    private static void compactType(List<Measured> kept, ContextBundle.BlockType type, int max, int min,
                                    List<ContextBundle.Truncation> truncations) {
        while (sourceTokens(kept, type) > max) {
            Measured removable = kept.stream()
                    .filter(item -> item.candidate.type() == type && !item.candidate.required())
                    .filter(item -> item.candidate.automaticRehydration()
                            || sourceTokens(kept, type) - item.tokens >= min)
                    .min(Comparator.comparingInt(item -> item.candidate.recency())).orElse(null);
            if (removable == null) break;
            kept.remove(removable);
            truncations.add(truncation(removable,
                    removable.candidate.automaticRehydration()
                            ? "automatic_rehydration_source_max" : "source_max_exceeded"));
        }
        if (sourceTokens(kept, type) > max) {
            throw new IllegalStateException("required " + type + " context exceeds its source maximum");
        }
    }

    private static Comparator<Measured> removalOrder() {
        return Comparator.comparingInt((Measured item) -> item.candidate.automaticRehydration() ? -1 : 0)
                .thenComparingInt(item -> priority(item.candidate.type()))
                .thenComparingInt(item -> item.candidate.recency());
    }

    private static int priority(ContextBundle.BlockType type) {
        return switch (type) {
            case TOOL_RESULT -> 0;
            case RECENT_RAW -> 1;
            case SUMMARY -> 2;
            case RETRIEVAL -> 3;
            case MEMORY -> 4;
            case REHYDRATED -> 5;
            case RELATIONSHIP -> 6;
            case PERSONA -> 7;
        };
    }

    private static int sourceTokens(List<Measured> values, ContextBundle.BlockType type) {
        return values.stream().filter(value -> value.candidate.type() == type)
                .mapToInt(value -> value.tokens).sum();
    }

    private static int total(List<Measured> values) {
        return values.stream().mapToInt(value -> value.tokens).sum();
    }

    private static ContextBundle.Truncation truncation(Measured item, String reason) {
        return new ContextBundle.Truncation(
                item.candidate.type(), item.candidate.sourceIds(), reason, item.tokens);
    }

    private record Measured(ContextCandidate candidate, int tokens) { }

    record Allocation(
            List<ContextBundle.Block> blocks,
            ContextBundle.Budget budget,
            List<ContextBundle.Truncation> truncations,
            int tokensBefore) { }
}

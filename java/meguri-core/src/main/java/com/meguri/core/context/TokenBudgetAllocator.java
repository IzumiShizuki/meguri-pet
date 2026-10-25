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
        List<ContextBundle.Truncation> truncations = new ArrayList<>();

        ContextProfile.SourceBudget retrievalBudget =
                profile.sourceBudgets().get(ContextBundle.BlockType.RETRIEVAL);
        compactType(kept, ContextBundle.BlockType.RETRIEVAL,
                retrievalBudget.maxTokens(), retrievalBudget.minTokens(), truncations);
        // External pages are reduced before the history precompression threshold is measured;
        // transient search results must not enqueue a conversation-history job.
        int before = total(kept);
        for (ContextBundle.BlockType type : ContextBundle.BlockType.values()) {
            if (type == ContextBundle.BlockType.RETRIEVAL) continue;
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

    private void compactType(List<Measured> kept, ContextBundle.BlockType type, int max, int min,
                             List<ContextBundle.Truncation> truncations) {
        if (type == ContextBundle.BlockType.RETRIEVAL) {
            compactRetrieved(kept, max, truncations);
            return;
        }
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

    private void compactRetrieved(
            List<Measured> kept, int max, List<ContextBundle.Truncation> truncations) {
        if (sourceTokens(kept, ContextBundle.BlockType.RETRIEVAL) <= max) {
            return;
        }
        int requiredTokens = kept.stream()
                .filter(item -> item.candidate.type() == ContextBundle.BlockType.RETRIEVAL)
                .filter(item -> item.candidate.required())
                .mapToInt(item -> item.tokens)
                .sum();
        if (requiredTokens > max) {
            throw new IllegalStateException(
                    "required RETRIEVAL context exceeds its source maximum");
        }

        List<Measured> optional = kept.stream()
                .filter(item -> item.candidate.type() == ContextBundle.BlockType.RETRIEVAL)
                .filter(item -> !item.candidate.required())
                .sorted(Comparator.comparingInt(item -> item.candidate.recency()))
                .toList();
        int remainingBudget = max - requiredTokens;
        int remainingItems = optional.size();
        for (Measured item : optional) {
            int share = remainingItems <= 0 ? 0 : remainingBudget / remainingItems;
            int target = Math.min(item.tokens, share);
            String bounded = target >= item.tokens
                    ? item.candidate.content()
                    : truncateRetrievedExcerpt(item.candidate.content(), target);
            int boundedTokens = tokenizer.count(bounded);
            if (boundedTokens > target) {
                bounded = truncateRetrievedExcerpt(bounded, target);
                boundedTokens = tokenizer.count(bounded);
            }

            int index = kept.indexOf(item);
            if (index >= 0 && boundedTokens == 0) {
                kept.remove(index);
            } else if (index >= 0 && boundedTokens < item.tokens) {
                ContextCandidate original = item.candidate;
                kept.set(index, new Measured(new ContextCandidate(
                        original.type(), original.sourceIds(), original.trust(), bounded,
                        original.required(), original.recency(), original.automaticRehydration()),
                        boundedTokens));
            }
            if (boundedTokens < item.tokens) {
                truncations.add(new ContextBundle.Truncation(
                        item.candidate.type(), item.candidate.sourceIds(),
                        "retrieval_excerpt_source_max", item.tokens - boundedTokens));
            }
            remainingBudget -= boundedTokens;
            remainingItems--;
        }
    }

    private String truncateRetrievedExcerpt(String content, int maxTokens) {
        if (content == null || content.isBlank() || maxTokens <= 0) return "";
        String normalized = content.trim();
        int citationStart = normalized.indexOf("\n[Citation:");
        String excerpt = citationStart < 0
                ? normalized : normalized.substring(0, citationStart).trim();
        String attribution = citationStart < 0
                ? "" : normalized.substring(citationStart).trim();
        String marker = "[…retrieved excerpt shortened…]";

        int attributionTokens = tokenizer.count(attribution);
        if (attributionTokens > maxTokens) {
            attribution = tokenizer.truncate(attribution, maxTokens);
            attributionTokens = tokenizer.count(attribution);
        }
        int markerTokens = attribution.isBlank() || excerpt.isBlank()
                ? 0 : tokenizer.count(marker);
        int excerptTokens = Math.max(0, maxTokens - attributionTokens - markerTokens);
        String shortened = tokenizer.truncate(excerpt, excerptTokens).trim();
        String result = shortened;
        if (!shortened.isBlank() && !attribution.isBlank()) {
            result += "\n" + marker + "\n" + attribution;
        } else if (shortened.isBlank()) {
            result = attribution;
        } else {
            result += "\n" + marker;
        }

        while (tokenizer.count(result) > maxTokens && excerptTokens > 0) {
            int excess = tokenizer.count(result) - maxTokens;
            excerptTokens = Math.max(0, excerptTokens - Math.max(1, excess));
            shortened = tokenizer.truncate(excerpt, excerptTokens).trim();
            result = shortened.isBlank() ? attribution
                    : attribution.isBlank()
                            ? shortened + "\n" + marker
                            : shortened + "\n" + marker + "\n" + attribution;
        }
        return result;
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

package com.meguri.core.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One tokenizer-backed budget across the system prompt and all context lanes. */
public final class GlobalPromptBudget {
    private static final List<String> OPTIONAL_LANES = List.of(
            "web_results", "recent_context", "long_term_memories", "canon_examples");

    private final ObjectMapper objectMapper;
    private final ProviderTokenizer tokenizer;
    private final int maximumTokens;
    private final int precompressThreshold;
    private final int hardTarget;

    public GlobalPromptBudget(ObjectMapper objectMapper, ProviderTokenizer tokenizer, int maximumTokens) {
        if (maximumTokens < 256) throw new IllegalArgumentException("prompt token budget must be at least 256");
        this.objectMapper = objectMapper;
        this.tokenizer = tokenizer;
        this.maximumTokens = maximumTokens;
        this.precompressThreshold = Math.max(1, (int) Math.floor(maximumTokens * 0.70d));
        this.hardTarget = Math.max(precompressThreshold, (int) Math.floor(maximumTokens * 0.90d));
    }

    public BudgetedPrompt fit(String systemPrompt, Map<String, Object> context) {
        return fit(systemPrompt, context, OPTIONAL_LANES);
    }

    /**
     * Fits one provider request while preserving every lane not explicitly marked optional.
     * Optional lanes are trimmed in the supplied order when equally large.
     */
    public BudgetedPrompt fit(
            String systemPrompt,
            Map<String, Object> context,
            List<String> optionalLanes) {
        Map<String, Object> mutable = mutableCopy(context);
        List<String> removable = optionalLanes == null ? List.of() : List.copyOf(optionalLanes);
        String beforeJson = write(mutable);
        int before = total(systemPrompt, beforeJson);
        if (before <= precompressThreshold) {
            return new BudgetedPrompt(beforeJson, before, before, false, tokenizer.name());
        }

        while (total(systemPrompt, write(mutable)) > hardTarget
                && removeLargestOptionalItem(mutable, removable)) {
            // Remove whole lowest-priority records so provenance boundaries stay intact.
        }
        String json = write(mutable);
        int consumed = total(systemPrompt, json);
        if (consumed > hardTarget) {
            throw new LlmProviderException(
                    "mandatory prompt context exceeds the configured global token budget");
        }
        return new BudgetedPrompt(json, before, consumed, true, tokenizer.name());
    }

    private boolean removeLargestOptionalItem(
            Map<String, Object> context,
            List<String> optionalLanes) {
        String selected = null;
        int selectedTokens = -1;
        for (String lane : optionalLanes) {
            Object value = context.get(lane);
            if (value == null || value instanceof List<?> list && list.isEmpty()) continue;
            int tokens = tokenizer.count(write(Map.of(lane, value)));
            if (tokens > selectedTokens) {
                selected = lane;
                selectedTokens = tokens;
            }
        }
        if (selected == null) return false;
        Object selectedValue = context.get(selected);
        if (selectedValue instanceof List<?> list && list.size() > 1) {
            @SuppressWarnings("unchecked")
            List<Object> values = (List<Object>) list;
            values.removeLast();
        } else {
            context.remove(selected);
        }
        return true;
    }

    private int total(String systemPrompt, String contextJson) {
        return tokenizer.count(systemPrompt == null ? "" : systemPrompt)
                + tokenizer.count(contextJson)
                + 12;
    }

    private Map<String, Object> mutableCopy(Map<String, Object> context) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (context == null) return copy;
        context.forEach((key, value) -> copy.put(
                key, value instanceof List<?> list ? new ArrayList<>(list) : value));
        return copy;
    }

    private String write(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new LlmProviderException("failed to encode LLM context", error);
        }
    }

    public record BudgetedPrompt(
            String json,
            int tokensBefore,
            int tokensAfter,
            boolean precompressed,
            String tokenizer) { }
}

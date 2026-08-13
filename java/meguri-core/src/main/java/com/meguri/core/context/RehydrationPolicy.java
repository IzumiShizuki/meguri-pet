package com.meguri.core.context;

/** Independent budget and enablement policy for automatic fact recovery. */
public record RehydrationPolicy(
        boolean enabled,
        int maxFacts,
        int maxSourceSpans,
        int maxTokens,
        int messagesBefore,
        int messagesAfter,
        String policyRevision) {
    public RehydrationPolicy {
        if (maxFacts < 0 || maxSourceSpans < 0 || maxTokens < 0
                || messagesBefore < 0 || messagesAfter < 0) {
            throw new IllegalArgumentException("rehydration limits must not be negative");
        }
        if (enabled && (maxFacts < 1 || maxSourceSpans < 1 || maxTokens < 1)) {
            throw new IllegalArgumentException("enabled rehydration requires positive budgets");
        }
        if (policyRevision == null || policyRevision.isBlank()) {
            throw new IllegalArgumentException("policyRevision must not be blank");
        }
        policyRevision = policyRevision.trim();
    }

    public static RehydrationPolicy disabled() {
        return new RehydrationPolicy(false, 0, 0, 0, 0, 0, "rehydration-v1-disabled");
    }

    public static RehydrationPolicy thinkDefault() {
        return new RehydrationPolicy(true, 3, 3, 1_024, 2, 2, "rehydration-v1-think-default");
    }
}

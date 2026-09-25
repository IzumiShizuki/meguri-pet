package com.meguri.core.metrics;

/** Numeric usage reported by one model response without retaining prompt or reply content. */
public record PromptCacheUsage(
        boolean usageReported,
        boolean cacheReported,
        long promptTokens,
        long outputTokens,
        long totalTokens,
        long cacheHitTokens,
        long cacheMissTokens) {

    public PromptCacheUsage {
        promptTokens = Math.max(0L, promptTokens);
        outputTokens = Math.max(0L, outputTokens);
        totalTokens = Math.max(0L, totalTokens);
        cacheHitTokens = Math.max(0L, cacheHitTokens);
        cacheMissTokens = Math.max(0L, cacheMissTokens);
    }

    public static PromptCacheUsage unavailable() {
        return new PromptCacheUsage(false, false, 0L, 0L, 0L, 0L, 0L);
    }
}

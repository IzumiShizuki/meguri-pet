package com.meguri.core.metrics;

/** Model-provider boundary for recording cache telemetry without coupling callers to persistence. */
public interface PromptCacheMetricsRecorder {
    void registerPrompt(String provider, String model, String prompt);

    void recordSuccess(String operation, String model, PromptCacheUsage usage);

    void recordFailure(String operation, String model);

    static PromptCacheMetricsRecorder noop() {
        return NoopHolder.INSTANCE;
    }

    final class NoopHolder {
        private static final PromptCacheMetricsRecorder INSTANCE = new PromptCacheMetricsRecorder() {
            @Override public void registerPrompt(String provider, String model, String prompt) {}
            @Override public void recordSuccess(String operation, String model, PromptCacheUsage usage) {}
            @Override public void recordFailure(String operation, String model) {}
        };

        private NoopHolder() {}
    }
}

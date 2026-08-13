package com.meguri.core.context;

/** Comparable evaluation fields for shadow/replay reports; TTFT is intentionally nullable. */
public record StructuredContextEvaluationMetrics(
        double tokenCompressionRatio,
        double constraintRetention,
        double stateAccuracy,
        double temporalCausalAccuracy,
        double exactRecall,
        double rehydrationHitRate,
        double unnecessaryRehydrationRate,
        int totalPromptTokens,
        Long ttftMillis,
        String ttftStatus,
        double estimatedCost,
        int fallbackFailures) {
    public StructuredContextEvaluationMetrics {
        checkRate(tokenCompressionRatio, "tokenCompressionRatio");
        checkRate(constraintRetention, "constraintRetention");
        checkRate(stateAccuracy, "stateAccuracy");
        checkRate(temporalCausalAccuracy, "temporalCausalAccuracy");
        checkRate(exactRecall, "exactRecall");
        checkRate(rehydrationHitRate, "rehydrationHitRate");
        checkRate(unnecessaryRehydrationRate, "unnecessaryRehydrationRate");
        if (totalPromptTokens < 0 || estimatedCost < 0d || fallbackFailures < 0) {
            throw new IllegalArgumentException("evaluation counts must not be negative");
        }
        if (ttftStatus == null || ttftStatus.isBlank()) {
            throw new IllegalArgumentException("ttftStatus must not be blank");
        }
        ttftStatus = ttftStatus.trim();
        if ("NOT_MEASURED".equals(ttftStatus) && ttftMillis != null) {
            throw new IllegalArgumentException("NOT_MEASURED cannot carry a TTFT value");
        }
        if (ttftMillis != null && ttftMillis < 0) {
            throw new IllegalArgumentException("ttftMillis must not be negative");
        }
    }

    public static StructuredContextEvaluationMetrics notMeasured(int totalPromptTokens) {
        return new StructuredContextEvaluationMetrics(
                0d, 0d, 0d, 0d, 0d, 0d, 0d, totalPromptTokens,
                null, "NOT_MEASURED", 0d, 0);
    }

    private static void checkRate(double value, String field) {
        if (value < 0d || value > 1d || Double.isNaN(value)) {
            throw new IllegalArgumentException(field + " must be between 0 and 1");
        }
    }
}

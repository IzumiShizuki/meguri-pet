package com.meguri.core.context;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredContextEvaluationMetricsTest {
    @Test
    void missingMatchedTtftBaselineIsExplicitlyNotMeasured() {
        StructuredContextEvaluationMetrics metrics =
                StructuredContextEvaluationMetrics.notMeasured(123);

        assertThat(metrics.ttftStatus()).isEqualTo("NOT_MEASURED");
        assertThat(metrics.ttftMillis()).isNull();
        assertThat(metrics.totalPromptTokens()).isEqualTo(123);
    }
}

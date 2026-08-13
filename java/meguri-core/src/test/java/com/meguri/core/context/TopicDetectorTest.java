package com.meguri.core.context;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TopicDetectorTest {
    @Test
    void detectsFromRawInputAndDoesNotNeedRetrievalRewrite() {
        TopicSignal signal = new DeterministicTopicDetector().detect(
                "topic: database migration", java.util.List.of());

        assertThat(signal.label()).isEqualTo("topic: database migration");
        assertThat(signal.confidence()).isGreaterThanOrEqualTo(0.8d);
        assertThat(signal.boundaryReason()).contains("raw-input");
        assertThat(signal.detectorRevision()).isEqualTo(DeterministicTopicDetector.REVISION);
    }
}

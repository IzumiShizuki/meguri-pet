package com.meguri.core.knowledge;

import java.time.Duration;
import java.util.Objects;

public record NotionSyncLimits(int maxDepth, int maxBlocks, Duration timeout) {
    public NotionSyncLimits {
        if (maxDepth < 0) throw new IllegalArgumentException("maxDepth must not be negative");
        if (maxBlocks < 1) throw new IllegalArgumentException("maxBlocks must be positive");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    public static NotionSyncLimits defaults() {
        return new NotionSyncLimits(16, 10_000, Duration.ofSeconds(30));
    }
}

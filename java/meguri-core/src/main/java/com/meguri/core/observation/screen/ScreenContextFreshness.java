package com.meguri.core.observation.screen;

import java.time.Instant;
import java.util.Objects;

public enum ScreenContextFreshness {
    CURRENT,
    RECENT_HISTORY,
    UNAVAILABLE;

    public static ScreenContextFreshness classify(ScreenContext context, Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (context == null || now.isBefore(context.observedAt())) {
            return UNAVAILABLE;
        }
        return context.isCurrentAt(now) ? CURRENT : RECENT_HISTORY;
    }
}

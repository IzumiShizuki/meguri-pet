package com.meguri.core.runtime;

import java.util.Locale;

/** Detailed durable lifecycle inside the stable public turn status. */
public enum TurnStage {
    CREATED,
    PLANNING,
    RETRIEVING,
    GENERATING,
    FINALIZING,
    COMPLETED,
    FAILED,
    CANCELLED;

    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}

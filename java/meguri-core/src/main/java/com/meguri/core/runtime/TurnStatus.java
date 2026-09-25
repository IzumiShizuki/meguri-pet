package com.meguri.core.runtime;

/** Lifecycle states for an asynchronous Meguri turn. */
public enum TurnStatus {
    ACCEPTED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED;

    /** Wire representation used by the Python/TypeScript protocol. */
    public String wireValue() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    public String toString() {
        return wireValue();
    }

    public static TurnStatus fromWireValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("turn status must not be null");
        }
        return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    }
}

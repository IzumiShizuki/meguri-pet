package com.meguri.core.harness.capability;

/** Sanitized capability rejection/failure safe for turn-level handling. */
public final class CapabilityExecutionException extends RuntimeException {
    public CapabilityExecutionException(String message) {
        super(message);
    }

    public CapabilityExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}

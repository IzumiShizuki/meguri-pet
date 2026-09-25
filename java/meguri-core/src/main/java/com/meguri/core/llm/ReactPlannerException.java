package com.meguri.core.llm;

/** Sanitized, machine-readable failure from the bounded ReAct planner route. */
public final class ReactPlannerException extends LlmProviderException {
    public enum Reason {
        QUEUE_SATURATED,
        PROVIDER_TIMEOUT,
        UPSTREAM_FAILURE,
        INVALID_RESPONSE,
        INTERRUPTED
    }

    private final Reason reason;

    public ReactPlannerException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public ReactPlannerException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}

package com.meguri.core.llm;

/** Sanitized, machine-readable failure from the optional Agent-planning route. */
public final class AgentPlannerException extends LlmProviderException {
    public enum Reason {
        QUEUE_SATURATED,
        PROVIDER_TIMEOUT,
        UPSTREAM_FAILURE,
        INVALID_RESPONSE,
        INTERRUPTED
    }

    private final Reason reason;

    public AgentPlannerException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public AgentPlannerException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}

package com.meguri.core.llm;

/** Local output validation, distinct from a model transport or streaming failure. */
public final class ReplyConstraintException extends LlmProviderException {
    public enum Reason {
        VIOLATION("REPLY_CONSTRAINT_VIOLATION"),
        DEADLINE("TURN_DEADLINE_EXCEEDED"),
        VALIDATION_FAILED("REPLY_CONSTRAINT_VALIDATION_FAILED");

        private final String failureCode;
        Reason(String failureCode) { this.failureCode = failureCode; }
    }

    private final Reason reason;
    public ReplyConstraintException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }
    public String failureCode() { return reason.failureCode; }
}

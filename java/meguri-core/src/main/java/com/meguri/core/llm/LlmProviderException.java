package com.meguri.core.llm;

/** Sanitized failure safe to expose in a turn.failed event. */
public class LlmProviderException extends RuntimeException {
    public LlmProviderException(String message) { super(message); }
    public LlmProviderException(String message, Throwable cause) { super(message, cause); }
}

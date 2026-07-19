package com.meguri.core.llm;

public final class LlmConfigurationException extends LlmProviderException {
    public LlmConfigurationException(String message) { super(message); }
    public LlmConfigurationException(String message, Throwable cause) { super(message, cause); }
}

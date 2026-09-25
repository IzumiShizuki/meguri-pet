package com.meguri.core.agent;

public class AgentSkippedException extends RuntimeException {
    public AgentSkippedException(String message) {
        super(message);
    }
}

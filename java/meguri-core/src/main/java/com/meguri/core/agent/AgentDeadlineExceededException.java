package com.meguri.core.agent;

public class AgentDeadlineExceededException extends RuntimeException {
    public AgentDeadlineExceededException(String message) {
        super(message);
    }
}

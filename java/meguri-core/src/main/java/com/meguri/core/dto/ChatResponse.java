package com.meguri.core.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = false)
public final class ChatResponse {
    private final String turnId;
    private final String sessionId;
    private final LlmResponse response;
    private final RuntimeState runtimeState;
    private final ResolvedExpression expression;
    private final MemoryStatus memoryStatus;
    private final String buildId;

    @JsonCreator
    public ChatResponse(@JsonProperty("turn_id") String turnId,
                        @JsonProperty("session_id") String sessionId,
                        @JsonProperty("response") LlmResponse response,
                        @JsonProperty("runtime_state") RuntimeState runtimeState,
                        @JsonProperty("expression") ResolvedExpression expression,
                        @JsonProperty("memory_status") MemoryStatus memoryStatus,
                        @JsonProperty("build_id") String buildId) {
        this.turnId = required(turnId, "turn_id");
        this.sessionId = required(sessionId, "session_id");
        this.response = required(response, "response");
        this.runtimeState = required(runtimeState, "runtime_state");
        this.expression = required(expression, "expression");
        this.memoryStatus = memoryStatus == null ? MemoryStatus.UNAVAILABLE : memoryStatus;
        this.buildId = required(buildId, "build_id");
    }

    private static <T> T required(T value, String field) {
        if (value == null) throw new IllegalArgumentException(field + " must not be null");
        return value;
    }
    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }

    @JsonProperty("turn_id") public String getTurnId() { return turnId; }
    public String turnId() { return turnId; }
    @JsonProperty("session_id") public String getSessionId() { return sessionId; }
    public String sessionId() { return sessionId; }
    @JsonProperty("response") public LlmResponse getResponse() { return response; }
    public LlmResponse response() { return response; }
    @JsonProperty("runtime_state") public RuntimeState getRuntimeState() { return runtimeState; }
    public RuntimeState runtimeState() { return runtimeState; }
    @JsonProperty("expression") public ResolvedExpression getExpression() { return expression; }
    public ResolvedExpression expression() { return expression; }
    @JsonProperty("memory_status") public MemoryStatus getMemoryStatus() { return memoryStatus; }
    public MemoryStatus memoryStatus() { return memoryStatus; }
    @JsonProperty("build_id") public String getBuildId() { return buildId; }
    public String buildId() { return buildId; }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ChatResponse that)) return false;
        return turnId.equals(that.turnId) && sessionId.equals(that.sessionId) && response.equals(that.response)
                && runtimeState.equals(that.runtimeState) && expression.equals(that.expression)
                && memoryStatus == that.memoryStatus && buildId.equals(that.buildId);
    }
    @Override public int hashCode() { return Objects.hash(turnId, sessionId, response, runtimeState, expression, memoryStatus, buildId); }
    @Override public String toString() { return "ChatResponse[turnId=" + turnId + ", sessionId=" + sessionId + ", response=" + response + ", runtimeState=" + runtimeState + ", expression=" + expression + ", memoryStatus=" + memoryStatus + ", buildId=" + buildId + "]"; }
}

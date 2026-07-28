package com.meguri.core.agent;

import java.util.Map;

public record AgentResult(
        String schemaId,
        String sourceAgentId,
        Map<String, Object> payload,
        boolean sensitive,
        TrustLabel trustLabel) {

    public AgentResult {
        payload = Map.copyOf(payload == null ? Map.of() : payload);
    }

    public AgentResult asUntrusted() {
        return new AgentResult(schemaId, sourceAgentId, payload, sensitive, TrustLabel.UNTRUSTED_AGENT_RESULT);
    }

    public enum TrustLabel {
        UNTRUSTED_AGENT_RESULT
    }
}

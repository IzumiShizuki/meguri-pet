package com.meguri.core.observability;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

/** Canonical wall-clock milestones for one Turn's time-to-first-token timeline. */
public enum TurnLatencyPoint {
    TURN_RECEIVED("turn.received_at"),
    TURN_PERSISTED("turn.persisted_at"),
    PERSONA_STARTED("persona.started_at"),
    PERSONA_READY("persona.ready_at"),
    RETRIEVAL_GATE_STARTED("retrieval_gate.started_at"),
    RETRIEVAL_GATE_READY("retrieval_gate.ready_at"),
    QUERY_REWRITE_STARTED("query_rewrite.started_at"),
    QUERY_REWRITE_READY("query_rewrite.ready_at"),
    RETRIEVAL_STARTED("retrieval.started_at"),
    RETRIEVAL_MINIMUM_READY("retrieval.minimum_ready_at"),
    RETRIEVAL_ALL_SETTLED("retrieval.all_settled_at"),
    CONTEXT_BUILD_STARTED("context_build.started_at"),
    CONTEXT_READY("context.ready_at"),
    CAPABILITY_EXPOSURE_STARTED("capability_exposure.started_at"),
    CAPABILITY_EXPOSURE_READY("capability_exposure.ready_at"),
    PROVIDER_REQUEST_SENT("provider.request_sent_at"),
    PROVIDER_FIRST_BYTE("provider.first_byte_at"),
    PROVIDER_FIRST_TOKEN("provider.first_token_at"),
    FIRST_DELTA_PERSISTED("first_delta.persisted_at"),
    FIRST_DELTA_SSE_FLUSHED("first_delta.sse_flushed_at"),
    CLIENT_FIRST_DELTA_RECEIVED("client.first_delta_received_at"),
    CLIENT_FIRST_RENDER("client.first_render.at");

    private final String wireName;

    TurnLatencyPoint(String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    @JsonCreator
    public static TurnLatencyPoint fromWireName(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("latency point is required");
        }
        String normalized = value.trim();
        // The 01 Notion page once used this dotted spelling. Read it as an alias,
        // but always serialize the canonical *_at spelling used by the parent spec.
        if ("provider.first_token.at".equals(normalized)) {
            return PROVIDER_FIRST_TOKEN;
        }
        if ("context_ready_at".equals(normalized)) {
            return CONTEXT_READY;
        }
        if ("client.first_render_at".equals(normalized)) {
            return CLIENT_FIRST_RENDER;
        }
        return Arrays.stream(values())
                .filter(point -> point.wireName.equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown Turn latency point: " + value));
    }
}

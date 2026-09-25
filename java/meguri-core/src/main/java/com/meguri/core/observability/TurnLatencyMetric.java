package com.meguri.core.observability;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

/** Derived TTFT segments. Endpoints deliberately partition provider and delivery latency. */
public enum TurnLatencyMetric {
    TURN_PERSIST_LATENCY(
            "turn_persist_latency", TurnLatencyPoint.TURN_RECEIVED,
            TurnLatencyPoint.TURN_PERSISTED),
    PERSONA_LATENCY(
            "persona_latency", TurnLatencyPoint.PERSONA_STARTED,
            TurnLatencyPoint.PERSONA_READY),
    RETRIEVAL_GATE_LATENCY(
            "retrieval_gate_latency", TurnLatencyPoint.RETRIEVAL_GATE_STARTED,
            TurnLatencyPoint.RETRIEVAL_GATE_READY),
    RETRIEVAL_WAIT_BEFORE_PROVIDER(
            "retrieval_wait_before_provider", TurnLatencyPoint.RETRIEVAL_STARTED,
            TurnLatencyPoint.RETRIEVAL_MINIMUM_READY),
    CONTEXT_BUILD_LATENCY(
            "context_build_latency", TurnLatencyPoint.CONTEXT_BUILD_STARTED,
            TurnLatencyPoint.CONTEXT_READY),
    CAPABILITY_EXPOSURE_LATENCY(
            "capability_exposure_latency", TurnLatencyPoint.CAPABILITY_EXPOSURE_STARTED,
            TurnLatencyPoint.CAPABILITY_EXPOSURE_READY),
    PRE_PROVIDER_LATENCY(
            "pre_provider_latency", TurnLatencyPoint.TURN_RECEIVED,
            TurnLatencyPoint.PROVIDER_REQUEST_SENT),
    PROVIDER_QUEUE_LATENCY(
            "provider_queue_latency", TurnLatencyPoint.PROVIDER_REQUEST_SENT,
            TurnLatencyPoint.PROVIDER_FIRST_BYTE),
    PROVIDER_FIRST_TOKEN_LATENCY(
            "provider_first_token_latency", TurnLatencyPoint.PROVIDER_REQUEST_SENT,
            TurnLatencyPoint.PROVIDER_FIRST_TOKEN),
    EVENT_PERSIST_LATENCY(
            "event_persist_latency", TurnLatencyPoint.PROVIDER_FIRST_TOKEN,
            TurnLatencyPoint.FIRST_DELTA_PERSISTED),
    SSE_TRANSPORT_LATENCY(
            "sse_transport_latency", TurnLatencyPoint.FIRST_DELTA_SSE_FLUSHED,
            TurnLatencyPoint.CLIENT_FIRST_DELTA_RECEIVED),
    CLIENT_RENDER_LATENCY(
            "client_render_latency", TurnLatencyPoint.CLIENT_FIRST_DELTA_RECEIVED,
            TurnLatencyPoint.CLIENT_FIRST_RENDER),
    END_TO_END_TTFT(
            "end_to_end_ttft", TurnLatencyPoint.TURN_RECEIVED,
            TurnLatencyPoint.CLIENT_FIRST_RENDER),
    /** Adapter-provided metric for clients that cannot expose character TTFT. */
    FIRST_PLATFORM_MESSAGE_LATENCY("first_platform_message_latency", null, null);

    private final String wireName;
    private final TurnLatencyPoint start;
    private final TurnLatencyPoint end;

    TurnLatencyMetric(String wireName, TurnLatencyPoint start, TurnLatencyPoint end) {
        this.wireName = wireName;
        this.start = start;
        this.end = end;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    public TurnLatencyPoint start() {
        return start;
    }

    public TurnLatencyPoint end() {
        return end;
    }

    public boolean derivedFromCanonicalTimestamps() {
        return start != null && end != null;
    }

    @JsonCreator
    public static TurnLatencyMetric fromWireName(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("latency metric is required");
        }
        String normalized = value.trim();
        return Arrays.stream(values())
                .filter(metric -> metric.wireName.equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown Turn latency metric: " + value));
    }
}

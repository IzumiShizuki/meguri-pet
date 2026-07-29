package com.meguri.core.adapter.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.meguri.core.dto.EventEnvelope;
import com.meguri.core.harness.SessionSnapshot;
import com.meguri.core.harness.TurnSnapshot;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Canonical state snapshot used after cursor expiry and as the polling fallback. */
public record AdapterSessionSnapshotResponse(
        @JsonProperty("protocol_version") String protocolVersion,
        @JsonProperty("session_id") String sessionId,
        long sequence,
        List<AdapterTurnSnapshot> turns,
        @JsonProperty("processed_event_ids") List<String> processedEventIds,
        @JsonProperty("processed_once_event_ids") List<String> processedOnceEventIds,
        @JsonProperty("created_at") Instant createdAt) {

    public AdapterSessionSnapshotResponse {
        protocolVersion = protocolVersion == null || protocolVersion.isBlank()
                ? ProtocolVersion.CURRENT.toString() : protocolVersion;
        if (sessionId == null || sessionId.isBlank() || sequence < 0) {
            throw new IllegalArgumentException("snapshot session and sequence are required");
        }
        turns = turns == null ? List.of() : List.copyOf(turns);
        processedEventIds = processedEventIds == null ? List.of() : List.copyOf(processedEventIds);
        processedOnceEventIds = processedOnceEventIds == null
                ? List.of() : List.copyOf(processedOnceEventIds);
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    public static AdapterSessionSnapshotResponse from(SessionSnapshot snapshot) {
        Map<String, MutableTurnState> state = new LinkedHashMap<>();
        for (TurnSnapshot turn : snapshot.turns()) {
            String text = turn.result() == null ? "" : turn.result().getResponse().getReply();
            Map<String, Object> expression = turn.result() == null
                    ? Map.of() : immutableExpression(Map.of(
                            "expression", turn.result().getExpression().getExpressionTag().value(),
                            "intensity", turn.result().getExpression().getExpressionIntensity().value()),
                            turn.result().getExpression().getOutfitCode());
            state.put(turn.turnId(), new MutableTurnState(
                    turn.turnId(), normalizeStatus(turn.status()), text, expression, turn.error()));
        }
        for (EventEnvelope event : snapshot.stateEvents()) {
            MutableTurnState turn = state.get(event.getTurnId());
            if (turn == null) continue;
            if ("text.completed".equals(event.getType()) && event.getData().get("text") != null) {
                turn.text = String.valueOf(event.getData().get("text"));
            } else if ("text.delta".equals(event.getType()) && event.getData().get("delta") != null) {
                turn.text = turn.text + event.getData().get("delta");
            } else if ("expression.cue".equals(event.getType())
                    || "sprite.resolved".equals(event.getType())) {
                turn.expression = immutableMapWithoutNulls(event.getData());
            }
        }
        List<AdapterTurnSnapshot> turns = new ArrayList<>();
        state.values().forEach(value -> turns.add(value.freeze()));
        return new AdapterSessionSnapshotResponse(
                ProtocolVersion.CURRENT.toString(),
                snapshot.sessionId(),
                snapshot.lastSequence(),
                turns,
                snapshot.processedEventIds(),
                snapshot.processedOnceEventIds(),
                snapshot.generatedAt());
    }

    private static String normalizeStatus(String status) {
        return switch (status) {
            case "accepted" -> "idle";
            case "cancel_requested" -> "running";
            default -> status;
        };
    }

    private static Map<String, Object> immutableExpression(
            Map<String, Object> required, Object outfitCode) {
        Map<String, Object> expression = new LinkedHashMap<>(required);
        expression.put("outfit_code", outfitCode);
        return immutableMapWithoutNulls(expression);
    }

    private static Map<String, Object> immutableMapWithoutNulls(Map<String, ?> values) {
        if (values == null || values.isEmpty()) return Map.of();
        Map<String, Object> sanitized = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key != null && value != null) sanitized.put(key, value);
        });
        return Map.copyOf(sanitized);
    }

    public record AdapterTurnSnapshot(
            @JsonProperty("turn_id") String turnId,
            String status,
            String text,
            Map<String, Object> expression,
            String error) {
        public AdapterTurnSnapshot {
            if (turnId == null || turnId.isBlank() || status == null || status.isBlank()) {
                throw new IllegalArgumentException("turn snapshot identity and status are required");
            }
            text = text == null ? "" : text;
            expression = immutableMapWithoutNulls(expression);
        }
    }

    private static final class MutableTurnState {
        private final String turnId;
        private final String status;
        private String text;
        private Map<String, Object> expression;
        private final String error;

        private MutableTurnState(
                String turnId, String status, String text,
                Map<String, Object> expression, String error) {
            this.turnId = turnId;
            this.status = status;
            this.text = text;
            this.expression = expression;
            this.error = error;
        }

        private AdapterTurnSnapshot freeze() {
            return new AdapterTurnSnapshot(turnId, status, text, expression, error);
        }
    }
}

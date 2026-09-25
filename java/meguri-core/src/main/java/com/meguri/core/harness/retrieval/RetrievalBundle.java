package com.meguri.core.harness.retrieval;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable multi-lane retrieval result frozen before generation. */
public record RetrievalBundle(
        String traceId,
        Instant completedAt,
        List<LaneResult> lanes) {
    public RetrievalBundle {
        if (traceId == null || traceId.isBlank()) throw new IllegalArgumentException("traceId must not be blank");
        completedAt = completedAt == null ? Instant.now() : completedAt;
        lanes = lanes == null ? List.of() : List.copyOf(lanes);
        for (Lane lane : Lane.values()) {
            long count = lanes.stream().filter(result -> result.lane() == lane).count();
            if (count != 1) throw new IllegalArgumentException("retrieval bundle requires one " + lane + " lane");
        }
    }

    public List<String> items(Lane lane) {
        return lanes.stream()
                .filter(result -> result.lane() == lane)
                .findFirst()
                .orElseThrow()
                .items();
    }

    /** Content-free event data that can be retained in the Turn trace. */
    public Map<String, Object> traceData() {
        List<Map<String, Object>> summaries = lanes.stream().map(result -> {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("lane", result.lane().wireValue());
            summary.put("status", result.status());
            summary.put("provider", result.provider());
            summary.put("result_count", result.items().size());
            summary.put("content_sha256", digest(String.join("\n", result.items())));
            return Map.copyOf(summary);
        }).toList();
        return Map.of(
                "trace_id", traceId,
                "completed_at", completedAt.toString(),
                "lanes", summaries);
    }

    public enum Lane {
        LORE("lore"), MEMORY("memory"), KNOWLEDGE_BASE("knowledge_base"), WEB("web");

        private final String wireValue;

        Lane(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }
    }

    public record LaneResult(Lane lane, String status, String provider, List<String> items) {
        public LaneResult {
            if (lane == null) throw new IllegalArgumentException("lane must not be null");
            status = status == null || status.isBlank() ? "unavailable" : status;
            provider = provider == null || provider.isBlank() ? "none" : provider;
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}

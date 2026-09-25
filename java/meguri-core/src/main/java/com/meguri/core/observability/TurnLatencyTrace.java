package com.meguri.core.observability;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.time.Instant;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Content-free TTFT trace contract. It carries only IDs, version/classification
 * dimensions, timestamps, durations, and stable missing-observation codes.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record TurnLatencyTrace(
        String contractRevision,
        String traceId,
        String turnId,
        String releaseId,
        String gitCommit,
        String imageDigest,
        String responseContractRevision,
        String promptRevision,
        String model,
        String provider,
        String executionMode,
        String retrievalMode,
        String clientType,
        Map<String, Instant> timestamps,
        Map<String, Long> durationsMillis,
        Map<String, String> missingTimestampReasonCodes) {

    public static final String CONTRACT_REVISION = "performance.v1";
    private static final Pattern REASON_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,127}");

    public TurnLatencyTrace {
        contractRevision = contractRevision == null || contractRevision.isBlank()
                ? CONTRACT_REVISION : contractRevision.trim();
        if (!CONTRACT_REVISION.equals(contractRevision)) {
            throw new IllegalArgumentException(
                    "unsupported Turn latency contract revision: " + contractRevision);
        }
        TurnLatencyTraceMetadata metadata = new TurnLatencyTraceMetadata(
                traceId, turnId, releaseId, gitCommit, imageDigest,
                responseContractRevision, promptRevision, model, provider,
                executionMode, retrievalMode, clientType);
        traceId = metadata.traceId();
        turnId = metadata.turnId();
        releaseId = metadata.releaseId();
        gitCommit = metadata.gitCommit();
        imageDigest = metadata.imageDigest();
        responseContractRevision = metadata.responseContractRevision();
        promptRevision = metadata.promptRevision();
        model = metadata.model();
        provider = metadata.provider();
        executionMode = metadata.executionMode();
        retrievalMode = metadata.retrievalMode();
        clientType = metadata.clientType();

        timestamps = canonicalTimestamps(timestamps);
        durationsMillis = deriveAndValidateDurations(
                timestamps, canonicalDurations(durationsMillis));
        missingTimestampReasonCodes = canonicalMissingReasons(
                missingTimestampReasonCodes, timestamps);
    }

    public TurnLatencyTraceMetadata metadata() {
        return new TurnLatencyTraceMetadata(
                traceId, turnId, releaseId, gitCommit, imageDigest,
                responseContractRevision, promptRevision, model, provider,
                executionMode, retrievalMode, clientType);
    }

    public Instant timestamp(TurnLatencyPoint point) {
        if (point == null) return null;
        return timestamps.get(point.wireName());
    }

    public Long durationMillis(TurnLatencyMetric metric) {
        if (metric == null) return null;
        return durationsMillis.get(metric.wireName());
    }

    public TurnLatencyMissingReason missingReason(TurnLatencyPoint point) {
        if (point == null) return null;
        String value = missingReasonCode(point);
        if (value == null) return null;
        try {
            return TurnLatencyMissingReason.fromWireName(value);
        } catch (IllegalArgumentException domainSpecificCode) {
            return null;
        }
    }

    public String missingReasonCode(TurnLatencyPoint point) {
        if (point == null) return null;
        return missingTimestampReasonCodes.get(point.wireName());
    }

    private static Map<String, Instant> canonicalTimestamps(Map<String, Instant> source) {
        LinkedHashMap<String, Instant> result = new LinkedHashMap<>();
        if (source != null) {
            source.forEach((key, value) -> {
                TurnLatencyPoint point = TurnLatencyPoint.fromWireName(key);
                if (value == null) throw new IllegalArgumentException(
                        "timestamp is required for " + point.wireName());
                if (result.putIfAbsent(point.wireName(), value) != null) {
                    throw new IllegalArgumentException("duplicate timestamp: " + point.wireName());
                }
            });
        }
        return immutable(result);
    }

    private static Map<String, Long> canonicalDurations(Map<String, Long> source) {
        LinkedHashMap<String, Long> result = new LinkedHashMap<>();
        if (source != null) {
            source.forEach((key, value) -> {
                TurnLatencyMetric metric = TurnLatencyMetric.fromWireName(key);
                if (value == null || value < 0L) throw new IllegalArgumentException(
                        "duration must be non-negative for " + metric.wireName());
                if (result.putIfAbsent(metric.wireName(), value) != null) {
                    throw new IllegalArgumentException("duplicate duration: " + metric.wireName());
                }
            });
        }
        return immutable(result);
    }

    private static Map<String, Long> deriveAndValidateDurations(
            Map<String, Instant> timestamps, Map<String, Long> supplied) {
        LinkedHashMap<String, Long> result = new LinkedHashMap<>(supplied);
        for (TurnLatencyMetric metric : TurnLatencyMetric.values()) {
            if (!metric.derivedFromCanonicalTimestamps()) continue;
            Instant start = timestamps.get(metric.start().wireName());
            Instant end = timestamps.get(metric.end().wireName());
            Long suppliedValue = supplied.get(metric.wireName());
            if (start == null || end == null) {
                if (start == null && end == null && Long.valueOf(0L).equals(suppliedValue)) {
                    continue;
                }
                if (suppliedValue != null) {
                    throw new IllegalArgumentException(
                            "duration endpoints are missing for " + metric.wireName());
                }
                continue;
            }
            long elapsed;
            try {
                elapsed = Duration.between(start, end).toMillis();
            } catch (ArithmeticException overflow) {
                throw new IllegalArgumentException(
                        "duration overflow for " + metric.wireName(), overflow);
            }
            if (elapsed < 0L) {
                throw new IllegalArgumentException(
                        "causally impossible timestamps for " + metric.wireName());
            }
            if (suppliedValue != null && suppliedValue != elapsed) {
                throw new IllegalArgumentException(
                        "duration does not match timestamps for " + metric.wireName());
            }
            result.put(metric.wireName(), elapsed);
        }
        validateOrder(timestamps, TurnLatencyPoint.RETRIEVAL_MINIMUM_READY,
                TurnLatencyPoint.RETRIEVAL_ALL_SETTLED);
        validateOrder(timestamps, TurnLatencyPoint.FIRST_DELTA_PERSISTED,
                TurnLatencyPoint.FIRST_DELTA_SSE_FLUSHED);
        return immutable(result);
    }

    private static void validateOrder(
            Map<String, Instant> timestamps,
            TurnLatencyPoint before,
            TurnLatencyPoint after) {
        Instant left = timestamps.get(before.wireName());
        Instant right = timestamps.get(after.wireName());
        if (left != null && right != null && right.isBefore(left)) {
            throw new IllegalArgumentException(
                    "causally impossible timestamps: " + after.wireName()
                            + " precedes " + before.wireName());
        }
    }

    private static Map<String, String> canonicalMissingReasons(
            Map<String, String> source, Map<String, Instant> timestamps) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        if (source != null) {
            source.forEach((key, value) -> {
                TurnLatencyPoint point = TurnLatencyPoint.fromWireName(key);
                String reason = canonicalReasonCode(value);
                if (timestamps.containsKey(point.wireName())) {
                    throw new IllegalArgumentException(
                            "observed timestamp cannot also be missing: " + point.wireName());
                }
                if (result.putIfAbsent(point.wireName(), reason) != null) {
                    throw new IllegalArgumentException("duplicate missing reason: " + point.wireName());
                }
            });
        }
        for (TurnLatencyPoint point : TurnLatencyPoint.values()) {
            if (!timestamps.containsKey(point.wireName())) {
                result.putIfAbsent(point.wireName(), TurnLatencyMissingReason.NOT_RECORDED.wireName());
            }
        }
        return immutable(result);
    }

    static String canonicalReasonCode(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing timestamp reason code is required");
        }
        String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
        if (!REASON_CODE.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "invalid missing timestamp reason code: " + value);
        }
        return normalized;
    }

    private static <T> Map<String, T> immutable(LinkedHashMap<String, T> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}

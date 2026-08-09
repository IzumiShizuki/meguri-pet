package com.meguri.core.observability;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Thread-safe per-Turn recorder. Wall time is exported for cross-process
 * correlation; elapsed durations are derived from a monotonic clock.
 */
public final class TurnLatencyTraceRecorder {
    private final TurnLatencyTraceMetadata metadata;
    private final Clock wallClock;
    private final LongSupplier monotonicNanos;
    private final EnumMap<TurnLatencyPoint, Observation> observations =
            new EnumMap<>(TurnLatencyPoint.class);
    private final EnumMap<TurnLatencyPoint, String> missing =
            new EnumMap<>(TurnLatencyPoint.class);
    private final EnumMap<TurnLatencyMetric, Long> adapterDurations =
            new EnumMap<>(TurnLatencyMetric.class);

    public TurnLatencyTraceRecorder(TurnLatencyTraceMetadata metadata) {
        this(metadata, Clock.systemUTC(), System::nanoTime);
    }

    public TurnLatencyTraceRecorder(
            TurnLatencyTraceMetadata metadata,
            Clock wallClock,
            LongSupplier monotonicNanos) {
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.wallClock = Objects.requireNonNull(wallClock, "wallClock");
        this.monotonicNanos = Objects.requireNonNull(monotonicNanos, "monotonicNanos");
    }

    /** Records only the first observation for a milestone. */
    public synchronized boolean mark(TurnLatencyPoint point) {
        Objects.requireNonNull(point, "point");
        if (observations.containsKey(point)) return false;
        observations.put(point, new Observation(wallClock.instant(), monotonicNanos.getAsLong()));
        missing.remove(point);
        return true;
    }

    /**
     * Records a client-reported wall timestamp. The caller must use the trace's
     * documented clock policy; causally impossible values are rejected at snapshot.
     */
    public synchronized boolean markAt(TurnLatencyPoint point, Instant observedAt) {
        Objects.requireNonNull(point, "point");
        Objects.requireNonNull(observedAt, "observedAt");
        if (observations.containsKey(point)) return false;
        observations.put(point, new Observation(observedAt, null));
        missing.remove(point);
        return true;
    }

    /** Records an explicit reason only while the milestone remains unobserved. */
    public synchronized boolean markMissing(
            TurnLatencyPoint point, TurnLatencyMissingReason reason) {
        Objects.requireNonNull(reason, "reason");
        return markMissing(point, reason.wireName());
    }

    /** Records a domain-specific stable reason code such as {@code SKIPPED_FAST_L0}. */
    public synchronized boolean markMissing(TurnLatencyPoint point, String reasonCode) {
        Objects.requireNonNull(point, "point");
        String canonicalReasonCode = TurnLatencyTrace.canonicalReasonCode(reasonCode);
        if (observations.containsKey(point) || missing.containsKey(point)) return false;
        missing.put(point, canonicalReasonCode);
        return true;
    }

    /** Records a duration that has no v1 timestamp pair, such as a platform message. */
    public synchronized boolean recordAdapterDuration(
            TurnLatencyMetric metric, long durationMillis) {
        Objects.requireNonNull(metric, "metric");
        if (metric.derivedFromCanonicalTimestamps()) {
            throw new IllegalArgumentException(
                    metric.wireName() + " must be derived from canonical timestamps");
        }
        if (durationMillis < 0L) {
            throw new IllegalArgumentException("durationMillis must be non-negative");
        }
        return adapterDurations.putIfAbsent(metric, durationMillis) == null;
    }

    /** Returns an immutable content-free snapshot; the recorder may receive later client marks. */
    public synchronized TurnLatencyTrace snapshot() {
        LinkedHashMap<String, Instant> timestamps = new LinkedHashMap<>();
        LinkedHashMap<String, Long> durations = new LinkedHashMap<>();
        LinkedHashMap<String, String> missingReasons = new LinkedHashMap<>();

        for (TurnLatencyPoint point : TurnLatencyPoint.values()) {
            Observation observation = observations.get(point);
            if (observation != null) {
                timestamps.put(point.wireName(), observation.wallTime());
            } else {
                String reason = missing.getOrDefault(
                        point, TurnLatencyMissingReason.NOT_RECORDED.wireName());
                missingReasons.put(point.wireName(), reason);
            }
        }

        for (TurnLatencyMetric metric : TurnLatencyMetric.values()) {
            if (!metric.derivedFromCanonicalTimestamps()) continue;
            Observation start = observations.get(metric.start());
            Observation end = observations.get(metric.end());
            if (start == null || end == null) continue;
            if (!timestamps.containsKey(metric.start().wireName())
                    || !timestamps.containsKey(metric.end().wireName())) continue;
            Long startNanos = start.monotonicNanos();
            Long endNanos = end.monotonicNanos();
            boolean monotonicRegression = startNanos != null && endNanos != null
                    && endNanos - startNanos < 0L;
            long wallElapsed = Duration.between(start.wallTime(), end.wallTime()).toMillis();
            if (monotonicRegression || wallElapsed < 0L) {
                missingReasons.put(metric.end().wireName(),
                        TurnLatencyMissingReason.CLOCK_REGRESSION.wireName());
                timestamps.remove(metric.end().wireName());
                continue;
            }
            durations.put(metric.wireName(), wallElapsed);
        }
        adapterDurations.forEach((metric, value) -> durations.put(metric.wireName(), value));

        return new TurnLatencyTrace(
                TurnLatencyTrace.CONTRACT_REVISION,
                metadata.traceId(), metadata.turnId(), metadata.releaseId(),
                metadata.gitCommit(), metadata.imageDigest(),
                metadata.responseContractRevision(), metadata.promptRevision(),
                metadata.model(), metadata.provider(), metadata.executionMode(),
                metadata.retrievalMode(), metadata.clientType(),
                timestamps, durations, missingReasons);
    }

    public TurnLatencyTraceMetadata metadata() {
        return metadata;
    }

    private record Observation(Instant wallTime, Long monotonicNanos) { }
}

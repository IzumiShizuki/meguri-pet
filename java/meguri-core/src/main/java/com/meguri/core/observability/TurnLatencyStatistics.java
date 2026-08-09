package com.meguri.core.observability;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Deterministic P50/P95/P99 aggregation grouped by the Notion-required dimensions. */
public final class TurnLatencyStatistics {
    private TurnLatencyStatistics() { }

    public static Distribution summarize(
            Collection<TurnLatencyTrace> traces, TurnLatencyMetric metric) {
        Objects.requireNonNull(traces, "traces");
        Objects.requireNonNull(metric, "metric");
        List<Long> observed = traces.stream()
                .filter(Objects::nonNull)
                .map(trace -> trace.durationMillis(metric))
                .filter(Objects::nonNull)
                .sorted()
                .toList();
        return distribution(traces.size(), observed);
    }

    public static Map<GroupKey, Distribution> summarizeByRequiredDimensions(
            Collection<TurnLatencyTrace> traces, TurnLatencyMetric metric) {
        Objects.requireNonNull(traces, "traces");
        Objects.requireNonNull(metric, "metric");
        LinkedHashMap<GroupKey, List<TurnLatencyTrace>> groups = new LinkedHashMap<>();
        traces.stream().filter(Objects::nonNull).forEach(trace ->
                groups.computeIfAbsent(GroupKey.from(trace), ignored -> new ArrayList<>())
                        .add(trace));
        LinkedHashMap<GroupKey, Distribution> result = new LinkedHashMap<>();
        groups.forEach((key, values) -> result.put(key, summarize(values, metric)));
        return Collections.unmodifiableMap(result);
    }

    /**
     * Produces numeric reductions only after the report owner has matched the
     * workload and all metric dimensions. A missing side is never coerced to 0.
     */
    public static Comparison compare(
            Distribution baseline,
            Distribution candidate,
            boolean comparableDefinitions) {
        if (!comparableDefinitions) {
            return Comparison.notMeasured("INCOMPARABLE_DEFINITIONS", baseline, candidate);
        }
        if (baseline == null || baseline.sampleCount() == 0) {
            return Comparison.notMeasured("BASELINE_MISSING", baseline, candidate);
        }
        if (candidate == null || candidate.sampleCount() == 0) {
            return Comparison.notMeasured("CANDIDATE_MISSING", baseline, candidate);
        }
        return new Comparison(
                ComparisonStatus.MEASURED,
                "COMPARABLE_DISTRIBUTIONS",
                baseline.sampleCount(),
                candidate.sampleCount(),
                baseline.p50Millis() - candidate.p50Millis(),
                baseline.p95Millis() - candidate.p95Millis(),
                baseline.p99Millis() - candidate.p99Millis());
    }

    private static Distribution distribution(int traceCount, List<Long> ordered) {
        if (ordered.isEmpty()) {
            return new Distribution(traceCount, 0, traceCount, null, null, null);
        }
        return new Distribution(
                traceCount,
                ordered.size(),
                Math.max(0, traceCount - ordered.size()),
                percentile(ordered, 0.50),
                percentile(ordered, 0.95),
                percentile(ordered, 0.99));
    }

    private static double percentile(List<Long> ordered, double quantile) {
        double position = (ordered.size() - 1) * quantile;
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) return ordered.get(lower);
        double fraction = position - lower;
        return ordered.get(lower) + (ordered.get(upper) - ordered.get(lower)) * fraction;
    }

    public record GroupKey(
            String releaseId,
            String model,
            String provider,
            String executionMode,
            String retrievalMode,
            String clientType) {
        public static GroupKey from(TurnLatencyTrace trace) {
            Objects.requireNonNull(trace, "trace");
            return new GroupKey(
                    trace.releaseId(), trace.model(), trace.provider(),
                    trace.executionMode(), trace.retrievalMode(), trace.clientType());
        }
    }

    public record Distribution(
            int traceCount,
            int sampleCount,
            int missingCount,
            Double p50Millis,
            Double p95Millis,
            Double p99Millis) {
        public Distribution {
            if (traceCount < 0 || sampleCount < 0 || missingCount < 0
                    || sampleCount + missingCount != traceCount) {
                throw new IllegalArgumentException("invalid latency distribution counts");
            }
        }
    }

    public enum ComparisonStatus {
        MEASURED,
        NOT_MEASURED
    }

    public record Comparison(
            ComparisonStatus status,
            String reasonCode,
            int baselineSampleCount,
            int candidateSampleCount,
            Double p50ReductionMillis,
            Double p95ReductionMillis,
            Double p99ReductionMillis) {

        private static Comparison notMeasured(
                String reasonCode, Distribution baseline, Distribution candidate) {
            return new Comparison(
                    ComparisonStatus.NOT_MEASURED,
                    reasonCode,
                    baseline == null ? 0 : baseline.sampleCount(),
                    candidate == null ? 0 : candidate.sampleCount(),
                    null,
                    null,
                    null);
        }
    }
}

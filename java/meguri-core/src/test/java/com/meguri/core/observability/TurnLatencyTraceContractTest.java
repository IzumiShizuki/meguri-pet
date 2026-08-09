package com.meguri.core.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TurnLatencyTraceContractTest {
    @Test
    void recordsCanonicalTimelineAndDerivesPartitionedTtftSegments() {
        MutableTime time = new MutableTime();
        TurnLatencyTraceRecorder recorder = recorder("FAST", time);

        mark(recorder, time, TurnLatencyPoint.TURN_RECEIVED, 0);
        mark(recorder, time, TurnLatencyPoint.TURN_PERSISTED, 2);
        mark(recorder, time, TurnLatencyPoint.PERSONA_STARTED, 1);
        mark(recorder, time, TurnLatencyPoint.PERSONA_READY, 5);
        mark(recorder, time, TurnLatencyPoint.RETRIEVAL_GATE_STARTED, 1);
        mark(recorder, time, TurnLatencyPoint.RETRIEVAL_GATE_READY, 1);
        mark(recorder, time, TurnLatencyPoint.QUERY_REWRITE_STARTED, 1);
        mark(recorder, time, TurnLatencyPoint.QUERY_REWRITE_READY, 3);
        mark(recorder, time, TurnLatencyPoint.RETRIEVAL_STARTED, 1);
        mark(recorder, time, TurnLatencyPoint.RETRIEVAL_MINIMUM_READY, 7);
        mark(recorder, time, TurnLatencyPoint.RETRIEVAL_ALL_SETTLED, 3);
        mark(recorder, time, TurnLatencyPoint.CONTEXT_BUILD_STARTED, 1);
        mark(recorder, time, TurnLatencyPoint.CONTEXT_READY, 4);
        mark(recorder, time, TurnLatencyPoint.CAPABILITY_EXPOSURE_STARTED, 1);
        mark(recorder, time, TurnLatencyPoint.CAPABILITY_EXPOSURE_READY, 2);
        mark(recorder, time, TurnLatencyPoint.PROVIDER_REQUEST_SENT, 2);
        mark(recorder, time, TurnLatencyPoint.PROVIDER_FIRST_BYTE, 3);
        mark(recorder, time, TurnLatencyPoint.PROVIDER_FIRST_TOKEN, 5);
        mark(recorder, time, TurnLatencyPoint.FIRST_DELTA_PERSISTED, 2);
        mark(recorder, time, TurnLatencyPoint.FIRST_DELTA_SSE_FLUSHED, 2);
        mark(recorder, time, TurnLatencyPoint.CLIENT_FIRST_DELTA_RECEIVED, 2);
        mark(recorder, time, TurnLatencyPoint.CLIENT_FIRST_RENDER, 3);

        TurnLatencyTrace trace = recorder.snapshot();

        assertThat(trace.timestamps()).hasSize(TurnLatencyPoint.values().length);
        assertThat(trace.missingTimestampReasonCodes()).isEmpty();
        assertThat(trace.durationMillis(TurnLatencyMetric.TURN_PERSIST_LATENCY)).isEqualTo(2L);
        assertThat(trace.durationMillis(TurnLatencyMetric.PERSONA_LATENCY)).isEqualTo(5L);
        assertThat(trace.durationMillis(TurnLatencyMetric.RETRIEVAL_GATE_LATENCY)).isEqualTo(1L);
        assertThat(trace.durationMillis(TurnLatencyMetric.RETRIEVAL_WAIT_BEFORE_PROVIDER)).isEqualTo(7L);
        assertThat(trace.durationMillis(TurnLatencyMetric.CONTEXT_BUILD_LATENCY)).isEqualTo(4L);
        assertThat(trace.durationMillis(TurnLatencyMetric.CAPABILITY_EXPOSURE_LATENCY)).isEqualTo(2L);
        assertThat(trace.durationMillis(TurnLatencyMetric.PRE_PROVIDER_LATENCY)).isEqualTo(35L);
        assertThat(trace.durationMillis(TurnLatencyMetric.PROVIDER_QUEUE_LATENCY)).isEqualTo(3L);
        assertThat(trace.durationMillis(TurnLatencyMetric.PROVIDER_FIRST_TOKEN_LATENCY)).isEqualTo(8L);
        assertThat(trace.durationMillis(TurnLatencyMetric.EVENT_PERSIST_LATENCY)).isEqualTo(2L);
        assertThat(trace.durationMillis(TurnLatencyMetric.SSE_TRANSPORT_LATENCY)).isEqualTo(2L);
        assertThat(trace.durationMillis(TurnLatencyMetric.CLIENT_RENDER_LATENCY)).isEqualTo(3L);
        assertThat(trace.durationMillis(TurnLatencyMetric.END_TO_END_TTFT)).isEqualTo(52L);
    }

    @Test
    void firstObservationWinsAndRealObservationCanReplaceAProvisionalMissingReason() {
        MutableTime time = new MutableTime();
        TurnLatencyTraceRecorder recorder = recorder("FAST", time);

        assertThat(recorder.markMissing(
                TurnLatencyPoint.CLIENT_FIRST_RENDER,
                TurnLatencyMissingReason.CLIENT_UNSUPPORTED)).isTrue();
        assertThat(recorder.mark(TurnLatencyPoint.CLIENT_FIRST_RENDER)).isTrue();
        time.advanceMillis(10);
        assertThat(recorder.mark(TurnLatencyPoint.CLIENT_FIRST_RENDER)).isFalse();

        TurnLatencyTrace trace = recorder.snapshot();
        assertThat(trace.timestamp(TurnLatencyPoint.CLIENT_FIRST_RENDER)).isEqualTo(Instant.EPOCH);
        assertThat(trace.missingReason(TurnLatencyPoint.CLIENT_FIRST_RENDER)).isNull();
        assertThat(trace.missingReason(TurnLatencyPoint.PROVIDER_FIRST_BYTE))
                .isEqualTo(TurnLatencyMissingReason.NOT_RECORDED);
    }

    @Test
    void bypassAndUnsupportedBoundariesRemainExplicitWithoutFabricatedDurations() {
        MutableTime time = new MutableTime();
        TurnLatencyTraceRecorder recorder = recorder("FAST", time);
        recorder.mark(TurnLatencyPoint.TURN_RECEIVED);
        recorder.markMissing(TurnLatencyPoint.QUERY_REWRITE_STARTED,
                TurnLatencyMissingReason.STAGE_BYPASSED);
        recorder.markMissing(TurnLatencyPoint.QUERY_REWRITE_READY,
                TurnLatencyMissingReason.STAGE_BYPASSED);
        recorder.markMissing(TurnLatencyPoint.PROVIDER_FIRST_BYTE,
                TurnLatencyMissingReason.PROVIDER_UNSUPPORTED);
        recorder.markMissing(TurnLatencyPoint.CLIENT_FIRST_RENDER,
                TurnLatencyMissingReason.CLIENT_UNSUPPORTED);

        TurnLatencyTrace trace = recorder.snapshot();

        assertThat(trace.missingReason(TurnLatencyPoint.QUERY_REWRITE_STARTED))
                .isEqualTo(TurnLatencyMissingReason.STAGE_BYPASSED);
        assertThat(trace.missingReason(TurnLatencyPoint.PROVIDER_FIRST_BYTE))
                .isEqualTo(TurnLatencyMissingReason.PROVIDER_UNSUPPORTED);
        assertThat(trace.missingReason(TurnLatencyPoint.CLIENT_FIRST_RENDER))
                .isEqualTo(TurnLatencyMissingReason.CLIENT_UNSUPPORTED);
        assertThat(trace.durationMillis(TurnLatencyMetric.PROVIDER_QUEUE_LATENCY)).isNull();
        assertThat(trace.durationMillis(TurnLatencyMetric.END_TO_END_TTFT)).isNull();
    }

    @Test
    void normalizesTheHistoricalFirstTokenAliasButAlwaysExportsCanonicalKey() {
        TurnLatencyTrace trace = new TurnLatencyTrace(
                TurnLatencyTrace.CONTRACT_REVISION,
                "trace-1", "turn-1", "release-1", "commit-1", "image-1",
                "adapter-v1", "prompt-v1", "model-1", "provider-1",
                "FAST", "NONE", "website",
                Map.of("provider.first_token.at", Instant.EPOCH), Map.of(), Map.of());

        assertThat(trace.timestamps())
                .containsEntry("provider.first_token_at", Instant.EPOCH)
                .doesNotContainKey("provider.first_token.at");
    }

    @Test
    void rejectsUnknownKeysAndSensitiveFreeFormExtensions() {
        assertThatThrownBy(() -> new TurnLatencyTrace(
                TurnLatencyTrace.CONTRACT_REVISION,
                "trace-1", "turn-1", "release-1", "commit-1", "image-1",
                "adapter-v1", "prompt-v1", "model-1", "provider-1",
                "FAST", "NONE", "website",
                Map.of("prompt.content", Instant.EPOCH), Map.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown Turn latency point");
    }

    @Test
    void serializesVersionEvidenceAndContentFreeSnakeCaseContract() throws Exception {
        MutableTime time = new MutableTime();
        TurnLatencyTraceRecorder recorder = recorder("FAST", time);
        recorder.mark(TurnLatencyPoint.TURN_RECEIVED);

        String json = new ObjectMapper().findAndRegisterModules()
                .writeValueAsString(recorder.snapshot());

        assertThat(json)
                .contains("\"contract_revision\":\"performance.v1\"")
                .contains("\"response_contract_revision\":\"adapter-v1\"")
                .contains("\"prompt_revision\":\"prompt-v1\"")
                .doesNotContain("prompt_content", "message_text", "memory_content", "secret");
    }

    @Test
    void acceptsAdditiveTopLevelFieldsAndDomainSpecificMissingReasonCodes() throws Exception {
        String json = """
                {
                  "contract_revision": "performance.v1",
                  "trace_id": "trace-1",
                  "turn_id": "turn-1",
                  "release_id": "release-1",
                  "git_commit": "commit-1",
                  "image_digest": "image-1",
                  "response_contract_revision": "adapter-v1",
                  "prompt_revision": "prompt-v1",
                  "model": "model-1",
                  "provider": "provider-1",
                  "execution_mode": "FAST",
                  "retrieval_mode": "NONE",
                  "client_type": "website",
                  "timestamps": {},
                  "durations_millis": {},
                  "missing_timestamp_reason_codes": {
                    "query_rewrite.started_at": "SKIPPED_FAST_L0"
                  },
                  "future_optional_dimension": "ignored-by-v1-reader"
                }
                """;

        TurnLatencyTrace trace = new ObjectMapper().findAndRegisterModules()
                .readValue(json, TurnLatencyTrace.class);
        assertThat(trace.missingReasonCode(TurnLatencyPoint.QUERY_REWRITE_STARTED))
                .isEqualTo("SKIPPED_FAST_L0");

        MutableTime time = new MutableTime();
        TurnLatencyTraceRecorder recorder = recorder("FAST", time);
        assertThat(recorder.markMissing(
                TurnLatencyPoint.QUERY_REWRITE_STARTED, "SKIPPED_FAST_L0")).isTrue();
        assertThat(recorder.snapshot().missingReasonCode(
                TurnLatencyPoint.QUERY_REWRITE_STARTED)).isEqualTo("SKIPPED_FAST_L0");
    }

    @Test
    void computesLinearPercentilesAndRequiredDimensionGroupsWithoutAverages() {
        List<TurnLatencyTrace> traces = List.of(
                endToEndTrace("a", "FAST", 10),
                endToEndTrace("b", "FAST", 20),
                endToEndTrace("c", "FAST", 30),
                endToEndTrace("d", "FAST", 40),
                endToEndTrace("e", "THINK", 90));

        TurnLatencyStatistics.Distribution all = TurnLatencyStatistics.summarize(
                traces, TurnLatencyMetric.END_TO_END_TTFT);
        Map<TurnLatencyStatistics.GroupKey, TurnLatencyStatistics.Distribution> grouped =
                TurnLatencyStatistics.summarizeByRequiredDimensions(
                        traces, TurnLatencyMetric.END_TO_END_TTFT);

        assertThat(all.sampleCount()).isEqualTo(5);
        assertThat(all.p50Millis()).isEqualTo(30.0);
        assertThat(all.p95Millis()).isEqualTo(80.0);
        assertThat(all.p99Millis()).isEqualTo(88.0);
        assertThat(grouped).hasSize(2);
        assertThat(grouped.entrySet()).anySatisfy(entry -> {
            assertThat(entry.getKey().executionMode()).isEqualTo("FAST");
            assertThat(entry.getValue().sampleCount()).isEqualTo(4);
            assertThat(entry.getValue().p50Millis()).isEqualTo(25.0);
        });
    }

    @Test
    void consumesBothSharedPerformanceV1LatencyFixtures() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        var standalone = mapper.readTree(Files.readString(
                contracts().resolve("fixtures/latency-trace.json"))).path("trace");
        var canonical = mapper.readTree(Files.readString(
                contracts().resolve("fixtures/canonical-fast-turn.json")))
                .path("turn_latency_trace");

        for (var node : List.of(standalone, canonical)) {
            TurnLatencyTrace trace = mapper.treeToValue(node, TurnLatencyTrace.class);
            assertThat(trace.contractRevision()).isEqualTo("performance.v1");
            assertThat(trace.timestamp(TurnLatencyPoint.CLIENT_FIRST_DELTA_RECEIVED))
                    .isEqualTo(Instant.parse("2026-08-01T12:00:00.083Z"));
            assertThat(trace.timestamp(TurnLatencyPoint.CLIENT_FIRST_RENDER))
                    .isEqualTo(Instant.parse("2026-08-01T12:00:00.086Z"));
            assertThat(trace.durationMillis(TurnLatencyMetric.PROVIDER_FIRST_TOKEN_LATENCY))
                    .isEqualTo(63L);
            assertThat(trace.durationMillis(TurnLatencyMetric.SSE_TRANSPORT_LATENCY))
                    .isEqualTo(3L);
            assertThat(trace.missingReasonCode(TurnLatencyPoint.QUERY_REWRITE_STARTED))
                    .isEqualTo("SKIPPED_FAST_L0");
        }
    }

    @Test
    void refusesToInventAnImprovementForMissingOrIncomparableDistributions() {
        TurnLatencyStatistics.Distribution measured = new TurnLatencyStatistics.Distribution(
                3, 3, 0, 40.0, 60.0, 80.0);
        TurnLatencyStatistics.Distribution improved = new TurnLatencyStatistics.Distribution(
                3, 3, 0, 25.0, 45.0, 70.0);
        TurnLatencyStatistics.Distribution missing = new TurnLatencyStatistics.Distribution(
                3, 0, 3, null, null, null);

        TurnLatencyStatistics.Comparison valid = TurnLatencyStatistics.compare(
                measured, improved, true);
        TurnLatencyStatistics.Comparison noBaseline = TurnLatencyStatistics.compare(
                missing, improved, true);
        TurnLatencyStatistics.Comparison mismatched = TurnLatencyStatistics.compare(
                measured, improved, false);

        assertThat(valid.status()).isEqualTo(TurnLatencyStatistics.ComparisonStatus.MEASURED);
        assertThat(valid.p50ReductionMillis()).isEqualTo(15.0);
        assertThat(valid.p95ReductionMillis()).isEqualTo(15.0);
        assertThat(valid.p99ReductionMillis()).isEqualTo(10.0);
        assertThat(noBaseline.status())
                .isEqualTo(TurnLatencyStatistics.ComparisonStatus.NOT_MEASURED);
        assertThat(noBaseline.reasonCode()).isEqualTo("BASELINE_MISSING");
        assertThat(noBaseline.p50ReductionMillis()).isNull();
        assertThat(mismatched.status())
                .isEqualTo(TurnLatencyStatistics.ComparisonStatus.NOT_MEASURED);
        assertThat(mismatched.reasonCode()).isEqualTo("INCOMPARABLE_DEFINITIONS");
    }

    @Test
    void acceptsExplicitClientWallTimesAndSeparatePlatformMessageLatency() {
        MutableTime time = new MutableTime();
        TurnLatencyTraceRecorder recorder = recorder("FAST", time);
        recorder.mark(TurnLatencyPoint.TURN_RECEIVED);
        recorder.markAt(TurnLatencyPoint.CLIENT_FIRST_RENDER, Instant.EPOCH.plusMillis(80));
        assertThat(recorder.recordAdapterDuration(
                TurnLatencyMetric.FIRST_PLATFORM_MESSAGE_LATENCY, 125L)).isTrue();
        assertThat(recorder.recordAdapterDuration(
                TurnLatencyMetric.FIRST_PLATFORM_MESSAGE_LATENCY, 126L)).isFalse();

        TurnLatencyTrace trace = recorder.snapshot();
        assertThat(trace.durationMillis(TurnLatencyMetric.END_TO_END_TTFT)).isEqualTo(80L);
        assertThat(trace.durationMillis(TurnLatencyMetric.FIRST_PLATFORM_MESSAGE_LATENCY))
                .isEqualTo(125L);
        assertThatThrownBy(() -> recorder.recordAdapterDuration(
                TurnLatencyMetric.END_TO_END_TTFT, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be derived");
    }

    private static TurnLatencyTrace endToEndTrace(String suffix, String mode, long millis) {
        MutableTime time = new MutableTime();
        TurnLatencyTraceRecorder recorder = new TurnLatencyTraceRecorder(
                metadata("trace-" + suffix, "turn-" + suffix, mode), time, time::nanoTime);
        recorder.mark(TurnLatencyPoint.TURN_RECEIVED);
        time.advanceMillis(millis);
        recorder.mark(TurnLatencyPoint.CLIENT_FIRST_RENDER);
        return recorder.snapshot();
    }

    private static Path contracts() {
        Path cursor = Path.of("").toAbsolutePath().normalize();
        for (int depth = 0; depth < 8 && cursor != null;
                depth++, cursor = cursor.getParent()) {
            Path candidate = cursor.resolve("contracts/performance/v1");
            if (Files.isRegularFile(candidate.resolve("performance-contract.schema.json"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("shared performance contracts are unavailable");
    }

    private static TurnLatencyTraceRecorder recorder(String mode, MutableTime time) {
        return new TurnLatencyTraceRecorder(metadata("trace-1", "turn-1", mode),
                time, time::nanoTime);
    }

    private static TurnLatencyTraceMetadata metadata(String traceId, String turnId, String mode) {
        return new TurnLatencyTraceMetadata(
                traceId, turnId, "release-1", "commit-1", "image-1",
                "adapter-v1", "prompt-v1", "model-1", "provider-1",
                mode, "NONE", "website");
    }

    private static void mark(
            TurnLatencyTraceRecorder recorder,
            MutableTime time,
            TurnLatencyPoint point,
            long advanceMillis) {
        time.advanceMillis(advanceMillis);
        assertThat(recorder.mark(point)).isTrue();
    }

    private static final class MutableTime extends Clock {
        private final AtomicLong nanos = new AtomicLong();
        private ZoneId zone = ZoneOffset.UTC;

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            this.zone = zone;
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.EPOCH.plusNanos(nanos.get());
        }

        private long nanoTime() {
            return nanos.get();
        }

        private void advanceMillis(long millis) {
            nanos.addAndGet(millis * 1_000_000L);
        }
    }
}

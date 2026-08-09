package com.meguri.core.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.dto.EventEnvelope;
import com.meguri.core.dto.EventMetadata;
import com.meguri.core.dto.LlmResponse;
import com.meguri.core.dto.RuntimeState;
import com.meguri.core.dto.TurnRequest;
import com.meguri.core.llm.LlmProvider;
import com.meguri.core.llm.NativeTextDeltaAggregator;
import com.meguri.core.memory.NoopMemoryGateway;
import com.meguri.core.runtime.ExpressionResolver;
import com.meguri.core.runtime.InMemoryTurnJournal;
import com.meguri.core.runtime.RuntimeStateMachine;
import com.meguri.core.runtime.TurnJournal;
import com.meguri.core.runtime.TurnOrchestrator;
import com.meguri.core.runtime.TurnRecord;
import com.meguri.core.training.TrainingFeedbackService;
import com.meguri.core.weather.WeatherConversationService;
import com.meguri.core.websearch.NoopWebSearchGateway;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repeatable, synthetic server-side TTFT benchmark.
 *
 * <p>This deliberately keeps the provider stream open after its first token so
 * the measurement includes any first-delta coalescing delay. It uses the real
 * TurnOrchestrator and event append path, but an in-memory journal and a timed
 * fake provider. It is not a provider, PostgreSQL, HTTP/SSE, reverse-proxy, or
 * client-render benchmark.</p>
 */
class SyntheticTurnTtftBenchmark {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @Test
    void recordsSyntheticFirstDurableDeltaDistribution() throws Exception {
        int warmup = positiveInt("meguri.ttft.warmup", 10);
        int samples = positiveInt("meguri.ttft.samples", 100);
        long providerDelayMs = positiveLong("meguri.ttft.providerDelayMs", 5L);
        long tailDelayMs = positiveLong("meguri.ttft.tailDelayMs", 75L);
        long coalescingDelayMs = positiveLong("meguri.ttft.coalescingDelayMs", 50L);
        long retrievalDelayMs = positiveLong("meguri.ttft.retrievalDelayMs", 20L);
        long promptSkillDelayMs = positiveLong("meguri.ttft.promptSkillDelayMs", 15L);

        for (int index = 0; index < warmup; index++) {
            measureOne(index, providerDelayMs, tailDelayMs);
            measureDeltaPolicy(true, providerDelayMs, coalescingDelayMs);
            measureDeltaPolicy(false, providerDelayMs, coalescingDelayMs);
            measureExecutionPath(true, retrievalDelayMs, promptSkillDelayMs, providerDelayMs);
            measureExecutionPath(false, retrievalDelayMs, promptSkillDelayMs, providerDelayMs);
        }

        List<Sample> measured = new ArrayList<>(samples);
        List<Double> legacyCoalescer = new ArrayList<>(samples);
        List<Double> immediateFirstDelta = new ArrayList<>(samples);
        List<Double> legacyFastPath = new ArrayList<>(samples);
        List<Double> executionFastBypass = new ArrayList<>(samples);
        for (int index = 0; index < samples; index++) {
            measured.add(measureOne(warmup + index, providerDelayMs, tailDelayMs));
            legacyCoalescer.add(measureDeltaPolicy(true, providerDelayMs, coalescingDelayMs));
            immediateFirstDelta.add(measureDeltaPolicy(false, providerDelayMs, coalescingDelayMs));
            legacyFastPath.add(measureExecutionPath(
                    true, retrievalDelayMs, promptSkillDelayMs, providerDelayMs));
            executionFastBypass.add(measureExecutionPath(
                    false, retrievalDelayMs, promptSkillDelayMs, providerDelayMs));
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schema_revision", "meguri.synthetic-ttft.v1");
        report.put("benchmark_type", "SYNTHETIC_IN_PROCESS");
        report.put("label", System.getProperty("meguri.ttft.label", "unspecified"));
        report.put("git_commit", System.getProperty("meguri.ttft.gitCommit", "unknown"));
        report.put("dirty_worktree", Boolean.parseBoolean(
                System.getProperty("meguri.ttft.dirtyWorktree", "true")));
        report.put("sample_count", samples);
        report.put("warmup_count", warmup);
        report.put("provider_first_token_delay_ms", providerDelayMs);
        report.put("provider_tail_delay_ms", tailDelayMs);
        report.put("subsequent_delta_coalescing_delay_ms", coalescingDelayMs);
        report.put("synthetic_retrieval_delay_ms", retrievalDelayMs);
        report.put("synthetic_prompt_skill_delay_ms", promptSkillDelayMs);
        report.put("provider", "synthetic-timed-provider");
        report.put("model", "synthetic-open-stream");
        report.put("execution_mode", "CURRENT_DEFAULT_PATH");
        report.put("retrieval_mode", "NONE");
        report.put("client_type", "website-in-process");
        report.put("scope", "TurnOrchestrator.start to first persisted text.delta");
        report.put("excluded", List.of(
                "real provider and provider queue",
                "PostgreSQL and network persistence",
                "HTTP/SSE serialization and socket flush",
                "reverse proxy buffering",
                "client transport and first render"));
        report.put("environment", Map.of(
                "java_version", System.getProperty("java.version"),
                "os_name", System.getProperty("os.name"),
                "available_processors", Runtime.getRuntime().availableProcessors()));
        report.put("metrics_ms", Map.of(
                "pre_provider_latency", distribution(measured.stream()
                        .map(Sample::preProviderMs).toList()),
                "provider_first_token_latency", distribution(measured.stream()
                        .map(Sample::providerFirstTokenMs).toList()),
                "event_persist_latency", distribution(measured.stream()
                        .map(Sample::eventPersistMs).toList()),
                "server_first_durable_delta_ttft", distribution(measured.stream()
                        .map(Sample::serverDurableTtftMs).toList())));
        report.put("delta_policy_comparison", Map.of(
                "status", "MEASURED_SYNTHETIC",
                "metric", "provider_stream_subscription_to_first_chunk_emission",
                "baseline", "LEGACY_FIRST_TOKEN_WAITS_FOR_WINDOW",
                "candidate", "FIRST_TOKEN_IMMEDIATE_THEN_COALESCE",
                "baseline_distribution_ms", distribution(legacyCoalescer),
                "candidate_distribution_ms", distribution(immediateFirstDelta),
                "reduction_ms", reduction(legacyCoalescer, immediateFirstDelta),
                "boundary", "NativeTextDeltaAggregator seam; no journal, network, or client"));
        report.put("execution_fast_path_comparison", Map.of(
                "status", "MEASURED_SYNTHETIC",
                "metric", "turn_received_to_first_provider_chunk",
                "baseline", "LEGACY_FAST_WITH_RETRIEVAL_AND_PROMPT_SKILL",
                "candidate", "EXECUTION_FAST_BYPASS",
                "baseline_distribution_ms", distribution(legacyFastPath),
                "candidate_distribution_ms", distribution(executionFastBypass),
                "reduction_ms", reduction(legacyFastPath, executionFastBypass),
                "legacy_executed_stages", List.of("retrieval", "prompt_skill"),
                "fast_bypassed_stages", List.of("retrieval", "prompt_skill"),
                "boundary", "controlled Reactor stage model; not the integrated Turn FAST path"));

        Path reportPath = Path.of(System.getProperty(
                "meguri.ttft.report", "target/synthetic-ttft-report.json"));
        Path parent = reportPath.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(reportPath.toFile(), report);

        assertThat(measured).hasSize(samples);
        assertThat(legacyCoalescer).hasSize(samples);
        assertThat(immediateFirstDelta).hasSize(samples);
        assertThat(legacyFastPath).hasSize(samples);
        assertThat(executionFastBypass).hasSize(samples);
        assertThat(measured).allSatisfy(sample -> {
            assertThat(sample.preProviderMs()).isGreaterThanOrEqualTo(0.0);
            assertThat(sample.providerFirstTokenMs()).isGreaterThanOrEqualTo(0.0);
            assertThat(sample.eventPersistMs()).isGreaterThanOrEqualTo(0.0);
            assertThat(sample.serverDurableTtftMs()).isGreaterThanOrEqualTo(0.0);
        });
        System.out.println("MEGURI_TTFT_REPORT=" + reportPath.toAbsolutePath());
        System.out.println(MAPPER.writeValueAsString(report));
    }

    private static double measureDeltaPolicy(
            boolean legacy, long providerDelayMs, long coalescingDelayMs) {
        Flux<String> providerTokens = Flux.concat(
                Mono.delay(Duration.ofMillis(providerDelayMs))
                        .map(ignored -> "first").flux(),
                Flux.never());
        Flux<String> chunks = legacy
                ? legacyAggregate(providerTokens, 80, Duration.ofMillis(coalescingDelayMs))
                : NativeTextDeltaAggregator.aggregate(
                        providerTokens, 80, Duration.ofMillis(coalescingDelayMs));
        long started = System.nanoTime();
        String first = chunks.blockFirst(Duration.ofSeconds(2));
        if (!"first".equals(first)) throw new IllegalStateException("first chunk was not preserved");
        return millis(System.nanoTime() - started);
    }

    private static double measureExecutionPath(
            boolean legacy,
            long retrievalDelayMs,
            long promptSkillDelayMs,
            long providerDelayMs) {
        Mono<Void> preProvider = legacy
                ? Mono.delay(Duration.ofMillis(retrievalDelayMs)).then()
                        .then(Mono.delay(Duration.ofMillis(promptSkillDelayMs)).then())
                : Mono.empty();
        Flux<String> providerTokens = preProvider.thenMany(
                Flux.concat(
                        Mono.delay(Duration.ofMillis(providerDelayMs))
                                .map(ignored -> "first").flux(),
                        Flux.never()));
        long started = System.nanoTime();
        String first = NativeTextDeltaAggregator.aggregate(
                        providerTokens, 80, Duration.ofSeconds(5))
                .blockFirst(Duration.ofSeconds(2));
        if (!"first".equals(first)) throw new IllegalStateException("first chunk was not preserved");
        return millis(System.nanoTime() - started);
    }

    /** Reference implementation of the pre-change policy: every token enters the same window. */
    private static Flux<String> legacyAggregate(
            Flux<String> tokens, int maxCharacters, Duration flushInterval) {
        return Flux.defer(() -> Flux.create(sink -> {
            Object lock = new Object();
            StringBuilder pending = new StringBuilder(maxCharacters);
            AtomicBoolean terminated = new AtomicBoolean();
            Runnable flush = () -> {
                synchronized (lock) {
                    if (!terminated.get() && !pending.isEmpty()) {
                        sink.next(pending.toString());
                        pending.setLength(0);
                    }
                }
            };
            Disposable timer = Schedulers.parallel().schedulePeriodically(
                    flush, flushInterval.toNanos(), flushInterval.toNanos(),
                    TimeUnit.NANOSECONDS);
            Disposable upstream = tokens.subscribe(token -> {
                if (token == null || token.isEmpty()) return;
                synchronized (lock) {
                    if (terminated.get()) return;
                    int offset = 0;
                    while (offset < token.length()) {
                        int copied = Math.min(
                                maxCharacters - pending.length(), token.length() - offset);
                        pending.append(token, offset, offset + copied);
                        offset += copied;
                        if (pending.length() == maxCharacters) flush.run();
                    }
                }
            }, error -> terminateLegacy(sink, lock, pending, terminated, timer, error),
                    () -> terminateLegacy(sink, lock, pending, terminated, timer, null));
            sink.onDispose(() -> {
                terminated.set(true);
                timer.dispose();
                upstream.dispose();
            });
        }, FluxSink.OverflowStrategy.BUFFER));
    }

    private static void terminateLegacy(
            FluxSink<String> sink,
            Object lock,
            StringBuilder pending,
            AtomicBoolean terminated,
            Disposable timer,
            Throwable error) {
        synchronized (lock) {
            if (!terminated.compareAndSet(false, true)) return;
            if (!pending.isEmpty()) sink.next(pending.toString());
            pending.setLength(0);
            timer.dispose();
            if (error == null) sink.complete();
            else sink.error(error);
        }
    }

    private static Sample measureOne(int index, long providerDelayMs, long tailDelayMs)
            throws Exception {
        Probe probe = new Probe();
        TurnJournal journal = new ProbingTurnJournal(probe);
        LlmProvider provider = new TimedStreamingProvider(probe, providerDelayMs, tailDelayMs);
        TurnOrchestrator runtime = runtime(provider, journal);
        String sessionId = "synthetic-ttft-" + index;

        probe.turnReceivedNanos.set(System.nanoTime());
        TurnRecord record = runtime.start(new TurnRequest(
                "benchmark-user", "website", sessionId, "synthetic greeting"));
        long persistedNanos = probe.firstDeltaPersistedNanos.get(5, TimeUnit.SECONDS);
        runtime.reset();
        record.getDone().get(5, TimeUnit.SECONDS);

        long receivedNanos = required(probe.turnReceivedNanos, "turn received");
        long providerRequestedNanos = required(probe.providerRequestedNanos, "provider requested");
        long firstTokenNanos = required(probe.firstTokenNanos, "provider first token");
        return new Sample(
                millis(providerRequestedNanos - receivedNanos),
                millis(firstTokenNanos - providerRequestedNanos),
                millis(persistedNanos - firstTokenNanos),
                millis(persistedNanos - receivedNanos));
    }

    private static TurnOrchestrator runtime(LlmProvider provider, TurnJournal journal) {
        return new TurnOrchestrator(
                provider, null, new RuntimeStateMachine(), new ExpressionResolver(),
                Duration.ofMillis(1), MAPPER, new NoopMemoryGateway(),
                new NoopWebSearchGateway(), TrainingFeedbackService.disabled(MAPPER),
                WeatherConversationService.disabled(), journal);
    }

    private static Map<String, Object> distribution(List<Double> values) {
        List<Double> ordered = values.stream().sorted().toList();
        return Map.of(
                "sample_count", ordered.size(),
                "p50", round(percentile(ordered, 0.50)),
                "p95", round(percentile(ordered, 0.95)),
                "p99", round(percentile(ordered, 0.99)));
    }

    private static Map<String, Double> reduction(
            List<Double> baseline, List<Double> candidate) {
        return Map.of(
                "p50", round(percentile(baseline.stream().sorted().toList(), 0.50)
                        - percentile(candidate.stream().sorted().toList(), 0.50)),
                "p95", round(percentile(baseline.stream().sorted().toList(), 0.95)
                        - percentile(candidate.stream().sorted().toList(), 0.95)),
                "p99", round(percentile(baseline.stream().sorted().toList(), 0.99)
                        - percentile(candidate.stream().sorted().toList(), 0.99)));
    }

    private static double percentile(List<Double> ordered, double quantile) {
        if (ordered.isEmpty()) throw new IllegalArgumentException("values must not be empty");
        double position = (ordered.size() - 1) * quantile;
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) return ordered.get(lower);
        double fraction = position - lower;
        return ordered.get(lower) + (ordered.get(upper) - ordered.get(lower)) * fraction;
    }

    private static int positiveInt(String property, int fallback) {
        int value = Integer.parseInt(System.getProperty(property, Integer.toString(fallback)));
        if (value <= 0) throw new IllegalArgumentException(property + " must be positive");
        return value;
    }

    private static long positiveLong(String property, long fallback) {
        long value = Long.parseLong(System.getProperty(property, Long.toString(fallback)));
        if (value <= 0) throw new IllegalArgumentException(property + " must be positive");
        return value;
    }

    private static long required(AtomicLong value, String label) {
        long observed = value.get();
        if (observed == 0L) throw new IllegalStateException(label + " was not observed");
        return observed;
    }

    private static double millis(long nanos) {
        return nanos / 1_000_000.0;
    }

    private static double round(double value) {
        return Math.round(value * 1_000.0) / 1_000.0;
    }

    private record Sample(
            double preProviderMs,
            double providerFirstTokenMs,
            double eventPersistMs,
            double serverDurableTtftMs) { }

    private static final class Probe {
        private final AtomicLong turnReceivedNanos = new AtomicLong();
        private final AtomicLong providerRequestedNanos = new AtomicLong();
        private final AtomicLong firstTokenNanos = new AtomicLong();
        private final CompletableFuture<Long> firstDeltaPersistedNanos = new CompletableFuture<>();
    }

    private static final class TimedStreamingProvider implements LlmProvider {
        private final Probe probe;
        private final Duration firstTokenDelay;
        private final Duration tailDelay;

        private TimedStreamingProvider(Probe probe, long firstTokenDelayMs, long tailDelayMs) {
            this.probe = probe;
            this.firstTokenDelay = Duration.ofMillis(firstTokenDelayMs);
            this.tailDelay = Duration.ofMillis(tailDelayMs);
        }

        @Override
        public Mono<LlmResponse> respond(TurnRequest request, RuntimeState state,
                                         List<String> canon, List<String> memories,
                                         List<String> recentContext) {
            return Mono.error(new AssertionError("native path must not call respond"));
        }

        @Override
        public boolean supportsNativeStreaming() {
            return true;
        }

        @Override
        public Flux<String> stream(TurnRequest request, RuntimeState state,
                                   List<String> canon, List<String> memories,
                                   List<String> recentContext, List<String> webResults) {
            probe.providerRequestedNanos.compareAndSet(0L, System.nanoTime());
            return Flux.concat(
                            Mono.delay(firstTokenDelay).map(ignored -> "first").flux(),
                            Mono.delay(tailDelay).map(ignored -> "tail").flux())
                    .doOnNext(ignored -> probe.firstTokenNanos.compareAndSet(0L, System.nanoTime()));
        }

        @Override
        public Mono<LlmResponse> finalizeStream(String reply, TurnRequest request, RuntimeState state) {
            return Mono.just(new LlmResponse(reply));
        }

        @Override
        public String providerName() {
            return "synthetic-timed-provider";
        }

        @Override
        public String modelId() {
            return "synthetic-open-stream";
        }
    }

    private static final class ProbingTurnJournal implements TurnJournal {
        private final InMemoryTurnJournal delegate = new InMemoryTurnJournal(MAPPER);
        private final Probe probe;

        private ProbingTurnJournal(Probe probe) {
            this.probe = probe;
        }

        @Override
        public Acceptance accept(TurnRequest request, String idempotencyKey, Instant deadlineAt) {
            return delegate.accept(request, idempotencyKey, deadlineAt);
        }

        @Override
        public Acceptance acceptRetry(TurnRequest request, String idempotencyKey,
                                      Instant deadlineAt, String retryOfTurnId) {
            return delegate.acceptRetry(request, idempotencyKey, deadlineAt, retryOfTurnId);
        }

        @Override
        public TurnRecord create(TurnRequest request, Instant deadlineAt) {
            return delegate.create(request, deadlineAt);
        }

        @Override
        public TurnRecord turn(String turnId) {
            return delegate.turn(turnId);
        }

        @Override
        public Map<String, TurnRecord> turns() {
            return delegate.turns();
        }

        @Override
        public List<EventEnvelope> events(String sessionId) {
            return delegate.events(sessionId);
        }

        @Override
        public long firstSequence(String sessionId) {
            return delegate.firstSequence(sessionId);
        }

        @Override
        public long lastSequence(String sessionId) {
            return delegate.lastSequence(sessionId);
        }

        @Override
        public EventEnvelope append(TurnRecord record, String type,
                                    Map<String, Object> data, EventMetadata metadata) {
            EventEnvelope event = delegate.append(record, type, data, metadata);
            observeFirstDelta(type);
            return event;
        }

        @Override
        public EventEnvelope appendExecution(TurnRecord record, String ownerId, String type,
                                             Map<String, Object> data, EventMetadata metadata) {
            EventEnvelope event = delegate.appendExecution(record, ownerId, type, data, metadata);
            observeFirstDelta(type);
            return event;
        }

        @Override
        public void persist(TurnRecord record) {
            delegate.persist(record);
        }

        @Override
        public boolean claimExecution(TurnRecord record, String ownerId, Duration lease) {
            return delegate.claimExecution(record, ownerId, lease);
        }

        @Override
        public boolean heartbeatExecution(TurnRecord record, String ownerId, Duration lease) {
            return delegate.heartbeatExecution(record, ownerId, lease);
        }

        @Override
        public void releaseExecution(TurnRecord record, String ownerId) {
            delegate.releaseExecution(record, ownerId);
        }

        @Override
        public boolean requestCancellation(TurnRecord record) {
            return delegate.requestCancellation(record);
        }

        @Override
        public boolean refreshCancellation(TurnRecord record) {
            return delegate.refreshCancellation(record);
        }

        @Override
        public void clear() {
            delegate.clear();
        }

        private void observeFirstDelta(String type) {
            if ("text.delta".equals(type)) {
                probe.firstDeltaPersistedNanos.complete(System.nanoTime());
            }
        }
    }
}

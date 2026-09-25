package com.meguri.core.retrieval;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Parallel typed retrieval runtime with isolated lane failures and deadline-aware Graph fallback. */
public final class RetrievalRuntime implements AutoCloseable {
    private final RetrievalPlanner planner;
    private final RetrievalGate gate;
    private final Map<SourceType, RetrievalProvider> providers;
    private final RetrievalProvider hybridKnowledge;
    private final KnowledgeGraphRetrievalService graph;
    private final BundleAssembler assembler;
    private final RetrievalTraceRepository traces;
    private final RetrievalAuthorizationPolicy authorization;
    private final ExecutorService executor;
    private final Duration maxLaneDuration;
    private final Duration maxGraphDuration;
    private final boolean ownsExecutor;

    public RetrievalRuntime(
            RetrievalPlanner planner,
            Map<SourceType, RetrievalProvider> providers,
            RetrievalProvider hybridKnowledge,
            KnowledgeGraphRetrievalService graph,
            RetrievalTraceRepository traces,
            Duration maxLaneDuration,
            Duration maxGraphDuration) {
        this(planner, new RetrievalGate(), providers, hybridKnowledge, graph,
                new BundleAssembler(), traces, Executors.newVirtualThreadPerTaskExecutor(),
                maxLaneDuration, maxGraphDuration, true,
                RetrievalAuthorizationPolicy.allowAll());
    }

    public RetrievalRuntime(
            RetrievalPlanner planner,
            RetrievalGate gate,
            Map<SourceType, RetrievalProvider> providers,
            RetrievalProvider hybridKnowledge,
            KnowledgeGraphRetrievalService graph,
            BundleAssembler assembler,
            RetrievalTraceRepository traces,
            ExecutorService executor,
            Duration maxLaneDuration,
            Duration maxGraphDuration,
            boolean ownsExecutor) {
        this(planner, gate, providers, hybridKnowledge, graph, assembler, traces,
                executor, maxLaneDuration, maxGraphDuration, ownsExecutor,
                RetrievalAuthorizationPolicy.allowAll());
    }

    public RetrievalRuntime(
            RetrievalPlanner planner,
            RetrievalGate gate,
            Map<SourceType, RetrievalProvider> providers,
            RetrievalProvider hybridKnowledge,
            KnowledgeGraphRetrievalService graph,
            BundleAssembler assembler,
            RetrievalTraceRepository traces,
            ExecutorService executor,
            Duration maxLaneDuration,
            Duration maxGraphDuration,
            boolean ownsExecutor,
            RetrievalAuthorizationPolicy authorization) {
        this.planner = Objects.requireNonNull(planner);
        this.gate = Objects.requireNonNull(gate);
        EnumMap<SourceType, RetrievalProvider> copy = new EnumMap<>(SourceType.class);
        if (providers != null) copy.putAll(providers);
        copy.remove(SourceType.KNOWLEDGE);
        this.providers = Map.copyOf(copy);
        this.hybridKnowledge = Objects.requireNonNull(hybridKnowledge);
        this.graph = graph;
        this.assembler = Objects.requireNonNull(assembler);
        this.traces = Objects.requireNonNull(traces);
        this.authorization = Objects.requireNonNull(authorization);
        this.executor = Objects.requireNonNull(executor);
        this.maxLaneDuration = positive(maxLaneDuration, Duration.ofSeconds(2));
        this.maxGraphDuration = positive(maxGraphDuration, Duration.ofMillis(500));
        this.ownsExecutor = ownsExecutor;
    }

    public RetrievalBundle retrieve(
            String query, RetrievalMode mode, RetrievalContext context) {
        Objects.requireNonNull(context);
        RetrievalMode gatedMode = gate.classify(query, mode);
        RetrievalPlan plan = gate.validate(authorization.authorize(
                gate.validate(planner.plan(query, gatedMode, context.deadline())), context));
        if (plan.mode() == RetrievalMode.NONE) {
            RetrievalBundle bundle = assembler.assemble(context.traceId(), plan, List.of());
            saveTrace(bundle, context);
            return bundle;
        }

        List<CompletableFuture<RetrievalLaneResult>> futures = new ArrayList<>();
        for (SourceType source : plan.sources()) {
            if (source == SourceType.KNOWLEDGE) {
                futures.add(laneFuture(source, context,
                        () -> retrieveKnowledge(plan, context)));
            } else {
                futures.add(laneFuture(source, context,
                        () -> retrieveProvider(source, plan, context)));
            }
        }
        List<RetrievalLaneResult> lanes = futures.stream().map(CompletableFuture::join).toList();
        RetrievalBundle bundle = assembler.assemble(context.traceId(), plan, lanes);
        saveTrace(bundle, context);
        return bundle;
    }

    public RetrievalBundle retrieve(
            String query, RetrievalMode mode, String principalId, String snapshotId,
            long revision, Instant deadline) {
        String traceId = UUID.randomUUID().toString();
        return retrieve(query, mode, new RetrievalContext(
                principalId, java.util.Set.of(), snapshotId, revision,
                Instant.now(), deadline, traceId));
    }

    private RetrievalLaneResult retrieveProvider(
            SourceType source, RetrievalPlan plan, RetrievalContext context) {
        RetrievalProvider provider = providers.get(source);
        if (provider == null) return RetrievalLaneResult.skipped(source, "provider_not_configured");
        int limit = plan.sourceBudgets().getOrDefault(source, 0);
        RetrievalProviderResult result = provider.retrieveWithDiagnostics(
                plan.query().queryFor(source), limit, context);
        return laneResult(source, provider.getClass().getSimpleName(),
                validateItems(source, result.items(), context.traceId()),
                result.degradations());
    }

    private RetrievalLaneResult retrieveKnowledge(
            RetrievalPlan plan, RetrievalContext context) {
        int limit = plan.sourceBudgets().getOrDefault(SourceType.KNOWLEDGE, 0);
        if (!plan.graphEnabled() || graph == null) {
            RetrievalProviderResult hybrid = callHybrid(plan, context, limit);
            return laneResult(SourceType.KNOWLEDGE, "hybrid",
                    hybrid.items(), hybrid.degradations());
        }

        GraphRetrievalResult graphResult;
        Future<GraphRetrievalResult> graphTask = executor.submit(
                () -> graph.retrieve(plan, context, limit));
        try {
            long timeoutMs = graphBudget(context).toMillis();
            graphResult = graphTask.get(Math.max(1, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            graphTask.cancel(true);
            Thread.currentThread().interrupt();
            graphResult = fallback("graph_interrupted");
        } catch (ExecutionException error) {
            graphResult = fallback("graph_failure");
        } catch (TimeoutException error) {
            graphTask.cancel(true);
            graphResult = fallback("graph_timeout");
        }

        if (graphResult.fallbackRequired()) {
            RetrievalProviderResult hybrid = callHybrid(plan, context, limit);
            List<String> degradations = mergeDegradations(
                    graphResult.degradations(), hybrid.degradations());
            return new RetrievalLaneResult(
                    SourceType.KNOWLEDGE, RetrievalLaneResult.Status.DEGRADED,
                    "hybrid", hybrid.items(), degradations);
        }
        List<RetrievalItem> combined = new ArrayList<>(graphResult.items());
        int remaining = Math.max(0, limit - combined.size());
        RetrievalProviderResult hybrid = remaining > 0
                ? callHybrid(plan, context, remaining)
                : RetrievalProviderResult.success(List.of());
        combined.addAll(hybrid.items());
        List<String> degradations = mergeDegradations(
                graphResult.degradations(), hybrid.degradations());
        return new RetrievalLaneResult(
                SourceType.KNOWLEDGE,
                degradations.isEmpty()
                        ? RetrievalLaneResult.Status.OK : RetrievalLaneResult.Status.DEGRADED,
                "graph+hybrid", validateItems(
                        SourceType.KNOWLEDGE, combined, context.traceId()),
                degradations);
    }

    private RetrievalProviderResult callHybrid(
            RetrievalPlan plan, RetrievalContext context, int limit) {
        RetrievalProviderResult result = hybridKnowledge.retrieveWithDiagnostics(
                plan.query().queryFor(SourceType.KNOWLEDGE), limit, context);
        return new RetrievalProviderResult(
                validateItems(SourceType.KNOWLEDGE, result.items(), context.traceId()),
                result.degradations());
    }

    private CompletableFuture<RetrievalLaneResult> laneFuture(
            SourceType source, RetrievalContext context,
            java.util.concurrent.Callable<RetrievalLaneResult> task) {
        long timeoutMs = Math.max(1, budget(context, maxLaneDuration).toMillis());
        CompletableFuture<RetrievalLaneResult> result = new CompletableFuture<>();
        Future<?> submitted = executor.submit(() -> {
            try {
                result.complete(task.call());
            } catch (Exception error) {
                result.complete(unavailable(source, "lane_failure"));
            }
        });
        CompletableFuture.delayedExecutor(timeoutMs, TimeUnit.MILLISECONDS).execute(() -> {
            if (result.complete(unavailable(source, "lane_timeout"))) {
                submitted.cancel(true);
            }
        });
        result.whenComplete((ignored, error) -> {
            if (result.isCancelled()) submitted.cancel(true);
        });
        return result;
    }

    private static RetrievalLaneResult laneResult(
            SourceType source, String provider, List<RetrievalItem> items,
            List<String> degradations) {
        return new RetrievalLaneResult(
                source,
                degradations == null || degradations.isEmpty()
                        ? RetrievalLaneResult.Status.OK
                        : RetrievalLaneResult.Status.DEGRADED,
                provider, items, degradations);
    }

    private static List<String> mergeDegradations(
            List<String> left, List<String> right) {
        java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>();
        if (left != null) merged.addAll(left);
        if (right != null) merged.addAll(right);
        return List.copyOf(merged);
    }

    private static List<RetrievalItem> validateItems(
            SourceType source, List<RetrievalItem> items, String traceId) {
        if (items == null) return List.of();
        for (RetrievalItem item : items) {
            if (item.sourceType() != source || !traceId.equals(item.traceId())) {
                throw new IllegalArgumentException("provider returned an item outside its lane or trace");
            }
        }
        return List.copyOf(items);
    }

    private static RetrievalLaneResult unavailable(SourceType source, String reason) {
        return new RetrievalLaneResult(source, RetrievalLaneResult.Status.UNAVAILABLE,
                "none", List.of(), List.of(reason));
    }

    private static GraphRetrievalResult fallback(String reason) {
        return new GraphRetrievalResult(
                GraphRetrievalResult.Status.FALLBACK, List.of(), List.of(reason));
    }

    private static Duration budget(RetrievalContext context, Duration maximum) {
        Duration remaining = context.remaining();
        return remaining.compareTo(maximum) < 0 ? remaining : maximum;
    }

    private Duration graphBudget(RetrievalContext context) {
        Duration remaining = context.remaining();
        Duration reserved = remaining.toMillis() > 2
                ? Duration.ofMillis(Math.max(1, remaining.toMillis() / 2))
                : remaining;
        return reserved.compareTo(maxGraphDuration) < 0 ? reserved : maxGraphDuration;
    }

    private void saveTrace(RetrievalBundle bundle, RetrievalContext context) {
        traces.save(RetrievalTrace.capture(bundle, context));
    }

    RetrievalTraceRepository traceRepository() {
        return traces;
    }

    private static Duration positive(Duration value, Duration fallback) {
        return value == null || value.isNegative() || value.isZero() ? fallback : value;
    }

    @Override
    public void close() {
        if (ownsExecutor) executor.close();
    }
}

package com.meguri.core.react;

import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryReactTraceRepository implements ReactTraceRepository {
    private final ConcurrentHashMap<String, List<ReactRoundTrace>> traces = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReactExecutionSummary> results = new ConcurrentHashMap<>();

    @Override
    public Mono<Void> append(ReactRoundTrace trace) {
        return Mono.fromRunnable(() -> traces.compute(trace.turnId(), (ignored, current) -> {
            List<ReactRoundTrace> copy = current == null
                    ? new ArrayList<>() : new ArrayList<>(current);
            copy.add(trace);
            return List.copyOf(copy);
        }));
    }

    @Override
    public Mono<Void> complete(ReactExecutionSummary summary) {
        return Mono.fromRunnable(() -> results.put(summary.turnId(), summary));
    }

    public List<ReactRoundTrace> traces(String turnId) {
        return traces.getOrDefault(turnId, List.of());
    }

    public ReactExecutionSummary result(String turnId) {
        return results.get(turnId);
    }
}

package com.meguri.core.retrieval;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class InMemoryRetrievalTraceRepository implements RetrievalTraceRepository {
    private final ConcurrentMap<String, RetrievalTrace> traces = new ConcurrentHashMap<>();

    @Override
    public void save(RetrievalTrace trace) {
        if (trace == null) throw new IllegalArgumentException("trace is required");
        traces.put(trace.traceId(), trace);
    }

    @Override
    public Optional<RetrievalTrace> find(String traceId) {
        return Optional.ofNullable(traces.get(traceId));
    }
}

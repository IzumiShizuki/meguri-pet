package com.meguri.core.retrieval;

import java.util.Optional;

public interface RetrievalTraceRepository {
    void save(RetrievalTrace trace);

    Optional<RetrievalTrace> find(String traceId);

    default Optional<RetrievalTraceProjection> findProjection(String traceId) {
        return find(traceId).map(RetrievalTraceProjection::capture);
    }
}

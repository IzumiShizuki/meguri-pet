package com.meguri.core.context;

import java.time.Instant;

/** Named repository boundary for exact audit and replay of a completed context selection. */
public final class ContextBuildTraceRepository {
    private final ContextRuntimePersistence persistence;

    public ContextBuildTraceRepository(ContextRuntimePersistence persistence) {
        this.persistence = persistence;
    }

    public void save(String traceId, String conversationId, long graphRevision,
                     String requestDigest, ContextBundle bundle) {
        persistence.saveTrace(new ContextRuntimePersistence.ContextBuildTrace(
                traceId, conversationId, graphRevision, requestDigest, bundle, Instant.now()));
    }

    public ContextBundle replay(String traceId) {
        return persistence.findTrace(traceId)
                .orElseThrow(() -> new IllegalArgumentException("context build trace does not exist"))
                .bundle();
    }
}

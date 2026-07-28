package com.meguri.core.retrieval;

/** Central ACL, ACTIVE-state and revision/snapshot filter owned by the knowledge store. */
@FunctionalInterface
public interface GraphVisibilityPort {
    Visibility visibility(ResourceKind kind, String resourceId, RetrievalContext context);

    enum ResourceKind {
        ENTITY,
        EDGE,
        EVIDENCE_CHUNK
    }

    enum Visibility {
        VISIBLE,
        ACL_DENIED,
        INACTIVE,
        VERSION_MISMATCH
    }
}

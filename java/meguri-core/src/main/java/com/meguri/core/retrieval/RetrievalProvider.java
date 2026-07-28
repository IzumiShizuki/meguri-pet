package com.meguri.core.retrieval;

import java.util.List;

/** Typed provider boundary. Implementations must honor context.remaining(). */
@FunctionalInterface
public interface RetrievalProvider {
    List<RetrievalItem> retrieve(String query, int limit, RetrievalContext context);

    default RetrievalProviderResult retrieveWithDiagnostics(
            String query, int limit, RetrievalContext context) {
        return RetrievalProviderResult.success(retrieve(query, limit, context));
    }
}

package com.meguri.core.retrieval;

import java.time.Instant;

@FunctionalInterface
public interface RetrievalPlanner {
    RetrievalPlan plan(String query, RetrievalMode mode, Instant deadline);
}

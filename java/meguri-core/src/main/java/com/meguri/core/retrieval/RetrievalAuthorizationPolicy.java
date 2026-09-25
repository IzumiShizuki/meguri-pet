package com.meguri.core.retrieval;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

/** Applies server-side source authorization after planning and before dispatch. */
@FunctionalInterface
public interface RetrievalAuthorizationPolicy {
    RetrievalPlan authorize(RetrievalPlan plan, RetrievalContext context);

    static RetrievalAuthorizationPolicy allowAll() {
        return (plan, context) -> plan;
    }

    static RetrievalAuthorizationPolicy scopeBased() {
        return (plan, context) -> {
            EnumSet<SourceType> allowed = EnumSet.noneOf(SourceType.class);
            allowed.addAll(plan.sources());
            if (!context.aclScopes().contains("memory:read")) allowed.remove(SourceType.MEMORY);
            if (!context.aclScopes().contains("web:read")) allowed.remove(SourceType.WEB);
            EnumMap<SourceType, Integer> budgets = retain(plan.sourceBudgets(), allowed);
            EnumMap<SourceType, Integer> seats = retain(plan.sourceSeats(), allowed);
            return new RetrievalPlan(plan.mode(), plan.query(), allowed, budgets, seats,
                    plan.graphEnabled() && allowed.contains(SourceType.KNOWLEDGE),
                    plan.graphMaxHops(), plan.totalItemLimit(), plan.deadline());
        };
    }

    private static EnumMap<SourceType, Integer> retain(
            Map<SourceType, Integer> values, EnumSet<SourceType> allowed) {
        EnumMap<SourceType, Integer> retained = new EnumMap<>(SourceType.class);
        values.forEach((source, value) -> {
            if (allowed.contains(source)) retained.put(source, value);
        });
        return retained;
    }
}

package com.meguri.core.retrieval;

import com.meguri.core.memory.MemoryGateway;
import com.meguri.core.memory.MemoryRecall;
import java.util.List;
import java.util.Map;

/** Read-only adapter for the authoritative Memory service; unavailable recalls fail closed. */
public final class MemoryRetrievalProviderAdapter implements RetrievalProvider {
    private final MemoryGateway gateway;
    private final WeightedRrfFusion fusion;

    public MemoryRetrievalProviderAdapter(MemoryGateway gateway) {
        this.gateway = java.util.Objects.requireNonNull(gateway);
        this.fusion = new WeightedRrfFusion(60, Map.of(
                RankSignal.STRUCTURED, 1.4,
                RankSignal.KEYWORD, 1.0,
                RankSignal.VECTOR, 1.0));
    }

    @Override
    public List<RetrievalItem> retrieve(String query, int limit, RetrievalContext context) {
        if (limit <= 0 || context.turnRequest() == null
                || !context.turnRequest().formalMemoryAllowed()) return List.of();
        MemoryRecall recall = gateway.recall(context.turnRequest())
                .block(context.remaining());
        if (recall == null || !recall.available()) return List.of();
        List<RetrievalItem> structured = java.util.stream.IntStream
                .range(0, Math.min(limit, recall.memories().size()))
                .mapToObj(index -> LegacyRetrievalItems.item(
                        SourceType.MEMORY, recall.memories().get(index), index + 1, 0.95,
                        LegacyRetrievalItems.version(context), context))
                .toList();
        return fusion.fuse(Map.of(RankSignal.STRUCTURED, structured));
    }

    @Override
    public RetrievalProviderResult retrieveWithDiagnostics(
            String query, int limit, RetrievalContext context) {
        try {
            return RetrievalProviderResult.success(retrieve(query, limit, context));
        } catch (RuntimeException error) {
            return new RetrievalProviderResult(List.of(), List.of("memory_unavailable"));
        }
    }
}

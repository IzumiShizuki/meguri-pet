package com.meguri.core.retrieval;

import com.meguri.core.rag.RagProvider;
import java.util.List;
import java.util.Map;

/** Converts the legacy Lore gateway into typed, source-local ranked evidence. */
public final class LoreRetrievalProviderAdapter implements RetrievalProvider {
    private final RagProvider gateway;
    private final WeightedRrfFusion fusion;

    public LoreRetrievalProviderAdapter(RagProvider gateway) {
        this(gateway, new WeightedRrfFusion());
    }

    LoreRetrievalProviderAdapter(RagProvider gateway, WeightedRrfFusion fusion) {
        this.gateway = java.util.Objects.requireNonNull(gateway);
        this.fusion = java.util.Objects.requireNonNull(fusion);
    }

    @Override
    public List<RetrievalItem> retrieve(String query, int limit, RetrievalContext context) {
        if (limit <= 0 || context.runtimeState() == null) return List.of();
        List<String> rows = gateway.search(query, context.runtimeState(), limit);
        List<RetrievalItem> keyword = java.util.stream.IntStream.range(0, rows.size())
                .mapToObj(index -> LegacyRetrievalItems.item(
                        SourceType.LORE, rows.get(index), index + 1, 0.85,
                        LegacyRetrievalItems.version(context), context))
                .toList();
        return fusion.fuse(Map.of(RankSignal.KEYWORD, keyword)).stream().limit(limit).toList();
    }
}

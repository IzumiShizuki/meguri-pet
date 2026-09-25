package com.meguri.core.retrieval;

import com.meguri.core.websearch.WebSearchGateway;
import com.meguri.core.websearch.WebSearchRecall;
import com.meguri.core.websearch.WebSearchResult;

import java.util.ArrayList;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Compatibility adapter that keeps existing bounded search gateways inside the typed Web lane. */
public final class WebSearchRetrievalProviderAdapter implements RetrievalProvider {
    private final WebSearchGateway gateway;

    public WebSearchRetrievalProviderAdapter(WebSearchGateway gateway) {
        this.gateway = java.util.Objects.requireNonNull(gateway, "gateway");
    }

    @Override
    public List<RetrievalItem> retrieve(String query, int limit, RetrievalContext context) {
        if (limit <= 0 || context.turnRequest() == null
                || !gateway.shouldSearch(context.turnRequest().getMessage())) {
            return List.of();
        }
        WebSearchRecall recall = gateway.search(query, limit).block(context.remaining());
        if (recall == null || !"ok".equalsIgnoreCase(recall.status())) return List.of();
        ArrayList<RetrievalItem> items = new ArrayList<>();
        for (int index = 0; index < Math.min(limit, recall.results().size()); index++) {
            WebSearchResult result = recall.results().get(index);
            String content = result.asContextLine();
            String sourceId = digest(content);
            items.add(new RetrievalItem(
                    SourceType.WEB, sourceId, content,
                    RetrievalCitation.single(result.url(), result.title(), "live", sourceId,
                            null, null),
                    0.35, new RankTrace(java.util.Map.of(RankSignal.STRUCTURED, index + 1), 0),
                    LegacyRetrievalItems.estimateTokens(content), context.validAt(), null,
                    List.of(), List.of(), context.traceId()));
        }
        return List.copyOf(items);
    }

    @Override
    public RetrievalProviderResult retrieveWithDiagnostics(
            String query, int limit, RetrievalContext context) {
        try {
            return RetrievalProviderResult.success(retrieve(query, limit, context));
        } catch (RuntimeException error) {
            return new RetrievalProviderResult(List.of(), List.of("web_unavailable"));
        }
    }

    private static String digest(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}

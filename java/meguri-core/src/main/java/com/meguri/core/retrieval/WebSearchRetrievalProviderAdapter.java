package com.meguri.core.retrieval;

import com.meguri.core.websearch.WebSearchGateway;
import com.meguri.core.websearch.WebSearchRecall;
import com.meguri.core.websearch.WebSearchResult;
import com.meguri.core.websearch.WebSearchPolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
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
        return retrieveWithDiagnostics(query, limit, context).items();
    }

    @Override
    public RetrievalProviderResult retrieveWithDiagnostics(
            String query, int limit, RetrievalContext context) {
        try {
            if (limit <= 0) return RetrievalProviderResult.success(List.of());
            if (context.turnRequest() == null) {
                return new RetrievalProviderResult(List.of(), List.of("web_policy_context_missing"));
            }
            if (!gateway.shouldSearch(context.turnRequest().getMessage())) {
                return new RetrievalProviderResult(List.of(), List.of("web_policy_skipped"));
            }
            String querySource = query == null || query.isBlank()
                    ? context.turnRequest().getMessage() : query;
            WebSearchRecall recall = gateway.search(
                    WebSearchPolicy.queryFor(querySource), limit).block(context.remaining());
            if (recall == null) {
                return new RetrievalProviderResult(List.of(), List.of("web_search_unavailable"));
            }
            if ("disabled".equalsIgnoreCase(recall.status())) {
                return new RetrievalProviderResult(List.of(), List.of("web_search_disabled"));
            }
            if ("empty".equalsIgnoreCase(recall.status())) {
                return new RetrievalProviderResult(List.of(), List.of("web_search_empty"));
            }
            if (!"ok".equalsIgnoreCase(recall.status())) {
                return new RetrievalProviderResult(List.of(), List.of("web_search_unavailable"));
            }
            if (recall.results().isEmpty()) {
                return new RetrievalProviderResult(List.of(), List.of("web_search_empty"));
            }
            return new RetrievalProviderResult(items(recall, limit, context), List.of());
        } catch (RuntimeException error) {
            return new RetrievalProviderResult(List.of(), List.of(
                    causedByTimeout(error) ? "web_search_timeout" : "web_search_unavailable"));
        }
    }

    private List<RetrievalItem> items(
            WebSearchRecall recall, int limit, RetrievalContext context) {
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

    private static boolean causedByTimeout(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof TimeoutException) return true;
        }
        return false;
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

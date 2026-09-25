package com.meguri.core.websearch;

import com.meguri.core.retrieval.RankSignal;
import com.meguri.core.retrieval.RankTrace;
import com.meguri.core.retrieval.RetrievalCitation;
import com.meguri.core.retrieval.RetrievalContext;
import com.meguri.core.retrieval.RetrievalItem;
import com.meguri.core.retrieval.RetrievalProvider;
import com.meguri.core.retrieval.RetrievalProviderResult;
import com.meguri.core.retrieval.SourceType;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** SLOW Web pipeline: Search -> policy/time filter -> Extract -> canonical dedupe -> rerank. */
public final class WebRetrievalProvider implements RetrievalProvider {
    private final WebSearchGateway search;
    private final WebContentExtractor extractor;
    private final SafeWebUrlPolicy urls;
    private final Duration maxAge;

    public WebRetrievalProvider(
            WebSearchGateway search, WebContentExtractor extractor,
            Set<String> allowlist, Duration maxAge) {
        this.search = java.util.Objects.requireNonNull(search);
        this.extractor = extractor == null ? WebContentExtractor.snippets() : extractor;
        this.urls = new SafeWebUrlPolicy(allowlist);
        this.maxAge = maxAge == null || maxAge.isNegative() || maxAge.isZero()
                ? Duration.ofDays(3650) : maxAge;
    }

    @Override
    public List<RetrievalItem> retrieve(String query, int limit, RetrievalContext context) {
        return retrieveWithDiagnostics(query, limit, context).items();
    }

    @Override
    public RetrievalProviderResult retrieveWithDiagnostics(
            String query, int limit, RetrievalContext context) {
        if (limit <= 0) return RetrievalProviderResult.success(List.of());
        LinkedHashSet<String> degradations = new LinkedHashSet<>();
        try {
            WebSearchRecall recall = search.search(query, Math.min(12, Math.max(limit * 3, limit)))
                    .block(context.remaining());
            if (recall == null || !"ok".equalsIgnoreCase(recall.status())) {
                return new RetrievalProviderResult(List.of(), List.of("web_search_unavailable"));
            }
            List<Candidate> extracted = extract(query, recall.results(), context, degradations);
            extracted.sort(Comparator.comparingDouble(Candidate::score).reversed()
                    .thenComparing(Candidate::canonicalUrl));
            List<RetrievalItem> items = new ArrayList<>();
            for (int index = 0; index < Math.min(limit, extracted.size()); index++) {
                Candidate candidate = extracted.get(index);
                String content = candidate.page().content();
                items.add(new RetrievalItem(
                        SourceType.WEB, candidate.canonicalUrl(), content,
                        RetrievalCitation.single(candidate.canonicalUrl(), candidate.page().title(),
                                candidate.page().fetchedAt().toString(), "", null, null),
                        0.25,
                        new RankTrace(Map.of(
                                RankSignal.STRUCTURED, candidate.searchRank(),
                                RankSignal.RERANK, index + 1), 0,
                                Map.of(RankSignal.RERANK, candidate.score())),
                        estimateTokens(content), context.validAt(), null, List.of(),
                        List.of(), context.traceId()));
            }
            return new RetrievalProviderResult(items, List.copyOf(degradations));
        } catch (RuntimeException error) {
            return new RetrievalProviderResult(List.of(), List.of("web_pipeline_unavailable"));
        }
    }

    private List<Candidate> extract(
            String query, List<WebSearchResult> results, RetrievalContext context,
            Set<String> degradations) {
        List<Candidate> candidates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Set<String> extractedUrls = new HashSet<>();
        for (int index = 0; index < results.size() && Instant.now().isBefore(context.deadline()); index++) {
            WebSearchResult result = results.get(index);
            if (result.publishedAt() != null
                    && result.publishedAt().isBefore(context.validAt().minus(maxAge))) continue;
            String canonical = urls.canonicalize(result.url());
            if (canonical.isBlank() || !seen.add(canonical)) continue;
            try {
                ExtractedWebPage page = extractor.extract(
                                new WebSearchResult(result.title(), canonical, result.snippet(), result.publishedAt()),
                                context.deadline())
                        .block(context.remaining());
                if (page == null || page.content().isBlank()) continue;
                String finalCanonical = urls.canonicalize(page.canonicalUrl());
                if (finalCanonical.isBlank() || !extractedUrls.add(finalCanonical)) continue;
                candidates.add(new Candidate(finalCanonical, page, index + 1,
                        lexicalScore(query, page.title() + " " + page.content())));
            } catch (RuntimeException error) {
                degradations.add("web_extract_partial_failure");
            }
        }
        return candidates;
    }

    private static double lexicalScore(String query, String content) {
        Set<String> terms = terms(query);
        if (terms.isEmpty()) return 0;
        Set<String> document = terms(content);
        long matched = terms.stream().filter(document::contains).count();
        return (double) matched / terms.size();
    }

    private static Set<String> terms(String value) {
        if (value == null) return Set.of();
        return java.util.Arrays.stream(value.toLowerCase(java.util.Locale.ROOT)
                        .split("[^\\p{L}\\p{N}]+"))
                .filter(term -> !term.isBlank()).collect(java.util.stream.Collectors.toSet());
    }

    private static int estimateTokens(String value) {
        return Math.max(1, (int) Math.ceil(value.codePointCount(0, value.length()) / 3.0));
    }

    private record Candidate(
            String canonicalUrl, ExtractedWebPage page, int searchRank, double score) {}
}

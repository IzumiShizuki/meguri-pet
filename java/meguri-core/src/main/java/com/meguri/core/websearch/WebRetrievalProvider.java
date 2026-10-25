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
import java.util.concurrent.TimeoutException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

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
        if (context.turnRequest() != null
                && !search.shouldSearch(context.turnRequest().getMessage())) {
            return new RetrievalProviderResult(List.of(), List.of("web_policy_skipped"));
        }
        LinkedHashSet<String> degradations = new LinkedHashSet<>();
        try {
            String querySource = query == null || query.isBlank()
                    ? context.turnRequest() == null ? "" : context.turnRequest().getMessage()
                    : query;
            String searchQuery = WebSearchPolicy.queryFor(querySource);
            WebSearchRecall recall = search.search(searchQuery, Math.min(12, Math.max(limit * 3, limit)))
                    .block(context.remaining());
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
            if (items.isEmpty() && degradations.isEmpty()) {
                degradations.add("web_results_unusable");
            }
            return new RetrievalProviderResult(items, List.copyOf(degradations));
        } catch (RuntimeException error) {
            return new RetrievalProviderResult(List.of(), List.of(
                    causedByTimeout(error) ? "web_search_timeout" : "web_search_unavailable"));
        }
    }

    private static boolean causedByTimeout(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof TimeoutException) return true;
        }
        return false;
    }

    private List<Candidate> extract(
            String query, List<WebSearchResult> results, RetrievalContext context,
            Set<String> degradations) {
        List<Candidate> candidates = new ArrayList<>();
        List<Candidate> snippets = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Set<String> extractedUrls = new HashSet<>();
        Instant extractionDeadline = context.deadline().minusMillis(150);
        for (int index = 0; index < results.size(); index++) {
            WebSearchResult result = results.get(index);
            if (result.publishedAt() != null
                    && result.publishedAt().isBefore(context.validAt().minus(maxAge))) continue;
            String canonical = urls.canonicalize(result.url());
            if (canonical.isBlank() || !seen.add(canonical)) continue;
            if (!result.snippet().isBlank()) {
                String snippet = result.snippet().substring(0, Math.min(1200, result.snippet().length()));
                ExtractedWebPage summary = new ExtractedWebPage(canonical, result.title(),
                        "搜索结果摘要（未读取网页正文）：\n" + snippet, Instant.now());
                snippets.add(new Candidate(canonical, summary, index + 1,
                        lexicalScore(query, result.title() + " " + snippet)));
            }
            Instant now = Instant.now();
            if (!now.isBefore(extractionDeadline)) {
                degradations.add("web_extract_partial_failure");
                continue;
            }
            Instant pageDeadline = now.plusSeconds(1).isBefore(extractionDeadline)
                    ? now.plusSeconds(1) : extractionDeadline;
            Duration pageBudget = Duration.between(now, pageDeadline);
            try {
                ExtractedWebPage page = Mono.defer(() -> extractor.extract(
                                new WebSearchResult(result.title(), canonical, result.snippet(), result.publishedAt()),
                                pageDeadline))
                        .subscribeOn(Schedulers.boundedElastic())
                        .timeout(pageBudget)
                        .block(context.remaining());
                if (page == null || page.content().isBlank()) {
                    degradations.add("web_extract_partial_failure");
                    continue;
                }
                String finalCanonical = urls.canonicalize(page.canonicalUrl());
                if (finalCanonical.isBlank() || !extractedUrls.add(finalCanonical)) continue;
                candidates.add(new Candidate(finalCanonical, page, index + 1,
                        lexicalScore(query, page.title() + " " + page.content())));
            } catch (RuntimeException error) {
                degradations.add("web_extract_partial_failure");
            }
        }
        if (candidates.isEmpty() && !snippets.isEmpty()) {
            degradations.add("web_search_snippet_fallback");
            return snippets;
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

package com.meguri.core.websearch;

import com.meguri.core.retrieval.RankSignal;
import com.meguri.core.retrieval.RetrievalContext;
import com.meguri.core.retrieval.RetrievalProviderResult;
import com.meguri.core.retrieval.SourceType;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class WebRetrievalProviderTest {
    @Test
    void filtersUnsafeStaleAndDuplicateUrlsThenExtractsAndReranks() {
        WebSearchGateway search = (query, limit) -> Mono.just(new WebSearchRecall(
                "ok", "test", List.of(
                new WebSearchResult("unsafe", "https://127.0.0.1/admin", "x"),
                new WebSearchResult("old", "https://allowed.test/old", "x",
                        Instant.now().minus(Duration.ofDays(40))),
                new WebSearchResult("weak", "https://allowed.test/page?utm_source=x&b=2", "other"),
                new WebSearchResult("duplicate", "https://allowed.test/page?b=2", "same"),
                new WebSearchResult("best", "https://allowed.test/best", "meguri runtime"))));
        AtomicInteger extracts = new AtomicInteger();
        WebContentExtractor extractor = (result, deadline) -> {
            extracts.incrementAndGet();
            return Mono.just(new ExtractedWebPage(
                    result.url(), result.title(), result.snippet(), Instant.now()));
        };
        WebRetrievalProvider provider = new WebRetrievalProvider(
                search, extractor, Set.of("allowed.test"), Duration.ofDays(30));

        RetrievalProviderResult result = provider.retrieveWithDiagnostics(
                "meguri runtime", 2, context());

        assertThat(extracts).hasValue(2);
        assertThat(result.degradations()).isEmpty();
        assertThat(result.items()).hasSize(2);
        assertThat(result.items().getFirst().sourceId()).isEqualTo("https://allowed.test/best");
        assertThat(result.items()).allSatisfy(item -> {
            assertThat(item.sourceType()).isEqualTo(SourceType.WEB);
            assertThat(item.trust()).isEqualTo(0.25);
            assertThat(item.citation().isEmpty()).isFalse();
            assertThat(item.rankTrace().ranks()).containsKey(RankSignal.RERANK);
            assertThat(item.rankTrace().scores()).containsKey(RankSignal.RERANK);
        });
    }

    @Test
    void extractionFailureIsolatedAndReportedWithoutDiscardingOtherPages() {
        WebSearchGateway search = (query, limit) -> Mono.just(new WebSearchRecall(
                "ok", "test", List.of(
                new WebSearchResult("bad", "https://allowed.test/bad", "bad"),
                new WebSearchResult("good", "https://allowed.test/good", "good"))));
        WebContentExtractor extractor = (result, deadline) -> result.url().endsWith("/bad")
                ? Mono.error(new IllegalStateException("broken"))
                : Mono.just(new ExtractedWebPage(result.url(), result.title(),
                        result.snippet(), Instant.now()));
        WebRetrievalProvider provider = new WebRetrievalProvider(
                search, extractor, Set.of("allowed.test"), Duration.ofDays(30));

        RetrievalProviderResult result = provider.retrieveWithDiagnostics("good", 2, context());

        assertThat(result.items()).singleElement()
                .satisfies(item -> assertThat(item.sourceId()).endsWith("/good"));
        assertThat(result.degradations()).containsExactly("web_extract_partial_failure");
    }

    private static RetrievalContext context() {
        return new RetrievalContext("user", Set.of("web:read"), "snapshot", 1,
                Instant.now(), Instant.now().plusSeconds(2), "trace");
    }
}

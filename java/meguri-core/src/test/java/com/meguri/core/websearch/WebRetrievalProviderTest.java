package com.meguri.core.websearch;

import com.meguri.core.retrieval.RankSignal;
import com.meguri.core.retrieval.RetrievalContext;
import com.meguri.core.retrieval.RetrievalProviderResult;
import com.meguri.core.retrieval.SourceType;
import com.meguri.core.dto.TurnRequest;
import com.meguri.core.harness.retrieval.RetrievalMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class WebRetrievalProviderTest {
    @Test
    void soleSourceHasABoundedTlsBudgetInsteadOfTheMultiResultOneSecondCap() {
        var allowedBudget = new AtomicReference<Duration>();
        WebContentExtractor extractor = (result, deadline) -> {
            allowedBudget.set(Duration.between(Instant.now(), deadline));
            return Mono.just(new ExtractedWebPage(result.url(), result.title(), "source", Instant.now()));
        };
        var provider = new WebRetrievalProvider((query, limit) -> Mono.just(WebSearchRecall.disabled()),
                extractor, Set.of("allowed.test"), null);
        var context = new RetrievalContext("user", Set.of("web:read"), "snapshot", 1,
                Instant.now(), Instant.now().plusSeconds(6), "trace");
        assertThat(provider.retrieve("按链接核实 https://allowed.test/source", 1, context)).hasSize(1);
        assertThat(allowedBudget.get()).isGreaterThan(Duration.ofSeconds(2)).isLessThanOrEqualTo(Duration.ofSeconds(3));
    }
    @Test
    void explicitlyRequestedPublicLinkUsesExtractionWithoutSearchingItsWrapper() {
        AtomicInteger searches = new AtomicInteger();
        WebSearchGateway search = (query, limit) -> {
            searches.incrementAndGet();
            return Mono.just(new WebSearchRecall("empty", "test", List.of()));
        };
        WebContentExtractor extractor = (result, deadline) -> Mono.just(
                new ExtractedWebPage(result.url(), "HTTP semantics", "retrieved source body", Instant.now()));
        var provider = new WebRetrievalProvider(search, extractor, Set.of("allowed.test"), null);
        var result = provider.retrieveWithDiagnostics("按链接核实 https://allowed.test/rfc9110。", 2, context());
        assertThat(searches).hasValue(0);
        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.sourceId()).isEqualTo("https://allowed.test/rfc9110");
            assertThat(item.content()).isEqualTo("retrieved source body");
        });
    }

    @Test
    void genericProductPageDoesNotSatisfySpecificTechnicalSubject() {
        assertThat(WebSearchRelevance.relevant("Java 21 virtual threads", new WebSearchResult(
                "Java downloads", "https://allowed.test/java", "Download Java 21 now"))).isFalse();
    }

    @Test
    void requestedLinksCannotBypassDisabledNetworkingOrLeakRejectedPrivateUrlsToSearch() {
        AtomicInteger calls = new AtomicInteger();
        WebContentExtractor extractor = (result, deadline) -> {
            calls.incrementAndGet();
            return Mono.empty();
        };
        var disabled = new WebRetrievalProvider(new NoopWebSearchGateway(), extractor, Set.of(), null);
        assertThat(disabled.retrieveWithDiagnostics("按链接核实 https://example.com/", 1, context()).items()).isEmpty();
        WebSearchGateway search = (query, limit) -> {
            calls.incrementAndGet();
            return Mono.just(new WebSearchRecall("empty", "test", List.of()));
        };
        var enabled = new WebRetrievalProvider(search, extractor, Set.of(), null);
        assertThat(enabled.retrieveWithDiagnostics("按链接核实 http://127.0.0.1/admin", 1, context()).items()).isEmpty();
        assertThat(calls).hasValue(0);
    }

    @ParameterizedTest
    @CsvSource({
            "disabled, web_search_disabled",
            "unavailable, web_search_unavailable",
            "empty, web_search_empty",
            "ok, web_search_empty"
    })
    void preservesDisabledUnavailableAndEmptySearchOutcomes(
            String status, String expectedDegradation) {
        WebSearchGateway search = (query, limit) -> Mono.just(
                new WebSearchRecall(status, "test", List.of()));
        WebRetrievalProvider provider = new WebRetrievalProvider(
                search, WebContentExtractor.snippets(), Set.of(), Duration.ofDays(30));

        RetrievalProviderResult result = provider.retrieveWithDiagnostics("current fact", 3, context());

        assertThat(result.items()).isEmpty();
        assertThat(result.degradations()).containsExactly(expectedDegradation);
    }

    @Test
    void doesNotExposeProviderExceptionTextInDiagnostics() {
        WebSearchGateway search = (query, limit) -> Mono.error(
                new IllegalStateException("Authorization: Bearer secret-token"));
        WebRetrievalProvider provider = new WebRetrievalProvider(
                search, WebContentExtractor.snippets(), Set.of(), Duration.ofDays(30));

        RetrievalProviderResult result = provider.retrieveWithDiagnostics("current fact", 3, context());

        assertThat(result.items()).isEmpty();
        assertThat(result.degradations()).containsExactly("web_search_unavailable")
                .doesNotContain("Authorization: Bearer secret-token");
    }

    @Test
    void skipsSearchWhenTurnMessageDoesNotPassWebPolicy() {
        AtomicInteger searches = new AtomicInteger();
        WebSearchGateway search = (query, limit) -> {
            searches.incrementAndGet();
            return Mono.just(new WebSearchRecall("ok", "test", List.of()));
        };
        WebRetrievalProvider provider = new WebRetrievalProvider(
                search, WebContentExtractor.snippets(), Set.of(), Duration.ofDays(30));
        TurnRequest request = new TurnRequest("user", "website", "session", "陪我聊聊天",
                RetrievalMode.SLOW);
        RetrievalContext context = new RetrievalContext(
                "user", Set.of("web:read"), "snapshot", 1,
                Instant.now(), Instant.now().plusSeconds(2), "trace",
                "meguri-local", null, request);

        RetrievalProviderResult result = provider.retrieveWithDiagnostics("陪我聊聊天", 3, context);

        assertThat(searches).hasValue(0);
        assertThat(result.items()).isEmpty();
        assertThat(result.degradations()).containsExactly("web_policy_skipped");
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "帮我查看一下今天Codex是否更新了, Codex 更新 今天",
            "今天帮我联网搜索Codex更新, Codex 更新 今天",
            "帮我看看今天Codex有没有更新吗？, Codex 更新 今天",
            "联网查询Codex版本, Codex 版本",
            "联网查Codex版本, Codex 版本",
            "网上查询Codex版本, Codex 版本"
    })
    void productionProviderSendsNormalizedCurrentInformationQueryToGateway(
            String message, String expectedQuery) {
        AtomicReference<String> searchedQuery = new AtomicReference<>();
        WebSearchGateway search = (query, limit) -> {
            searchedQuery.set(query);
            return Mono.just(new WebSearchRecall("empty", "test", List.of()));
        };
        WebRetrievalProvider provider = new WebRetrievalProvider(
                search, WebContentExtractor.snippets(), Set.of(), Duration.ofDays(30));
        TurnRequest request = new TurnRequest("user", "website", "session", message,
                RetrievalMode.SLOW);
        RetrievalContext context = new RetrievalContext(
                "user", Set.of("web:read"), "snapshot", 1,
                Instant.now(), Instant.now().plusSeconds(2), "trace",
                "meguri-local", null, request);

        RetrievalProviderResult result = provider.retrieveWithDiagnostics(message, 3, context);

        assertThat(result.degradations()).containsExactly("web_search_empty");
        assertThat(searchedQuery).hasValue(expectedQuery);
    }

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

        assertThat(extracts).hasValue(1);
        assertThat(result.degradations()).contains("web_results_irrelevant");
        assertThat(result.items()).hasSize(1);
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
                new WebSearchResult("bad", "https://allowed.test/bad", "good topic, bad page"),
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

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void retainsLabeledSearchSnippetsWhenEveryPageBodyIsUnavailable(boolean fails) {
        WebSearchGateway search = (query, limit) -> Mono.just(new WebSearchRecall(
                "ok", "test", List.of(
                new WebSearchResult("unsafe", "https://127.0.0.1/admin", "private"),
                new WebSearchResult("old", "https://allowed.test/old", "stale",
                        Instant.now().minus(Duration.ofDays(40))),
                new WebSearchResult("current", "https://allowed.test/current", "release summary"))));
        WebContentExtractor extractor = (result, deadline) -> fails
                ? Mono.error(new IllegalStateException("Authorization: Bearer secret")) : Mono.empty();
        WebRetrievalProvider provider = new WebRetrievalProvider(
                search, extractor, Set.of("allowed.test"), Duration.ofDays(30));

        RetrievalProviderResult result = provider.retrieveWithDiagnostics("release", 3, context());

        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.sourceId()).isEqualTo("https://allowed.test/current");
            assertThat(item.content()).contains("搜索结果摘要", "未读取网页正文", "release summary")
                    .doesNotContain("secret", "private", "stale");
        });
        assertThat(result.degradations()).contains("web_search_snippet_fallback");
    }

    @Test
    void stalledPageLeavesTimeForAnotherReadableResult() {
        WebSearchGateway search = (query, limit) -> Mono.just(new WebSearchRecall(
                "ok", "test", List.of(
                new WebSearchResult("stalled", "https://allowed.test/stalled", "release search-only"),
                new WebSearchResult("readable", "https://allowed.test/readable", "release search-only"))));
        AtomicInteger cancelled = new AtomicInteger();
        WebContentExtractor extractor = (result, deadline) -> result.url().endsWith("/stalled")
                ? Mono.<ExtractedWebPage>never().doOnCancel(cancelled::incrementAndGet)
                : Mono.just(new ExtractedWebPage(result.url(), result.title(), "verified body", Instant.now()));
        WebRetrievalProvider provider = new WebRetrievalProvider(
                search, extractor, Set.of("allowed.test"), Duration.ofDays(30));

        RetrievalProviderResult result = provider.retrieveWithDiagnostics("release", 3, context());

        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.sourceId()).endsWith("/readable");
            assertThat(item.content()).isEqualTo("verified body");
        });
        assertThat(cancelled).hasValue(1);
        assertThat(result.degradations()).contains("web_extract_partial_failure")
                .doesNotContain("web_search_snippet_fallback");
    }

    @Test
    void rejectsUnrelatedResultsAndRetriesOnlyOnceWithTheSameSubject() {
        var queries = new java.util.ArrayList<String>();
        WebSearchGateway search = (query, limit) -> {
            queries.add(query);
            return Mono.just(new WebSearchRecall("ok", "test", List.of(new WebSearchResult(
                    "Minecraft", "https://allowed.test/game", "unrelated game"))));
        };
        var provider = new WebRetrievalProvider(search, WebContentExtractor.snippets(), Set.of(), null);
        var result = provider.retrieveWithDiagnostics("RFC 9110", 3, context());
        assertThat(queries).containsExactly("RFC 9110", "\"rfc\" \"9110\"");
        assertThat(result.items()).isEmpty();
        assertThat(result.degradations()).contains("web_results_irrelevant", "web_search_retried");
    }

    @Test
    void boundedRecoveryCanReturnRelevantEvidence() {
        AtomicInteger calls = new AtomicInteger();
        WebSearchGateway search = (query, limit) -> Mono.just(new WebSearchRecall("ok", "test",
                calls.incrementAndGet() == 1
                        ? List.of(new WebSearchResult("QQ", "https://allowed.test/qq", "chat"))
                        : List.of(new WebSearchResult("HTTP semantics RFC 9110", "https://allowed.test/rfc9110", "RFC 9110 HTTP"))));
        var provider = new WebRetrievalProvider(search, WebContentExtractor.snippets(), Set.of(), null);
        var result = provider.retrieveWithDiagnostics("RFC 9110", 3, context());
        assertThat(calls).hasValue(2);
        assertThat(result.items()).singleElement().satisfies(item ->
                assertThat(item.sourceId()).endsWith("/rfc9110"));
    }
}

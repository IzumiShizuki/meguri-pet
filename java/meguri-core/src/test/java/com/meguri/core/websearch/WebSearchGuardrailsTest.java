package com.meguri.core.websearch;

import com.meguri.core.retrieval.RetrievalContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

class WebSearchGuardrailsTest {
    @Test
    void recordedWrongTopicRssResultsRemainRejected() throws Exception {
        try (var input = getClass().getResourceAsStream("/websearch/wrong-topic-rss.json")) {
            var captures = new com.fasterxml.jackson.databind.ObjectMapper().readTree(input);
            int checked = 0;
            for (var capture : captures) {
                for (var result : capture.path("results")) {
                    assertThat(WebSearchRelevance.relevant(capture.path("query").asText(), new WebSearchResult(
                            result.path("title").asText(), result.path("url").asText(), result.path("snippet").asText())))
                            .as("recorded capture %s", capture.path("id").asText()).isFalse();
                    checked++;
                }
            }
            assertThat(checked).isEqualTo(40);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"RFC 9110", "RFC9110", "RFC-9110", "site:rfc-editor.org RFC 9110"})
    void rfcNumbersMustHaveExactDigitBoundaries(String query) {
        for (String wrong : List.of("19110", "91100", "191100")) {
            assertThat(WebSearchRelevance.relevant(query, new WebSearchResult("RFC " + wrong,
                    "https://www.rfc-editor.org/rfc/rfc" + wrong + ".html", "HTTP semantics"))).isFalse();
        }
        assertThat(WebSearchRelevance.relevant(query, new WebSearchResult("HTTP Semantics",
                "https://www.rfc-editor.org/rfc/rfc9110.html", "RFC 9110"))).isTrue();
        assertThat(WebSearchRelevance.relevant(query, new WebSearchResult("RFC index",
                "https://www.rfc-editor.org/index", "9110"))).isTrue();
    }

    @Test
    void siteConstrainsTheHostAndSurvivesOneBoundedRetry() {
        List<String> queries = new ArrayList<>();
        WebSearchGateway gateway = (query, limit) -> {
            queries.add(query);
            return Mono.just(new WebSearchRecall("ok", "fixture", List.of(
                    queries.size() == 1 ? hit("https://rfc-editor.org.attacker.test/rfc9110")
                            : hit("https://www.rfc-editor.org/rfc/rfc9110.html"))));
        };
        var result = provider(gateway).retrieveWithDiagnostics("site:rfc-editor.org RFC 9110", 1, context());
        assertThat(queries).containsExactly("site:rfc-editor.org RFC 9110", "site:rfc-editor.org \"rfc\" \"9110\"");
        assertThat(result.items()).singleElement().satisfies(item ->
                assertThat(item.sourceId()).isEqualTo("https://www.rfc-editor.org/rfc/rfc9110.html"));
        assertThat(result.degradations()).contains("web_results_scope_rejected", "web_search_retried");
    }

    @Test
    void normalizationCannotDiscardSiteInAnOutputInstruction() {
        List<String> queries = new ArrayList<>();
        var provider = provider((query, limit) -> {
            queries.add(query);
            return Mono.just(new WebSearchRecall("ok", "fixture", List.of(hit("https://evil.test/rfc9110"))));
        });
        var result = provider.retrieveWithDiagnostics("搜索 RFC 9110，并提供来源 site:rfc-editor.org", 1, context());
        assertThat(result.items()).isEmpty();
        assertThat(queries).containsExactly("site:rfc-editor.org RFC 9110", "site:rfc-editor.org \"rfc\" \"9110\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://evil.test/rfc-editor.org/rfc9110", "https://evil.test/?site=rfc-editor.org&rfc=9110",
            "https://notrfc-editor.org/rfc9110", "https://rfc-editor.org.evil.test/rfc9110"})
    void titleSnippetPathAndSuffixCannotSpoofTheRequestedSite(String url) {
        AtomicInteger extracts = new AtomicInteger();
        var provider = new WebRetrievalProvider((query, limit) -> Mono.just(new WebSearchRecall("ok", "fixture", List.of(hit(url)))),
                (result, deadline) -> { extracts.incrementAndGet(); return Mono.empty(); }, Set.of(), null);
        var result = provider.retrieveWithDiagnostics("RFC 9110 site:rfc-editor.org", 1, context());
        assertThat(result.items()).isEmpty();
        assertThat(extracts).hasValue(0);
        assertThat(result.degradations()).contains("web_results_scope_rejected");
    }

    @Test
    void redirectOutsideTheRequestedSiteCannotBecomeBodyEvidence() {
        var provider = new WebRetrievalProvider((query, limit) -> Mono.just(new WebSearchRecall("ok", "fixture",
                List.of(hit("https://www.rfc-editor.org/rfc/rfc9110.html")))),
                (result, deadline) -> Mono.just(new ExtractedWebPage("https://evil.test/rfc9110", "RFC 9110",
                        "OUT OF SCOPE BODY", Instant.now())), Set.of(), null);
        var result = provider.retrieveWithDiagnostics("site:rfc-editor.org RFC 9110", 1, context());
        assertThat(result.degradations()).contains("web_extract_scope_rejected", "web_search_snippet_fallback");
        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.sourceId()).startsWith("https://www.rfc-editor.org/");
            assertThat(item.content()).doesNotContain("OUT OF SCOPE BODY");
        });
    }

    @Test
    void explicitLinkCannotEscapeScopeOrTriggerASearchRetry() {
        AtomicInteger calls = new AtomicInteger();
        var provider = new WebRetrievalProvider((query, limit) -> { calls.incrementAndGet(); return Mono.empty(); },
                (result, deadline) -> { calls.incrementAndGet(); return Mono.empty(); }, Set.of(), null);
        var result = provider.retrieveWithDiagnostics("search site:rfc-editor.org https://evil.test/rfc9110", 1, context());
        assertThat(result.items()).isEmpty();
        assertThat(result.degradations()).contains("web_results_scope_rejected");
        assertThat(calls).hasValue(0);
    }

    @Test
    void malformedSiteFailsClosedBeforeAnySearch() {
        AtomicInteger calls = new AtomicInteger();
        var provider = provider((query, limit) -> { calls.incrementAndGet(); return Mono.empty(); });
        for (String query : List.of("RFC 9110 site:", "RFC 9110 site:example.org/path", "site:*.org RFC 9110")) {
            assertThat(provider.retrieveWithDiagnostics(query, 1, context()).degradations()).contains("web_query_scope_invalid");
        }
        assertThat(calls).hasValue(0);
    }

    @Test
    void quotedOperatorsStayLiteralAndMultipleDomainsStayConjunctive() {
        assertThat(WebSearchScope.parse("\"site:rfc-editor.org\" RFC 9110").domains()).isEmpty();
        assertThat(WebSearchScope.parse("`site:rfc-editor.org` RFC 9110").domains()).isEmpty();
        assertThat(WebSearchScope.parse("I'm looking for RFC 9110 site:rfc-editor.org").domains()).containsExactly("rfc-editor.org");
        assertThat(WebSearchScope.parse("search https://example.org/?site:rfc-editor.org").domains()).isEmpty();
        var scope = WebSearchScope.parse("SITE:RFC-EDITOR.ORG site:www.rfc-editor.org RFC 9110");
        assertThat(scope.accepts("https://www.rfc-editor.org/rfc9110")).isTrue();
        assertThat(scope.accepts("https://rfc-editor.org/rfc9110")).isFalse();
        assertThat(WebSearchScope.parse("site:example.org site:rfc-editor.org").accepts("https://example.org/")).isFalse();
    }

    @Test
    void retryHtmlIsUnavailableAndOriginalRelevanceFailureRemainsVisible() {
        AtomicInteger calls = new AtomicInteger();
        var result = provider((query, limit) -> Mono.just(calls.incrementAndGet() == 1
                ? new WebSearchRecall("ok", "fixture", List.of(new WebSearchResult("Maps", "https://example.org/maps", "Directions")))
                : WebSearchRecall.unavailable("fixture", "web_response_non_rss")))
                .retrieveWithDiagnostics("RFC 9110", 1, context());
        assertThat(result.items()).isEmpty();
        assertThat(calls).hasValue(2);
        assertThat(result.degradations()).contains("web_results_irrelevant", "web_search_retried",
                "web_retry_response_non_rss", "web_retry_unavailable");
    }

    @Test
    void siteConstraintDoesNotRelaxExistingUrlAllowlist() {
        var provider = new WebRetrievalProvider((query, limit) -> Mono.just(new WebSearchRecall("ok", "fixture",
                List.of(hit("https://rfc-editor.org/rfc9110")))), WebContentExtractor.snippets(), Set.of("example.org"), null);
        assertThat(provider.retrieveWithDiagnostics("site:rfc-editor.org RFC 9110", 1, context()).items()).isEmpty();
    }

    private static WebSearchResult hit(String url) {
        return new WebSearchResult("RFC 9110 rfc-editor.org", url, "RFC 9110 official rfc-editor.org HTTP semantics");
    }

    private static WebRetrievalProvider provider(WebSearchGateway gateway) {
        return new WebRetrievalProvider(gateway, WebContentExtractor.snippets(), Set.of(), null);
    }

    private static RetrievalContext context() {
        return new RetrievalContext("synthetic", Set.of("web:read"), "snapshot", 1,
                Instant.now(), Instant.now().plusSeconds(5), "guardrail-test");
    }
}

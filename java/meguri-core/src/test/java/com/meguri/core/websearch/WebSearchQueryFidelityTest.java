package com.meguri.core.websearch;

import java.util.stream.Stream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import com.meguri.core.retrieval.RetrievalContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSearchQueryFidelityTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("queries")
    void preservesSearchSubjectWhileRemovingPresentationRequests(String label, String input, String expected) {
        assertEquals(expected, WebSearchPolicy.queryFor(input));
    }

    static Stream<Arguments> queries() {
        return Stream.of(
                Arguments.of("Chinese topic contains summary", "请搜索 文本总结算法 的局限", "文本总结算法 的局限"),
                Arguments.of("quoted topic contains summary", "请搜索 \"文本总结方法\" 的论文，并给出来源。", "\"文本总结方法\" 的论文"),
                Arguments.of("quoted output words are data", "请搜索 \"RFC 9110, 总结\" 的资料，并说明来源。", "\"RFC 9110, 总结\" 的资料"),
                Arguments.of("English for wrapper", "Could you search for Kotlin coroutine cancellation, and provide two official sources?", "Kotlin coroutine cancellation"),
                Arguments.of("English web wrapper", "Please search the web for Rust async cancellation and summarize the findings.", "Rust async cancellation"),
                Arguments.of("English explanatory preamble", "I need a public source for a technical note. Could you search for Python TaskGroup exception propagation, then provide official links?", "Python TaskGroup exception propagation"),
                Arguments.of("freshness prefix is part of identifier", "currentColor CSS specification", "currentColor CSS specification"),
                Arguments.of("freshness prefix is part of API name", "latestVersion function documentation", "latestVersion function documentation"),
                Arguments.of("search prefix is part of word", "searchable encryption", "searchable encryption"),
                Arguments.of("comparison changes subject", "搜索 Python 内置函数，并比较 sorted 与 sort", "Python 内置函数，并比较 sorted 与 sort"),
                Arguments.of("explicit summary instruction", "请联网搜索 DeepSeek API 并总结", "DeepSeek API"),
                Arguments.of("short English subject", "search Go io.Copy", "Go io.Copy"),
                Arguments.of("search operator and punctuation", "请搜索 C++20 协程 -tutorial \"co_await\" site:en.cppreference.com，并提供链接", "C++20 协程 -tutorial \"co_await\" site:en.cppreference.com"),
                Arguments.of("comparison inside quotes", "搜索 \"并总结\" 运算符文档", "\"并总结\" 运算符文档"),
                Arguments.of("quoted mixed-script literal", "请搜索 \"RFC9110标准\" 的说明", "\"RFC9110标准\" 的说明"),
                Arguments.of("quoted spacing is literal", "Please search for \"currentColor  RFC9110标准\" and provide links.", "\"currentColor  RFC9110标准\""),
                Arguments.of("freshness qualifier retained", "请搜索最新 Rust 版本，并给出官方链接。", "Rust 版本 最新"));
    }

    @Test
    void queryLengthBoundDoesNotSplitSupplementaryCharacters() {
        String query = WebSearchPolicy.queryFor("search " + "a".repeat(255) + "😀tail");
        assertTrue(query.length() <= 256);
        assertFalse(Character.isHighSurrogate(query.charAt(query.length() - 1)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void normalizedSubjectAdmitsEvidenceButStillRejectsWrongTopics(boolean relevant) {
        var queries = new ArrayList<String>();
        WebSearchGateway gateway = (query, limit) -> {
            queries.add(query);
            return Mono.just(new WebSearchRecall("ok", "fixture", List.of(new WebSearchResult(
                    relevant ? "Kotlin coroutine cancellation" : "Unrelated garden tools",
                    "https://allowed.test/article", relevant ? "Coroutine cancellation in Kotlin" : "A shovel review"))));
        };
        var provider = new WebRetrievalProvider(gateway, WebContentExtractor.snippets(), Set.of("allowed.test"), null);
        var context = new RetrievalContext("fictional-user", Set.of("web:read"), "fixture", 1,
                Instant.now(), Instant.now().plusSeconds(3), "query-fidelity");
        var result = provider.retrieveWithDiagnostics(
                "Could you search for Kotlin coroutine cancellation, and provide two official sources?", 2, context);
        assertEquals("Kotlin coroutine cancellation", queries.getFirst());
        assertEquals(relevant ? 1 : 2, queries.size());
        assertEquals(relevant ? 1 : 0, result.items().size());
        if (!relevant) assertTrue(result.degradations().contains("web_results_irrelevant"));
    }
}

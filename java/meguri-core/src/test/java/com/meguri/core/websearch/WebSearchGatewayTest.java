package com.meguri.core.websearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSearchGatewayTest {
    @Test
    void policySearchesExplicitAndCurrentInformationRequests() {
        assertFalse(WebSearchPolicy.shouldSearch("陪我聊聊天"));
        assertTrue(WebSearchPolicy.shouldSearch("帮我联网搜索今天的新闻"));
        assertTrue(WebSearchPolicy.shouldSearch("联网查询Codex版本"));
        assertTrue(WebSearchPolicy.shouldSearch("联网查Codex版本"));
        assertTrue(WebSearchPolicy.shouldSearch("look up the latest DeepSeek API"));
        assertTrue(WebSearchPolicy.shouldSearch("今天 Tobi 有发什么新的 Codex 更新吗"));
        assertTrue(WebSearchPolicy.shouldSearch("目前 DeepSeek API 支持哪些模型"));
        assertTrue(WebSearchPolicy.shouldSearch("最近有什么 AI 新闻"));
        assertEquals("DeepSeek API", WebSearchPolicy.queryFor("请联网搜索 DeepSeek API 并总结"));
    }

    @Test
    void policyKeepsStableConversationAndCreativeRequestsOffline() {
        assertFalse(WebSearchPolicy.shouldSearch("今天吃什么"));
        assertFalse(WebSearchPolicy.shouldSearch("Codex 是什么"));
        assertFalse(WebSearchPolicy.shouldSearch("写一首关于春天的诗"));
        assertFalse(WebSearchPolicy.shouldSearch("帮我总结下面这段文字"));
        assertFalse(WebSearchPolicy.shouldSearch("不要联网搜索，只总结这段文字"));
        assertFalse(WebSearchPolicy.shouldSearch("不要联网，告诉我今天Codex是否更新"));
    }

    @Test
    void queryNormalizationRemovesOnlyLookupWrappersAndClosedUpdateQuestions() {
        assertEquals("Codex 更新", WebSearchPolicy.queryFor("帮我看看Codex有没有更新吗？"));
        assertEquals("Codex 更新", WebSearchPolicy.queryFor("Codex有更新吗？"));
        assertEquals("如何更新文档", WebSearchPolicy.queryFor("如何更新文档？"));
        assertEquals("版本是否支持图片 今天", WebSearchPolicy.queryFor("今天版本是否支持图片？"));
    }

    @Test
    void parsesBoundedInstantAnswerAndNestedTopics() {
        DuckDuckGoWebSearchGateway gateway = new DuckDuckGoWebSearchGateway(
                null, new ObjectMapper(), "https://api.duckduckgo.com", null, 2);
        WebSearchRecall recall = gateway.parse("""
                {
                  "Heading":"Meguri",
                  "AbstractText":"A virtual character runtime.",
                  "AbstractURL":"https://example.com/meguri",
                  "RelatedTopics":[
                    {"Text":"One result","FirstURL":"https://example.com/one"},
                    {"Topics":[{"Text":"Nested result","FirstURL":"https://example.com/two"}]}
                  ]
                }
                """, 2);
        assertEquals("ok", recall.status());
        assertEquals(2, recall.results().size());
        assertEquals("https://example.com/meguri", recall.results().getFirst().url());
        assertEquals(2, recall.contextLines().size());
    }

    @Test
    void disabledGatewayNeverContactsNetwork() {
        StepVerifier.create(new NoopWebSearchGateway().search("联网搜索", 5))
                .assertNext(recall -> {
                    assertEquals("disabled", recall.status());
                    assertTrue(recall.results().isEmpty());
                })
                .verifyComplete();
    }

    @Test
    void parsesBingRssResultsWithBoundedXmlHandling() {
        BingRssWebSearchGateway gateway = new BingRssWebSearchGateway(
                null, new ObjectMapper(), "https://www.bing.com/search", null, 5);
        WebSearchRecall recall = gateway.parse("""
                <rss><channel>
                  <item><title>Official docs</title><link>https://example.com/docs</link><description>Docs summary</description></item>
                  <item><title>Second</title><link>https://example.com/second</link><description>Second summary</description></item>
                </channel></rss>
                """, 1);
        assertEquals("ok", recall.status());
        assertEquals("bing-rss", recall.provider());
        assertEquals(1, recall.results().size());
        assertEquals("Official docs", recall.results().getFirst().title());
    }

    @Test
    void rejectsNonHttpsSearchEndpoint() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new DuckDuckGoWebSearchGateway(null, new ObjectMapper(), "http://example.com", null, 5));
    }
}

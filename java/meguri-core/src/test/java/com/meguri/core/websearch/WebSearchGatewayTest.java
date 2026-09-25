package com.meguri.core.websearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSearchGatewayTest {
    @Test
    void policyRequiresExplicitSearchIntent() {
        assertFalse(WebSearchPolicy.shouldSearch("陪我聊聊天"));
        assertTrue(WebSearchPolicy.shouldSearch("帮我联网搜索今天的新闻"));
        assertTrue(WebSearchPolicy.shouldSearch("look up the latest DeepSeek API"));
        assertEquals("DeepSeek API", WebSearchPolicy.queryFor("请联网搜索 DeepSeek API 并总结"));
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

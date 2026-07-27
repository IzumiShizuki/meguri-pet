package com.meguri.core.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatResponseMetadata;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PromptCacheUsageExtractorTest {

    @Test
    void extractsDeepSeekCacheHitAndMissFieldsFromRawResponse() {
        String rawBody = """
            {"usage":{"prompt_tokens":120,"completion_tokens":30,"total_tokens":150,
            "prompt_cache_hit_tokens":80,"prompt_cache_miss_tokens":40}}
            """;
        SuccessfulHttpResponse rawResponse = SuccessfulHttpResponse.builder()
            .statusCode(200)
            .headers(Map.of())
            .body(rawBody)
            .build();
        OpenAiChatResponseMetadata metadata = OpenAiChatResponseMetadata.builder()
            .modelName("deepseek-chat")
            .rawHttpResponse(rawResponse)
            .build();
        ChatResponse response = ChatResponse.builder()
            .aiMessage(AiMessage.from("{}"))
            .metadata(metadata)
            .build();

        PromptCacheUsage usage = PromptCacheUsageExtractor.extract(response, new ObjectMapper());

        assertTrue(usage.usageReported());
        assertTrue(usage.cacheReported());
        assertEquals(120L, usage.promptTokens());
        assertEquals(30L, usage.outputTokens());
        assertEquals(150L, usage.totalTokens());
        assertEquals(80L, usage.cacheHitTokens());
        assertEquals(40L, usage.cacheMissTokens());
    }
}

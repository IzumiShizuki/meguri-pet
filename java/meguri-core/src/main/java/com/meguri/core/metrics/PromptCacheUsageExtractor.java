package com.meguri.core.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatResponseMetadata;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import dev.langchain4j.model.output.TokenUsage;

/** Extracts provider-neutral and DeepSeek-specific prompt-cache usage from LangChain4j responses. */
public final class PromptCacheUsageExtractor {
    private PromptCacheUsageExtractor() {}

    public static PromptCacheUsage extract(ChatResponse response, ObjectMapper mapper) {
        if (response == null || response.metadata() == null) return PromptCacheUsage.unavailable();

        TokenUsage tokenUsage = response.metadata().tokenUsage();
        long promptTokens = nonNegative(tokenUsage == null ? null : tokenUsage.inputTokenCount());
        long outputTokens = nonNegative(tokenUsage == null ? null : tokenUsage.outputTokenCount());
        long totalTokens = nonNegative(tokenUsage == null ? null : tokenUsage.totalTokenCount());
        boolean usageReported = tokenUsage != null;
        boolean cacheReported = false;
        long cacheHitTokens = 0L;
        long cacheMissTokens = 0L;

        if (response.metadata() instanceof OpenAiChatResponseMetadata metadata) {
            OpenAiTokenUsage openAiUsage = metadata.tokenUsage();
            if (openAiUsage != null && openAiUsage.inputTokensDetails() != null
                    && openAiUsage.inputTokensDetails().cachedTokens() != null) {
                cacheHitTokens = nonNegative(openAiUsage.inputTokensDetails().cachedTokens());
                cacheMissTokens = Math.max(0L, promptTokens - cacheHitTokens);
                cacheReported = true;
            }

            JsonNode rawUsage = rawUsage(metadata, mapper);
            if (rawUsage != null) {
                promptTokens = readLong(rawUsage, "prompt_tokens", promptTokens);
                outputTokens = readLong(rawUsage, "completion_tokens", outputTokens);
                totalTokens = readLong(rawUsage, "total_tokens", totalTokens);
                usageReported = hasNumeric(rawUsage, "prompt_tokens")
                        || hasNumeric(rawUsage, "completion_tokens")
                        || usageReported;

                boolean hasDeepSeekHit = hasNumeric(rawUsage, "prompt_cache_hit_tokens");
                boolean hasDeepSeekMiss = hasNumeric(rawUsage, "prompt_cache_miss_tokens");
                if (hasDeepSeekHit || hasDeepSeekMiss) {
                    cacheHitTokens = readLong(rawUsage, "prompt_cache_hit_tokens", 0L);
                    cacheMissTokens = readLong(rawUsage, "prompt_cache_miss_tokens",
                            Math.max(0L, promptTokens - cacheHitTokens));
                    cacheReported = true;
                }
            }
        }

        if (totalTokens == 0L && usageReported) totalTokens = promptTokens + outputTokens;
        return new PromptCacheUsage(usageReported, cacheReported, promptTokens, outputTokens,
                totalTokens, cacheHitTokens, cacheMissTokens);
    }

    private static JsonNode rawUsage(OpenAiChatResponseMetadata metadata, ObjectMapper mapper) {
        if (metadata.rawHttpResponse() == null || mapper == null) return null;
        String body = metadata.rawHttpResponse().body();
        if (body == null || body.isBlank()) return null;
        try {
            JsonNode usage = mapper.readTree(body).path("usage");
            return usage.isObject() ? usage : null;
        } catch (Exception ignored) {
            // Usage telemetry is best-effort and must never turn a valid model reply into a failed turn.
            return null;
        }
    }

    private static boolean hasNumeric(JsonNode node, String field) {
        return node != null && node.has(field) && node.path(field).isNumber();
    }

    private static long readLong(JsonNode node, String field, long fallback) {
        return hasNumeric(node, field) ? Math.max(0L, node.path(field).asLong()) : fallback;
    }

    private static long nonNegative(Integer value) {
        return value == null ? 0L : Math.max(0L, value.longValue());
    }
}

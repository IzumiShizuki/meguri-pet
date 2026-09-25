package com.meguri.core.llm;

import dev.langchain4j.data.message.Content;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Selects a known multimodal-capable provider without speculative provider retries. */
public final class MultimodalModelRoute {
    private final Set<String> primaryModels;
    private final ChatModel fallbackModel;
    private final StreamingChatModel fallbackStreamingModel;
    private final String fallbackModelId;

    public MultimodalModelRoute(
            Set<String> primaryModels,
            ChatModel fallbackModel,
            StreamingChatModel fallbackStreamingModel,
            String fallbackModelId) {
        this.primaryModels = primaryModels == null ? Set.of()
                : primaryModels.stream().filter(Objects::nonNull)
                        .map(MultimodalModelRoute::normalized).filter(value -> !value.isBlank())
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.fallbackModel = fallbackModel;
        this.fallbackStreamingModel = fallbackStreamingModel;
        this.fallbackModelId = fallbackModelId == null ? "" : fallbackModelId.trim();
    }

    public Selection select(
            String primaryModelId,
            ChatModel primaryModel,
            StreamingChatModel primaryStreamingModel,
            boolean hasMultimodalContent) {
        if (!hasMultimodalContent || primaryModels.contains(normalized(primaryModelId))) {
            return new Selection(primaryModel, primaryStreamingModel, primaryModelId);
        }
        if (fallbackModel == null || fallbackModelId.isBlank()) {
            throw new LlmProviderException(
                    "The selected model is not configured for multimodal attachments and "
                            + "MEGURI_LLM_MULTIMODAL_FALLBACK_MODEL is not configured.");
        }
        return new Selection(fallbackModel, fallbackStreamingModel, fallbackModelId);
    }

    public static MultimodalModelRoute disabled() {
        return new MultimodalModelRoute(Set.of(), null, null, "");
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /** Selected model clients for the current request. */
    public record Selection(ChatModel model, StreamingChatModel streamingModel, String modelId) { }
}

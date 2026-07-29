package com.meguri.core.context;

import java.util.List;

/** Inputs owned by adjacent runtimes; conversation history itself is loaded from SessionContextStore. */
public record ContextBuildRequest(
        String userId,
        String clientId,
        String conversationId,
        ContextProfile profile,
        String topicHint,
        double topicConfidence,
        List<ExternalBlock> externalBlocks) {
    public ContextBuildRequest {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId must not be blank");
        }
        externalBlocks = externalBlocks == null ? List.of() : List.copyOf(externalBlocks);
    }

    public record ExternalBlock(
            ContextBundle.BlockType blockType,
            List<String> sourceIds,
            ContextBundle.Trust trust,
            String content,
            boolean required) {
        public ExternalBlock {
            sourceIds = sourceIds == null ? List.of() : List.copyOf(sourceIds);
            content = content == null ? "" : content;
        }
    }
}

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
        List<ExternalBlock> externalBlocks,
        String currentInput,
        TopicSignal topicSignal,
        RehydrationPolicy rehydrationPolicy) {
    public ContextBuildRequest(
            String userId,
            String clientId,
            String conversationId,
            ContextProfile profile,
            String topicHint,
            double topicConfidence,
            List<ExternalBlock> externalBlocks) {
        this(userId, clientId, conversationId, profile, topicHint, topicConfidence,
                externalBlocks, "", TopicSignal.fromLegacy(topicHint, topicConfidence),
                RehydrationPolicy.disabled());
    }

    public ContextBuildRequest {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId must not be blank");
        }
        externalBlocks = externalBlocks == null ? List.of() : List.copyOf(externalBlocks);
        currentInput = currentInput == null ? "" : currentInput;
        topicSignal = topicSignal == null
                ? TopicSignal.fromLegacy(topicHint, topicConfidence) : topicSignal;
        rehydrationPolicy = rehydrationPolicy == null
                ? RehydrationPolicy.disabled() : rehydrationPolicy;
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

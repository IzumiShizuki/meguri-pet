package com.meguri.core.context;

import java.util.List;
import java.util.Map;

/** Immutable, provenance-preserving history input for the prompt composer. */
public record ContextBundle(
        String conversationId,
        String activeLeafMessageId,
        String topicSegmentId,
        List<Block> blocks,
        Budget budget,
        List<Truncation> truncations,
        String buildRevision) {
    public ContextBundle {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
        truncations = truncations == null ? List.of() : List.copyOf(truncations);
    }

    public enum BlockType {
        PERSONA, RELATIONSHIP, RECENT_RAW, SUMMARY, REHYDRATED,
        MEMORY, RETRIEVAL, TOOL_RESULT
    }

    public enum Trust { SYSTEM, USER, APPROVED_MEMORY, UNTRUSTED_EXTERNAL }

    public record Block(
            BlockType blockType,
            List<String> sourceIds,
            Trust trust,
            int tokenCount,
            String content) {
        public Block {
            sourceIds = sourceIds == null ? List.of() : List.copyOf(sourceIds);
            content = content == null ? "" : content;
            if (tokenCount < 0) throw new IllegalArgumentException("tokenCount must not be negative");
        }
    }

    public record Budget(
            int usableInputTokens,
            int softThresholdTokens,
            int hardThresholdTokens,
            int consumedTokens,
            String tokenizer,
            Map<BlockType, Integer> consumedBySource) {
        public Budget {
            consumedBySource = consumedBySource == null ? Map.of() : Map.copyOf(consumedBySource);
        }
    }

    public record Truncation(
            BlockType blockType,
            List<String> sourceIds,
            String reason,
            int tokensRemoved) {
        public Truncation {
            sourceIds = sourceIds == null ? List.of() : List.copyOf(sourceIds);
            if (reason == null || reason.isBlank()) throw new IllegalArgumentException("reason must not be blank");
        }
    }
}

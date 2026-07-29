package com.meguri.core.llm;

import com.meguri.core.context.ContextBundle;
import com.meguri.core.dto.RuntimeState;
import com.meguri.core.dto.TurnRequest;
import com.meguri.core.persona.prompt.PromptPolicyComposer;
import com.meguri.core.persona.runtime.EffectivePersonaState;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Immutable provider input produced by the canonical 20.x Turn pipeline. */
public record ProviderRequest(
        TurnRequest turn,
        RuntimeState runtimeState,
        EffectivePersonaState persona,
        ContextBundle context,
        List<PromptPolicyComposer.PromptBlock> promptBlocks,
        String knowledgeSnapshotId,
        String retrievalTraceId,
        String contextTraceId,
        String capabilitySnapshotId,
        List<CapabilityRef> capabilities,
        Instant deadline,
        String traceId) {
    public ProviderRequest {
        Objects.requireNonNull(turn, "turn");
        Objects.requireNonNull(runtimeState, "runtimeState");
        Objects.requireNonNull(persona, "persona");
        Objects.requireNonNull(context, "context");
        promptBlocks = promptBlocks == null ? List.of() : List.copyOf(promptBlocks);
        knowledgeSnapshotId = required(knowledgeSnapshotId, "knowledgeSnapshotId");
        retrievalTraceId = required(retrievalTraceId, "retrievalTraceId");
        contextTraceId = required(contextTraceId, "contextTraceId");
        capabilitySnapshotId = required(capabilitySnapshotId, "capabilitySnapshotId");
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        deadline = Objects.requireNonNull(deadline, "deadline");
        traceId = required(traceId, "traceId");
    }

    public List<String> legacyCanon() {
        return context.blocks().stream()
                .filter(block -> block.blockType() == ContextBundle.BlockType.RETRIEVAL)
                .filter(block -> !hasSource(block, "WEB"))
                .map(ContextBundle.Block::content)
                .toList();
    }

    public List<String> legacyMemories() {
        return context.blocks().stream()
                .filter(block -> block.blockType() == ContextBundle.BlockType.MEMORY)
                .map(ContextBundle.Block::content)
                .toList();
    }

    public List<String> legacyRecentContext() {
        return context.blocks().stream()
                .filter(block -> block.blockType() == ContextBundle.BlockType.RECENT_RAW
                        || block.blockType() == ContextBundle.BlockType.SUMMARY
                        || block.blockType() == ContextBundle.BlockType.REHYDRATED
                        || block.blockType() == ContextBundle.BlockType.TOOL_RESULT)
                .map(ContextBundle.Block::content)
                .toList();
    }

    public List<String> legacyWebResults() {
        return context.blocks().stream()
                .filter(block -> block.blockType() == ContextBundle.BlockType.RETRIEVAL)
                .filter(block -> hasSource(block, "WEB"))
                .map(ContextBundle.Block::content)
                .toList();
    }

    public ProviderRequest withPromptBlocks(
            List<PromptPolicyComposer.PromptBlock> replacement) {
        return new ProviderRequest(turn, runtimeState, persona, context, replacement,
                knowledgeSnapshotId, retrievalTraceId, contextTraceId,
                capabilitySnapshotId, capabilities, deadline, traceId);
    }

    /** Stable digest of semantic canonical input before provider-specific framing. */
    public String canonicalPromptDigest() {
        StringBuilder canonical = new StringBuilder("meguri-provider-prompt-v1");
        append(canonical, turn.getMessage());
        append(canonical, turn.getReplyFormat());
        append(canonical, runtimeState.getClientId());
        append(canonical, runtimeState.getMode().name());
        append(canonical, runtimeState.getRelationshipProfile().name());
        append(canonical, runtimeState.getOutfitCode());
        append(canonical, runtimeState.getLocalTime());
        append(canonical, Boolean.toString(runtimeState.isHoliday()));
        append(canonical, Boolean.toString(runtimeState.isVoiceEnabled()));
        append(canonical, Boolean.toString(runtimeState.isScreenContextEnabled()));
        runtimeState.getAllowedExpressionTags().forEach(value -> append(canonical, value.name()));
        for (PromptPolicyComposer.PromptBlock block : promptBlocks) {
            append(canonical, block.role().name());
            append(canonical, block.source().name());
            append(canonical, block.trust().name());
            append(canonical, block.provenance());
            append(canonical, block.revision());
            append(canonical, block.content());
        }
        capabilities.stream()
                .sorted(Comparator.comparing(CapabilityRef::id)
                        .thenComparing(CapabilityRef::version))
                .forEach(capability -> {
                    append(canonical, capability.id());
                    append(canonical, capability.version());
                });
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static void append(StringBuilder target, String value) {
        String normalized = value == null ? "" : value;
        target.append('\n').append(normalized.length()).append(':').append(normalized);
    }

    private static boolean hasSource(ContextBundle.Block block, String source) {
        return block.sourceIds().stream().anyMatch(source::equalsIgnoreCase);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    public record CapabilityRef(String id, String version) {
        public CapabilityRef {
            id = required(id, "id");
            version = required(version, "version");
        }
    }
}

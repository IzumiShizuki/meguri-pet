package com.meguri.core.llm;

import com.meguri.core.context.ContextBundle;
import com.meguri.core.dto.RuntimeState;
import com.meguri.core.dto.TurnRequest;
import com.meguri.core.persona.prompt.PromptPolicyComposer;
import com.meguri.core.persona.runtime.EffectivePersonaState;
import com.meguri.core.retrieval.RetrievalBundle;
import com.meguri.core.retrieval.RetrievalLaneResult;
import com.meguri.core.retrieval.SourceType;

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
        String traceId,
        WebRetrievalSummary webRetrievalSummary,
        String checkedInputText) {
    public ProviderRequest(
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
        this(turn, runtimeState, persona, context, promptBlocks, knowledgeSnapshotId,
                retrievalTraceId, contextTraceId, capabilitySnapshotId, capabilities,
                deadline, traceId, WebRetrievalSummary.notAttempted(), null);
    }

    public ProviderRequest(
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
            String traceId,
            WebRetrievalSummary webRetrievalSummary) {
        this(turn, runtimeState, persona, context, promptBlocks, knowledgeSnapshotId,
                retrievalTraceId, contextTraceId, capabilitySnapshotId, capabilities,
                deadline, traceId, webRetrievalSummary, null);
    }

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
        webRetrievalSummary = webRetrievalSummary == null
                ? WebRetrievalSummary.notAttempted() : webRetrievalSummary;
        if (checkedInputText != null && checkedInputText.isBlank()) checkedInputText = null;
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
                capabilitySnapshotId, capabilities, deadline, traceId, webRetrievalSummary,
                checkedInputText);
    }

    public ProviderRequest withWebRetrievalSummary(WebRetrievalSummary replacement) {
        return new ProviderRequest(turn, runtimeState, persona, context, promptBlocks,
                knowledgeSnapshotId, retrievalTraceId, contextTraceId,
                capabilitySnapshotId, capabilities, deadline, traceId, replacement,
                checkedInputText);
    }

    /** Adds non-authoritative input assistance while retaining the immutable original request. */
    public ProviderRequest withCheckedInputText(String replacement) {
        String normalized = replacement == null || replacement.equals(turn.getMessage())
                ? null : replacement;
        return new ProviderRequest(turn, runtimeState, persona, context, promptBlocks,
                knowledgeSnapshotId, retrievalTraceId, contextTraceId,
                capabilitySnapshotId, capabilities, deadline, traceId, webRetrievalSummary,
                normalized);
    }

    public String rawInputText() {
        return turn.getMessage();
    }

    public String effectiveInputText() {
        return checkedInputText == null ? turn.getMessage() : checkedInputText;
    }

    /** Stable digest of semantic canonical input before provider-specific framing. */
    public String canonicalPromptDigest() {
        StringBuilder canonical = new StringBuilder("meguri-provider-prompt-v1");
        append(canonical, turn.getMessage());
        if (checkedInputText != null) append(canonical, checkedInputText);
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
            append(canonical, block.content());
        }
        capabilities.stream()
                .sorted(Comparator.comparing(CapabilityRef::id)
                        .thenComparing(CapabilityRef::version))
                .forEach(capability -> {
                    append(canonical, capability.id());
                    append(canonical, capability.version());
                });
        append(canonical, webRetrievalSummary.status().name());
        append(canonical, Integer.toString(webRetrievalSummary.resultCount()));
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

    /** Small public projection of this turn's frozen WEB lane, independent of callable tools. */
    public record WebRetrievalSummary(Status status, int resultCount) {
        public WebRetrievalSummary {
            status = status == null ? Status.NOT_ATTEMPTED : status;
            if (resultCount < 0) throw new IllegalArgumentException("resultCount cannot be negative");
            if (status != Status.RESULTS_AVAILABLE && resultCount != 0) {
                throw new IllegalArgumentException("only results_available may contain a result count");
            }
        }

        public static WebRetrievalSummary notAttempted() {
            return new WebRetrievalSummary(Status.NOT_ATTEMPTED, 0);
        }

        public static WebRetrievalSummary from(RetrievalBundle bundle) {
            if (bundle == null || !bundle.plan().sources().contains(SourceType.WEB)) {
                return notAttempted();
            }
            RetrievalLaneResult lane = bundle.lanes().get(SourceType.WEB);
            if (lane == null || lane.status() == RetrievalLaneResult.Status.SKIPPED) {
                return new WebRetrievalSummary(Status.SKIPPED, 0);
            }
            if (lane.degradations().contains("web_search_disabled")) {
                return new WebRetrievalSummary(Status.DISABLED, 0);
            }
            if (!lane.items().isEmpty()) {
                return new WebRetrievalSummary(Status.RESULTS_AVAILABLE, lane.items().size());
            }
            if (lane.status() == RetrievalLaneResult.Status.UNAVAILABLE
                    || lane.degradations().contains("web_search_unavailable")
                    || lane.degradations().contains("web_search_timeout")) {
                return new WebRetrievalSummary(Status.UNAVAILABLE, 0);
            }
            if (lane.degradations().contains("web_policy_skipped")) {
                return new WebRetrievalSummary(Status.SKIPPED, 0);
            }
            return new WebRetrievalSummary(Status.EMPTY, 0);
        }

        public enum Status {
            NOT_ATTEMPTED,
            SKIPPED,
            DISABLED,
            UNAVAILABLE,
            EMPTY,
            RESULTS_AVAILABLE
        }
    }
}

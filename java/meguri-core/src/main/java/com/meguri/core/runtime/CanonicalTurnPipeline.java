package com.meguri.core.runtime;

import com.meguri.core.capability.CapabilityRuntimeFacade;
import com.meguri.core.capability.CapabilityDescriptor;
import com.meguri.core.capability.McpContentResolver;
import com.meguri.core.capability.McpExternalContent;
import com.meguri.core.capability.PromptSkillContextContract;
import com.meguri.core.capability.ToolProposal;
import com.meguri.core.context.CompanionContextRuntime;
import com.meguri.core.context.ContextBuildRequest;
import com.meguri.core.context.ContextBundle;
import com.meguri.core.context.ContextProfile;
import com.meguri.core.context.DeterministicTopicDetector;
import com.meguri.core.context.RehydrationPolicy;
import com.meguri.core.context.TopicDetector;
import com.meguri.core.context.TopicSignal;
import com.meguri.core.dto.ExpressionTag;
import com.meguri.core.dto.RuntimeState;
import com.meguri.core.dto.TurnRequest;
import com.meguri.core.execution.ExecutionModeDecision;
import com.meguri.core.execution.TurnExecutionMode;
import com.meguri.core.llm.ProviderRequest;
import com.meguri.core.observability.TurnLatencyMissingReason;
import com.meguri.core.observability.TurnLatencyPoint;
import com.meguri.core.observability.TurnLatencyTraceRecorder;
import com.meguri.core.persona.PersonaRuntimeFacade;
import com.meguri.core.persona.prompt.PromptPolicyComposer;
import com.meguri.core.persona.runtime.ClientCapabilityState;
import com.meguri.core.persona.runtime.EffectivePersonaState;
import com.meguri.core.persona.runtime.InteractionState;
import com.meguri.core.retrieval.FrozenKnowledgeSnapshot;
import com.meguri.core.retrieval.RetrievalBundle;
import com.meguri.core.retrieval.RetrievalItem;
import com.meguri.core.retrieval.SourceType;
import com.meguri.core.retrieval.UnifiedRetrievalFacade;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** The single synchronous preparation path from frozen Persona through the typed provider request. */
public final class CanonicalTurnPipeline {
    private static final int MAX_PROMPT_SKILLS = 4;
    private static final int PROMPT_SKILL_TOKEN_BUDGET = 512;

    private final PersonaRuntimeFacade personaRuntime;
    private final UnifiedRetrievalFacade retrieval;
    private final CompanionContextRuntime contextRuntime;
    private final PromptPolicyComposer promptComposer;
    private final ContextProfile contextProfile;
    private final CapabilityRuntimeFacade capabilityRuntime;
    private final McpContentResolver mcpContentResolver;
    private final boolean selectiveRehydrationEnabled;
    private final TopicDetector topicDetector;

    public CanonicalTurnPipeline(
            PersonaRuntimeFacade personaRuntime,
            UnifiedRetrievalFacade retrieval,
            CompanionContextRuntime contextRuntime,
            PromptPolicyComposer promptComposer,
            ContextProfile contextProfile,
            CapabilityRuntimeFacade capabilityRuntime) {
        this(personaRuntime, retrieval, contextRuntime, promptComposer,
                contextProfile, capabilityRuntime, McpContentResolver.unavailable(), false,
                new DeterministicTopicDetector());
    }

    public CanonicalTurnPipeline(
            PersonaRuntimeFacade personaRuntime,
            UnifiedRetrievalFacade retrieval,
            CompanionContextRuntime contextRuntime,
            PromptPolicyComposer promptComposer,
            ContextProfile contextProfile,
            CapabilityRuntimeFacade capabilityRuntime,
            McpContentResolver mcpContentResolver) {
        this(personaRuntime, retrieval, contextRuntime, promptComposer, contextProfile,
                capabilityRuntime, mcpContentResolver, false, new DeterministicTopicDetector());
    }

    public CanonicalTurnPipeline(
            PersonaRuntimeFacade personaRuntime,
            UnifiedRetrievalFacade retrieval,
            CompanionContextRuntime contextRuntime,
            PromptPolicyComposer promptComposer,
            ContextProfile contextProfile,
            CapabilityRuntimeFacade capabilityRuntime,
            McpContentResolver mcpContentResolver,
            boolean selectiveRehydrationEnabled,
            TopicDetector topicDetector) {
        this.personaRuntime = Objects.requireNonNull(personaRuntime, "personaRuntime");
        this.retrieval = Objects.requireNonNull(retrieval, "retrieval");
        this.contextRuntime = Objects.requireNonNull(contextRuntime, "contextRuntime");
        this.promptComposer = Objects.requireNonNull(promptComposer, "promptComposer");
        this.contextProfile = Objects.requireNonNull(contextProfile, "contextProfile");
        this.capabilityRuntime = Objects.requireNonNull(capabilityRuntime, "capabilityRuntime");
        this.mcpContentResolver = Objects.requireNonNull(
                mcpContentResolver, "mcpContentResolver");
        this.selectiveRehydrationEnabled = selectiveRehydrationEnabled;
        this.topicDetector = Objects.requireNonNull(topicDetector, "topicDetector");
    }

    public EffectivePersonaState resolvePersona(
            TurnRecord record, RuntimeState temporalState) {
        TurnRequest request = record.getRequest();
        InteractionState interaction = new InteractionState(
                "conversation", InteractionState.Urgency.NORMAL,
                "zh_ja_pairs".equals(request.getReplyFormat())
                        ? Set.of("sentence_aligned_bilingual") : Set.of(),
                null, Set.of());
        ClientCapabilityState client = new ClientCapabilityState(
                request.getClientId(),
                request.getClientCapabilities().isVoice(),
                request.getClientCapabilities().isSprite(),
                request.getClientCapabilities().isSprite(),
                request.getClientCapabilities().isScreenContext(),
                request.getClientCapabilities().isText());
        return personaRuntime.resolveSafely(new PersonaRuntimeFacade.Request(
                "meguri", request.getUserId(), request.getSessionId(),
                request.getSessionId(), record.getTurnId(), temporalState.getMode(),
                interaction, client));
    }

    public RuntimeState applyPersona(RuntimeState temporal, EffectivePersonaState persona) {
        List<ExpressionTag> expressions = persona.envelope().expressionTags().stream()
                .map(ExpressionTag::fromValue)
                .toList();
        return new RuntimeState(
                temporal.getClientId(), persona.mode(), persona.relationship().stage(),
                temporal.getOutfitCode(), temporal.getLocalTime(), temporal.isHoliday(),
                persona.capabilities().voice(), persona.capabilities().screenContext(),
                expressions);
    }

    public FrozenKnowledgeSnapshot freezeKnowledge(TurnRequest request, java.time.Instant frozenAt) {
        return retrieval.freezeForTurn(frozenAt, request.tenantId());
    }

    public CanonicalTurnPipeline withRetrieval(UnifiedRetrievalFacade replacement) {
        return new CanonicalTurnPipeline(
                personaRuntime, replacement, contextRuntime, promptComposer, contextProfile,
                capabilityRuntime, mcpContentResolver, selectiveRehydrationEnabled, topicDetector);
    }

    public CanonicalTurnPipeline withMcpContentResolver(
            McpContentResolver replacement) {
        return new CanonicalTurnPipeline(
                personaRuntime, retrieval, contextRuntime, promptComposer, contextProfile,
                capabilityRuntime, Objects.requireNonNull(replacement, "replacement"),
                selectiveRehydrationEnabled, topicDetector);
    }

    public Prepared prepare(
            TurnRecord record,
            RuntimeState runtimeState,
            EffectivePersonaState persona,
            CapabilityRuntimeFacade.TurnCapabilities capabilities,
            List<ContextBuildRequest.ExternalBlock> adjacentBlocks) {
        TurnRequest request = record.getRequest();
        FrozenKnowledgeSnapshot knowledge = FrozenKnowledgeSnapshot.restore(
                record.getManifest().knowledgeSnapshotId());
        mark(record, TurnLatencyPoint.RETRIEVAL_GATE_STARTED);
        com.meguri.core.retrieval.RetrievalMode effectiveRetrievalMode = retrievalMode(record);
        mark(record, TurnLatencyPoint.RETRIEVAL_GATE_READY);
        if (effectiveRetrievalMode != com.meguri.core.retrieval.RetrievalMode.SLOW) {
            markMissing(record, TurnLatencyPoint.QUERY_REWRITE_STARTED,
                    TurnLatencyMissingReason.STAGE_BYPASSED);
            markMissing(record, TurnLatencyPoint.QUERY_REWRITE_READY,
                    TurnLatencyMissingReason.STAGE_BYPASSED);
        }
        mark(record, TurnLatencyPoint.RETRIEVAL_STARTED);
        RetrievalBundle retrievalBundle = retrieval.retrieve(
                new UnifiedRetrievalFacade.Request(
                        request.getMessage(), effectiveRetrievalMode, request.tenantId(),
                        request.getUserId(), retrievalScopes(record), record.getManifest().frozenAt(),
                        record.getDeadlineAt(), record.getTraceId(), runtimeState, request),
                knowledge);
        mark(record, TurnLatencyPoint.RETRIEVAL_MINIMUM_READY);
        mark(record, TurnLatencyPoint.RETRIEVAL_ALL_SETTLED);

        PromptSkillBatch promptSkills = executePromptSkills(record, capabilities);
        List<ContextBuildRequest.ExternalBlock> adjacent = new ArrayList<>(
                adjacentBlocks == null ? List.of() : adjacentBlocks);
        McpContentBatch mcpContent = resolveMcpContent(request);
        adjacent.addAll(mcpContent.blocks());
        return assemble(record, runtimeState, persona, capabilities,
                knowledge, retrievalBundle, promptSkills,
                List.copyOf(adjacent), mcpContent.executions());
    }

    public Prepared augment(
            TurnRecord record,
            RuntimeState runtimeState,
            EffectivePersonaState persona,
            CapabilityRuntimeFacade.TurnCapabilities capabilities,
            Prepared prepared,
            List<ContextBuildRequest.ExternalBlock> additionalBlocks) {
        Objects.requireNonNull(prepared, "prepared");
        List<ContextBuildRequest.ExternalBlock> adjacent =
                new ArrayList<>(prepared.adjacentBlocks());
        if (additionalBlocks != null) adjacent.addAll(additionalBlocks);
        FrozenKnowledgeSnapshot knowledge = FrozenKnowledgeSnapshot.restore(
                record.getManifest().knowledgeSnapshotId());
        return assemble(record, runtimeState, persona, capabilities,
                knowledge, prepared.retrieval(),
                new PromptSkillBatch(
                        prepared.promptContexts(), prepared.promptSkills()),
                List.copyOf(adjacent), prepared.mcpContent());
    }

    public void freeze(TurnRecord record, Prepared prepared) {
        record.freezeRetrievalBundle(prepared.retrieval());
        record.freezeContextBuild(prepared.context());
        record.freezeProviderRequest(prepared.providerRequest());
    }

    private Prepared assemble(
            TurnRecord record,
            RuntimeState runtimeState,
            EffectivePersonaState persona,
            CapabilityRuntimeFacade.TurnCapabilities capabilities,
            FrozenKnowledgeSnapshot knowledge,
            RetrievalBundle retrievalBundle,
            PromptSkillBatch promptSkills,
            List<ContextBuildRequest.ExternalBlock> adjacentBlocks,
            List<McpContentExecution> mcpContent) {
        TurnRequest request = record.getRequest();
        List<ContextBuildRequest.ExternalBlock> external = new ArrayList<>();
        retrievalBundle.items().forEach(item -> external.add(toContextBlock(item)));
        external.addAll(adjacentBlocks);
        ExecutionModeDecision execution = record.getExecutionModeDecision();
        boolean think = execution != null && execution.mode() == TurnExecutionMode.THINK;
        TopicSignal topicSignal = topicDetector.detect(request.getMessage(), List.of());
        RehydrationPolicy rehydrationPolicy = think
                ? RehydrationPolicy.thinkDefault() : RehydrationPolicy.disabled();
        mark(record, TurnLatencyPoint.CONTEXT_BUILD_STARTED);
        // The legacy hint is retained only for the topic-detector-off compatibility path;
        // enabled topic detection consumes the independent raw-input TopicSignal.
        CompanionContextRuntime.BuildResult contextBuild = contextRuntime.build(
                new ContextBuildRequest(
                        request.getUserId(), request.getClientId(), request.getSessionId(),
                        contextProfile, retrievalBundle.plan().query().rewrittenQuery(), 0.75, external,
                        request.getMessage(), topicSignal, rehydrationPolicy));
        mark(record, TurnLatencyPoint.CONTEXT_READY);
        List<PromptPolicyComposer.PromptBlock> promptBlocks =
                promptComposer.compose(persona, contextBuild.bundle(), promptSkills.contexts());
        ProviderRequest providerRequest = new ProviderRequest(
                request, runtimeState, persona, contextBuild.bundle(), promptBlocks,
                knowledge.snapshotId(), retrievalBundle.traceId(), contextBuild.traceId(),
                capabilities.snapshotId(), capabilities.exposed().stream()
                        .map(ref -> new ProviderRequest.CapabilityRef(ref.id(), ref.version()))
                        .toList(), record.getDeadlineAt(), record.getTraceId());
        return new Prepared(
                retrievalBundle, contextBuild, providerRequest,
                promptSkills.executions(), promptSkills.contexts(),
                adjacentBlocks, mcpContent);
    }

    private static void mark(TurnRecord record, TurnLatencyPoint point) {
        TurnLatencyTraceRecorder recorder = record.getLatencyTraceRecorder();
        if (recorder != null) recorder.mark(point);
    }

    private static void markMissing(
            TurnRecord record,
            TurnLatencyPoint point,
            TurnLatencyMissingReason reason) {
        TurnLatencyTraceRecorder recorder = record.getLatencyTraceRecorder();
        if (recorder != null) recorder.markMissing(point, reason);
    }

    private static com.meguri.core.retrieval.RetrievalMode retrievalMode(TurnRecord record) {
        TurnRequest request = record.getRequest();
        com.meguri.core.retrieval.RetrievalMode requested = switch (request.retrievalMode()) {
            case NONE -> com.meguri.core.retrieval.RetrievalMode.NONE;
            case FAST -> com.meguri.core.retrieval.RetrievalMode.FAST;
            case SLOW -> com.meguri.core.retrieval.RetrievalMode.SLOW;
        };
        ExecutionModeDecision decision = record.getExecutionModeDecision();
        if (decision == null || decision.mode() != TurnExecutionMode.FAST) {
            return requested;
        }
        com.meguri.core.retrieval.RetrievalMode fastRequested =
                requested == com.meguri.core.retrieval.RetrievalMode.NONE
                        ? requested : com.meguri.core.retrieval.RetrievalMode.FAST;
        return new com.meguri.core.retrieval.RetrievalGate()
                .classify(request.getMessage(), fastRequested);
    }

    private static Set<String> retrievalScopes(TurnRecord record) {
        TurnRequest request = record.getRequest();
        LinkedHashSet<String> scopes = new LinkedHashSet<>();
        // formalMemoryAllowed is rebound from the authenticated adapter identity
        // before the Turn reaches this pipeline; the request body is not authority.
        if (request.formalMemoryAllowed()) scopes.add("memory:read");
        if (retrievalMode(record) == com.meguri.core.retrieval.RetrievalMode.SLOW) {
            scopes.add("web:read");
        }
        return Set.copyOf(scopes);
    }

    private PromptSkillBatch executePromptSkills(
            TurnRecord record,
            CapabilityRuntimeFacade.TurnCapabilities capabilities) {
        TurnRequest request = record.getRequest();
        ExecutionModeDecision execution = record.getExecutionModeDecision();
        if (execution != null
                && (execution.mode() != TurnExecutionMode.AGENT || execution.reactEligible())) {
            // When ReAct is active, prompt skills are selected by the planner
            // round-by-round. Do not eagerly execute every exposed skill here.
            return new PromptSkillBatch(List.of(), List.of());
        }
        List<PromptSkillContextContract.ContextInput> contexts = new ArrayList<>();
        List<PromptSkillExecution> executions = new ArrayList<>();
        int remainingTokens = PROMPT_SKILL_TOKEN_BUDGET;
        List<CapabilityDescriptor> descriptors = capabilityRuntime
                .exposedDescriptors(capabilities).stream()
                .filter(descriptor -> descriptor.kind()
                        == CapabilityDescriptor.Kind.PROMPT_SKILL)
                .sorted(java.util.Comparator.comparing(CapabilityDescriptor::id))
                .limit(MAX_PROMPT_SKILLS)
                .toList();
        for (CapabilityDescriptor descriptor : descriptors) {
            try {
                PromptSkillContextContract.ContextInput context =
                        capabilityRuntime.promptSkillContext(
                                capabilities,
                                new ToolProposal(
                                        record.getTurnId(), record.getTraceId(),
                                        request.tenantId(), request.getUserId(),
                                        request.getClientId(), descriptor.id(),
                                        Map.of(
                                                "message", request.getMessage(),
                                                "reply_format", request.getReplyFormat(),
                                                "retrieval_mode", request.retrievalMode().wireValue()),
                                        request.authorizedCapabilityScopes(),
                                        null, null, null,
                                        request.retrievalMode().permitsOpenRetrieval(), 1_000),
                                "turn:" + record.getTurnId() + ":prompt-skill:" + descriptor.id(),
                                remainingTokens);
                contexts.add(context);
                remainingTokens -= context.tokenEstimate();
                executions.add(new PromptSkillExecution(
                        descriptor.id(), descriptor.version(), true, null,
                        context.tokenEstimate()));
            } catch (RuntimeException failure) {
                executions.add(new PromptSkillExecution(
                        descriptor.id(), descriptor.version(), false,
                        failure.getClass().getSimpleName(), 0));
            }
        }
        return new PromptSkillBatch(List.copyOf(contexts), List.copyOf(executions));
    }

    private McpContentBatch resolveMcpContent(TurnRequest request) {
        List<ContextBuildRequest.ExternalBlock> blocks = new ArrayList<>();
        List<McpContentExecution> executions = new ArrayList<>();
        for (TurnRequest.McpContentSelection selection
                : request.mcpContentSelections()) {
            try {
                List<McpExternalContent> values = mcpContentResolver.resolve(
                        selection, request.authorizedCapabilityScopes());
                values.forEach(value -> blocks.add(toContextBlock(value)));
                executions.add(new McpContentExecution(
                        selection.kind(), selection.sourceId(),
                        selection.identifier(), true, null, values.size()));
            } catch (RuntimeException failure) {
                executions.add(new McpContentExecution(
                        selection.kind(), selection.sourceId(),
                        selection.identifier(), false,
                        failure.getClass().getSimpleName(), 0));
                if (selection.required()) {
                    throw new IllegalStateException(
                            "required MCP content is unavailable", failure);
                }
            }
        }
        return new McpContentBatch(List.copyOf(blocks), List.copyOf(executions));
    }

    private static ContextBuildRequest.ExternalBlock toContextBlock(RetrievalItem item) {
        ContextBundle.BlockType blockType = item.sourceType() == SourceType.MEMORY
                ? ContextBundle.BlockType.MEMORY : ContextBundle.BlockType.RETRIEVAL;
        ContextBundle.Trust trust = item.sourceType() == SourceType.MEMORY
                ? ContextBundle.Trust.APPROVED_MEMORY
                : ContextBundle.Trust.UNTRUSTED_EXTERNAL;
        LinkedHashSet<String> sources = new LinkedHashSet<>();
        sources.add(item.sourceType().name());
        sources.add(item.sourceId());
        sources.add("trace:" + item.traceId());
        sources.addAll(item.evidenceChunkIds());
        StringBuilder content = new StringBuilder(item.content());
        if (!item.citation().displayReferences().isEmpty()) {
            content.append("\n[Citation: ")
                    .append(item.citation().displayReferences()).append(']');
        }
        if (item.graphPath() != null) {
            content.append("\n[Graph evidence: ").append(item.graphPath()).append(']');
        }
        return new ContextBuildRequest.ExternalBlock(
                blockType, List.copyOf(sources), trust, content.toString(), false);
    }

    private static ContextBuildRequest.ExternalBlock toContextBlock(
            McpExternalContent value) {
        ContextBundle.BlockType blockType =
                value.kind() == McpExternalContent.Kind.RESOURCE
                        ? ContextBundle.BlockType.RETRIEVAL
                        : ContextBundle.BlockType.TOOL_RESULT;
        return new ContextBuildRequest.ExternalBlock(
                blockType,
                List.of(
                        "MCP",
                        "mcp:" + value.server(),
                        "mcp-" + value.kind().name().toLowerCase(
                                java.util.Locale.ROOT) + ":" + value.identifier()),
                ContextBundle.Trust.UNTRUSTED_EXTERNAL,
                value.content(),
                false);
    }

    public record Prepared(
            RetrievalBundle retrieval,
            CompanionContextRuntime.BuildResult context,
            ProviderRequest providerRequest,
            List<PromptSkillExecution> promptSkills,
            List<PromptSkillContextContract.ContextInput> promptContexts,
            List<ContextBuildRequest.ExternalBlock> adjacentBlocks,
            List<McpContentExecution> mcpContent) {
        public Prepared {
            promptSkills = promptSkills == null ? List.of() : List.copyOf(promptSkills);
            promptContexts = promptContexts == null ? List.of() : List.copyOf(promptContexts);
            adjacentBlocks = adjacentBlocks == null ? List.of() : List.copyOf(adjacentBlocks);
            mcpContent = mcpContent == null ? List.of() : List.copyOf(mcpContent);
        }
    }

    public record PromptSkillExecution(
            String capabilityId,
            String capabilityVersion,
            boolean succeeded,
            String failureCode,
            int tokenEstimate) { }

    public record McpContentExecution(
            TurnRequest.McpContentSelection.Kind kind,
            String sourceId,
            String identifier,
            boolean succeeded,
            String failureCode,
            int itemCount) { }

    private record PromptSkillBatch(
            List<PromptSkillContextContract.ContextInput> contexts,
            List<PromptSkillExecution> executions) { }

    private record McpContentBatch(
            List<ContextBuildRequest.ExternalBlock> blocks,
            List<McpContentExecution> executions) { }
}

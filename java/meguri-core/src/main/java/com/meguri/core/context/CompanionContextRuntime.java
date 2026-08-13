package com.meguri.core.context;

import com.meguri.core.llm.ProviderTokenizer;
import com.meguri.core.runtime.SessionContextStore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Online facade for the normative 20.1 pipeline. Adjacent runtimes provide typed inputs and receive
 * one immutable ContextBundle; all history selection remains inside this module.
 */
public final class CompanionContextRuntime {
    private final SessionContextStore conversations;
    private final ContextRuntimePersistence persistence;
    private final ContextBuildTraceRepository traces;
    private final BranchResolver branchResolver = new BranchResolver();
    private final TopicSegmentResolver topicResolver;
    private final SummarySelector summarySelector = new SummarySelector();
    private final ReferenceResolver referenceResolver = new ReferenceResolver();
    private final ContextRehydrationService rehydration = new ContextRehydrationService();
    private final SelectiveContextRehydrationService selectiveRehydration;
    private final TopicDetector topicDetector;
    private final boolean topicDetectionEnabled;
    private final boolean selectiveRehydrationEnabled;
    private final String strategyRevision;
    private final ConversationContextReadModelProvider readModelProvider;
    private final ContextAssembler assembler;

    public CompanionContextRuntime(SessionContextStore conversations,
                                   ContextRuntimePersistence persistence,
                                   ProviderTokenizer tokenizer) {
        this(conversations, persistence, tokenizer, new DeterministicTopicDetector(), false,
                false, ContextRefactoringStrategy.DEFAULT_REVISION,
                ConversationContextReadModelProvider.missing());
    }

    public CompanionContextRuntime(SessionContextStore conversations,
                                   ContextRuntimePersistence persistence,
                                   ProviderTokenizer tokenizer,
                                   TopicDetector topicDetector,
                                   boolean selectiveRehydrationEnabled,
                                   String strategyRevision) {
        this(conversations, persistence, tokenizer, topicDetector, selectiveRehydrationEnabled,
                false, strategyRevision, ConversationContextReadModelProvider.missing());
    }

    public CompanionContextRuntime(SessionContextStore conversations,
                                   ContextRuntimePersistence persistence,
                                   ProviderTokenizer tokenizer,
                                   TopicDetector topicDetector,
                                   boolean selectiveRehydrationEnabled,
                                   String strategyRevision,
                                   ConversationContextReadModelProvider readModelProvider) {
        this(conversations, persistence, tokenizer, topicDetector, selectiveRehydrationEnabled,
                false, strategyRevision, readModelProvider);
    }

    public CompanionContextRuntime(SessionContextStore conversations,
                                   ContextRuntimePersistence persistence,
                                   ProviderTokenizer tokenizer,
                                   TopicDetector topicDetector,
                                   boolean selectiveRehydrationEnabled,
                                   boolean topicDetectionEnabled,
                                   String strategyRevision,
                                   ConversationContextReadModelProvider readModelProvider) {
        this.conversations = conversations;
        this.persistence = persistence;
        this.traces = new ContextBuildTraceRepository(persistence);
        this.topicResolver = new TopicSegmentResolver(persistence);
        this.topicDetector = topicDetector == null ? new DeterministicTopicDetector() : topicDetector;
        this.topicDetectionEnabled = topicDetectionEnabled;
        this.selectiveRehydration = new SelectiveContextRehydrationService(tokenizer);
        this.selectiveRehydrationEnabled = selectiveRehydrationEnabled;
        this.strategyRevision = strategyRevision == null || strategyRevision.isBlank()
                ? ContextRefactoringStrategy.DEFAULT_REVISION : strategyRevision.trim();
        this.readModelProvider = readModelProvider == null
                ? ConversationContextReadModelProvider.missing() : readModelProvider;
        this.assembler = new ContextAssembler(new TokenBudgetAllocator(tokenizer));
    }

    public BuildResult build(ContextBuildRequest request) {
        SessionContextStore.GraphSnapshot graph = conversations.graph(
                request.userId(), request.clientId(), request.conversationId());
        boolean forceHarness = graph.references().stream()
                .anyMatch(reference -> java.util.Objects.equals(
                        graph.activeLeafMessageId(), reference.targetMessageId())
                        && reference.type() != SessionContextStore.ReferenceType.AUTO_FACT);
        ConversationContextReadModel readModel = null;
        if (!forceHarness) {
            try {
                readModel = readModelProvider.find(request, graph).orElse(null);
            } catch (RuntimeException ignored) {
                // A derived projection is never allowed to break the authoritative path.
            }
        }
        boolean readModelHit = ConversationContextReadModelProvider.verified(
                readModel, graph, strategyRevision);
        if (!readModelHit) {
            try {
                readModelProvider.rebuild(request, graph);
            } catch (RuntimeException ignored) {
                // Rebuild is best effort; the full Harness below remains authoritative.
            }
        }
        List<SessionContextStore.MessageNode> branch = readModelHit
                ? readModel.activePath() : branchResolver.resolve(graph);
        TopicSignal topicSignal = topicDetectionEnabled
                ? (request.topicSignal() == null
                        ? topicDetector.detect(request.currentInput(), branch) : request.topicSignal())
                : TopicSignal.fromLegacy(request.topicHint(), request.topicConfidence());
        ContextRuntimePersistence.TopicSegment topic = topicResolver.resolve(
                request.conversationId(), topicSignal.label(), topicSignal.confidence());
        List<SessionContextStore.DerivedSummary> summaries = summarySelector.select(
                graph, branch, readModelHit ? readModel.summaries() : graph.summaries());
        List<SessionContextStore.ContextReference> references = readModelHit
                ? readModel.referenceIndex().values().stream()
                        .filter(reference -> java.util.Objects.equals(graph.activeLeafMessageId(),
                                reference.targetMessageId())).toList()
                : referenceResolver.resolve(graph);
        List<ContextRehydrationService.RehydratedWindow> windows = rehydration.rehydrate(graph, references);
        RehydrationPolicy policy = selectiveRehydrationEnabled
                ? request.rehydrationPolicy() : RehydrationPolicy.disabled();
        List<SelectiveContextRehydrationService.Selection> automatic =
                selectiveRehydration.select(graph, summaries, request.currentInput(), policy);

        List<ContextCandidate> candidates = new ArrayList<>();
        int recency = 0;
        for (SessionContextStore.MessageNode message : branch) {
            boolean currentInput = message.messageId().equals(graph.activeLeafMessageId())
                    && "user".equals(message.role());
            candidates.add(new ContextCandidate(ContextBundle.BlockType.RECENT_RAW,
                    List.of(message.messageId()), ContextBundle.Trust.USER,
                    message.role() + ": " + message.content(), currentInput, recency++));
        }
        for (SessionContextStore.DerivedSummary summary : summaries) {
            candidates.add(new ContextCandidate(ContextBundle.BlockType.SUMMARY,
                    summary.sourceMessageIds(), ContextBundle.Trust.SYSTEM, summary.content(), false, recency++));
        }
        for (ContextRehydrationService.RehydratedWindow window : windows) {
            StringBuilder content = new StringBuilder();
            for (SessionContextStore.MessageNode message : window.messages()) {
                content.append(message.role()).append(": ").append(message.content()).append('\n');
            }
            candidates.add(new ContextCandidate(ContextBundle.BlockType.REHYDRATED,
                    List.of(window.referenceId(), window.sourceMessageId()), ContextBundle.Trust.USER,
                    content.toString(), false, recency++));
            candidates.add(new ContextCandidate(ContextBundle.BlockType.REHYDRATED,
                    List.of(window.referenceId(), window.sourceMessageId()), ContextBundle.Trust.USER,
                    "exact_reference: " + window.exactSnapshot(), true, recency++));
        }
        java.util.Map<String, List<SelectiveContextRehydrationService.Selection>> automaticByWindow =
                new java.util.LinkedHashMap<>();
        for (SelectiveContextRehydrationService.Selection selection : automatic) {
            automaticByWindow.computeIfAbsent(selection.window().referenceId(), ignored -> new ArrayList<>())
                    .add(selection);
        }
        for (List<SelectiveContextRehydrationService.Selection> selections : automaticByWindow.values()) {
            SelectiveContextRehydrationService.Selection first = selections.getFirst();
            java.util.LinkedHashSet<String> sourceIds = new java.util.LinkedHashSet<>();
            selections.forEach(selection -> sourceIds.add("auto-fact:" + selection.factId()));
            sourceIds.add(first.window().sourceMessageId());
            candidates.add(new ContextCandidate(ContextBundle.BlockType.REHYDRATED,
                    List.copyOf(sourceIds), ContextBundle.Trust.USER, first.content(), false, recency++, true));
        }
        List<ContextBundle.RehydrationDecision> rehydrationDecisions = automatic.stream()
                .map(selection -> new ContextBundle.RehydrationDecision(
                        selection.factId(), selection.sourceIds(), selection.reason(), selection.score(),
                        selection.tokenCount(), true))
                .toList();
        for (ContextBuildRequest.ExternalBlock block : request.externalBlocks()) {
            candidates.add(new ContextCandidate(block.blockType(), block.sourceIds(), block.trust(),
                    block.content(), block.required(), recency++));
        }

        String requestDigest = digest(request, graph.revision(), candidates);
        String revision = "ctx_" + requestDigest.substring(0, 20);
        ContextAssembler.Assembled assembled = assembler.assemble(
                request.conversationId(), graph.activeLeafMessageId(), topic.segmentId(), revision,
                request.profile(), candidates, rehydrationDecisions);
        String traceId = "trace_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        traces.save(traceId, request.conversationId(), graph.revision(), requestDigest, assembled.bundle());

        Optional<ContextRuntimePersistence.PrecompressionJob> job = Optional.empty();
        if (assembled.tokensBefore() >= assembled.bundle().budget().softThresholdTokens()) {
            String key = request.conversationId() + ":" + graph.revision() + ":"
                    + request.profile().modelId() + ":" + strategyRevision;
            job = Optional.of(persistence.enqueuePrecompression(new ContextRuntimePersistence.PrecompressionJob(
                    "precompress_" + digest(key).substring(0, 20), key,
                    request.userId(), request.clientId(), request.conversationId(), graph.revision(),
                    branch.stream().map(SessionContextStore.MessageNode::messageId).toList(),
                    request.profile().modelId(), strategyRevision,
                    ContextRuntimePersistence.JobStatus.PENDING, 0,
                    Instant.now(), null, null, Instant.now())));
        }
        return new BuildResult(traceId, assembled.bundle(), job);
    }

    /** Exact replay uses the durable bundle, never a second database read or a fresh selection pass. */
    public ContextBundle replay(String traceId) {
        return traces.replay(traceId);
    }

    private static String digest(ContextBuildRequest request, long revision, List<ContextCandidate> candidates) {
        StringBuilder value = new StringBuilder(request.conversationId()).append('|')
                .append(request.profile().modelId()).append('|').append(revision);
        candidates.forEach(candidate -> value.append('|').append(candidate.type())
                .append(':').append(candidate.sourceIds()).append(':').append(candidate.content()));
        return digest(value.toString());
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    public record BuildResult(
            String traceId,
            ContextBundle bundle,
            Optional<ContextRuntimePersistence.PrecompressionJob> precompressionJob) { }
}

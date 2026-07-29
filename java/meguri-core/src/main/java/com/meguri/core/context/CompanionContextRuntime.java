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
    private final ContextAssembler assembler;

    public CompanionContextRuntime(SessionContextStore conversations,
                                   ContextRuntimePersistence persistence,
                                   ProviderTokenizer tokenizer) {
        this.conversations = conversations;
        this.persistence = persistence;
        this.traces = new ContextBuildTraceRepository(persistence);
        this.topicResolver = new TopicSegmentResolver(persistence);
        this.assembler = new ContextAssembler(new TokenBudgetAllocator(tokenizer));
    }

    public BuildResult build(ContextBuildRequest request) {
        SessionContextStore.GraphSnapshot graph = conversations.graph(
                request.userId(), request.clientId(), request.conversationId());
        List<SessionContextStore.MessageNode> branch = branchResolver.resolve(graph);
        ContextRuntimePersistence.TopicSegment topic = topicResolver.resolve(
                request.conversationId(), request.topicHint(), request.topicConfidence());
        List<SessionContextStore.DerivedSummary> summaries = summarySelector.select(graph, branch);
        List<SessionContextStore.ContextReference> references = referenceResolver.resolve(graph);
        List<ContextRehydrationService.RehydratedWindow> windows = rehydration.rehydrate(graph, references);

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
        for (ContextBuildRequest.ExternalBlock block : request.externalBlocks()) {
            candidates.add(new ContextCandidate(block.blockType(), block.sourceIds(), block.trust(),
                    block.content(), block.required(), recency++));
        }

        String requestDigest = digest(request, graph.revision(), candidates);
        String revision = "ctx_" + requestDigest.substring(0, 20);
        ContextAssembler.Assembled assembled = assembler.assemble(
                request.conversationId(), graph.activeLeafMessageId(), topic.segmentId(), revision,
                request.profile(), candidates);
        String traceId = "trace_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        traces.save(traceId, request.conversationId(), graph.revision(), requestDigest, assembled.bundle());

        Optional<ContextRuntimePersistence.PrecompressionJob> job = Optional.empty();
        if (assembled.tokensBefore() >= assembled.bundle().budget().softThresholdTokens()) {
            String key = request.conversationId() + ":" + graph.revision() + ":" + request.profile().modelId();
            job = Optional.of(persistence.enqueuePrecompression(new ContextRuntimePersistence.PrecompressionJob(
                    "precompress_" + digest(key).substring(0, 20), key,
                    request.userId(), request.clientId(), request.conversationId(), graph.revision(),
                    branch.stream().map(SessionContextStore.MessageNode::messageId).toList(),
                    request.profile().modelId(), ContextRuntimePersistence.JobStatus.PENDING, 0,
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

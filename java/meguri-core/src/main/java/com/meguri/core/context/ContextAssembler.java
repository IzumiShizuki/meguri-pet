package com.meguri.core.context;

import java.util.List;

/** Final assembly boundary. No caller may append untraced history after this point. */
public final class ContextAssembler {
    private final TokenBudgetAllocator allocator;

    public ContextAssembler(TokenBudgetAllocator allocator) {
        this.allocator = allocator;
    }

    Assembled assemble(String conversationId, String activeLeaf, String topicSegmentId,
                       String buildRevision, ContextProfile profile, List<ContextCandidate> candidates) {
        return assemble(conversationId, activeLeaf, topicSegmentId, buildRevision, profile,
                candidates, List.of());
    }

    Assembled assemble(String conversationId, String activeLeaf, String topicSegmentId,
                       String buildRevision, ContextProfile profile, List<ContextCandidate> candidates,
                       List<ContextBundle.RehydrationDecision> rehydrationDecisions) {
        TokenBudgetAllocator.Allocation allocation = allocator.allocate(profile, candidates);
        List<ContextBundle.RehydrationDecision> decisions = rehydrationDecisions == null
                ? List.of() : rehydrationDecisions.stream().map(decision ->
                new ContextBundle.RehydrationDecision(
                        decision.factId(), decision.sourceIds(), decision.reason(), decision.score(),
                        decision.tokenCount(), decision.selected()
                                && allocation.blocks().stream().anyMatch(block -> block.sourceIds().stream()
                                .anyMatch(source -> source.equals("auto-fact:" + decision.factId())))))
                .toList();
        ContextBundle bundle = new ContextBundle(
                conversationId, activeLeaf, topicSegmentId, allocation.blocks(), allocation.budget(),
                allocation.truncations(), buildRevision, decisions);
        return new Assembled(bundle, allocation.tokensBefore());
    }

    record Assembled(ContextBundle bundle, int tokensBefore) { }
}

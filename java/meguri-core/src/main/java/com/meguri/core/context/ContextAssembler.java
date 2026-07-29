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
        TokenBudgetAllocator.Allocation allocation = allocator.allocate(profile, candidates);
        ContextBundle bundle = new ContextBundle(
                conversationId, activeLeaf, topicSegmentId, allocation.blocks(), allocation.budget(),
                allocation.truncations(), buildRevision);
        return new Assembled(bundle, allocation.tokensBefore());
    }

    record Assembled(ContextBundle bundle, int tokensBefore) { }
}

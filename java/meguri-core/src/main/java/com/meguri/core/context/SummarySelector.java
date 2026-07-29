package com.meguri.core.context;

import com.meguri.core.runtime.SessionContextStore;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Selects only ACTIVE summaries whose complete source range remains on this branch. */
public final class SummarySelector {
    public List<SessionContextStore.DerivedSummary> select(
            SessionContextStore.GraphSnapshot graph,
            List<SessionContextStore.MessageNode> branch) {
        Set<String> branchIds = new HashSet<>();
        branch.forEach(node -> branchIds.add(node.messageId()));
        return graph.summaries().stream()
                .filter(summary -> summary.status() == SessionContextStore.SummaryStatus.ACTIVE)
                .filter(summary -> !summary.sourceMessageIds().isEmpty())
                .filter(summary -> branchIds.containsAll(summary.sourceMessageIds()))
                .toList();
    }
}

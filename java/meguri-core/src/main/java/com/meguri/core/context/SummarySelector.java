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
        return select(graph, branch, graph.summaries());
    }

    public List<SessionContextStore.DerivedSummary> select(
            SessionContextStore.GraphSnapshot graph,
            List<SessionContextStore.MessageNode> branch,
            List<SessionContextStore.DerivedSummary> candidates) {
        Set<String> branchIds = new HashSet<>();
        branch.forEach(node -> branchIds.add(node.messageId()));
        return (candidates == null ? List.<SessionContextStore.DerivedSummary>of() : candidates).stream()
                .filter(summary -> summary.status() == SessionContextStore.SummaryStatus.ACTIVE)
                .filter(summary -> !summary.sourceMessageIds().isEmpty())
                .filter(summary -> branchIds.containsAll(summary.sourceMessageIds()))
                .filter(summary -> graph.summaries().stream()
                        .anyMatch(authority -> authority.summaryId().equals(summary.summaryId())
                                && authority.contextRevision() == summary.contextRevision()))
                .toList();
    }
}

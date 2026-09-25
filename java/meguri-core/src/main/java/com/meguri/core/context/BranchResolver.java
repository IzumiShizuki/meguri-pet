package com.meguri.core.context;

import com.meguri.core.runtime.SessionContextStore;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Rebuilds only the parent chain ending at the selected active leaf. */
public final class BranchResolver {
    public List<SessionContextStore.MessageNode> resolve(SessionContextStore.GraphSnapshot graph) {
        if (graph.activeLeafMessageId() == null) return List.of();
        Set<String> activeIds = new HashSet<>();
        graph.activePath().forEach(node -> activeIds.add(node.messageId()));
        if (!activeIds.contains(graph.activeLeafMessageId())) {
            throw new IllegalStateException("active path does not end at active leaf");
        }
        return graph.activePath();
    }
}

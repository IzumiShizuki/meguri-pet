package com.meguri.core.context;

import com.meguri.core.runtime.SessionContextStore;

import java.util.List;

/** Resolves references targeting this turn while preserving their distinct topology semantics. */
public final class ReferenceResolver {
    public List<SessionContextStore.ContextReference> resolve(SessionContextStore.GraphSnapshot graph) {
        String activeLeaf = graph.activeLeafMessageId();
        if (activeLeaf == null) return List.of();
        return graph.references().stream()
                .filter(reference -> activeLeaf.equals(reference.targetMessageId()))
                .toList();
    }
}

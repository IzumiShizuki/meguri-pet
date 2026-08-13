package com.meguri.core.context;

import com.meguri.core.runtime.SessionContextStore;

import java.util.List;
import java.util.Map;

/** Rebuildable projection of the active conversation path; never an authority store. */
public record ConversationContextReadModel(
        String conversationId,
        String activeLeafMessageId,
        String activeTopicSegmentId,
        SessionContextStore.DerivedSummary latestStableSummary,
        List<SessionContextStore.MessageNode> recentMessages,
        List<SessionContextStore.MessageNode> activePath,
        List<SessionContextStore.DerivedSummary> summaries,
        List<String> openThreads,
        Map<String, SessionContextStore.ContextReference> referenceIndex,
        Map<String, StructuredContextSummary.Fact> factIndex,
        int tokenCount,
        long graphRevision,
        String sourceDigest,
        String strategyRevision) {
    public ConversationContextReadModel {
        recentMessages = recentMessages == null ? List.of() : List.copyOf(recentMessages);
        activePath = activePath == null ? List.of() : List.copyOf(activePath);
        summaries = summaries == null ? List.of() : List.copyOf(summaries);
        openThreads = openThreads == null ? List.of() : List.copyOf(openThreads);
        referenceIndex = referenceIndex == null ? Map.of() : Map.copyOf(referenceIndex);
        factIndex = factIndex == null ? Map.of() : Map.copyOf(factIndex);
        if (tokenCount < 0) throw new IllegalArgumentException("tokenCount must not be negative");
    }
}

package com.meguri.core.context;

import com.meguri.core.llm.ProviderTokenizer;
import com.meguri.core.runtime.SessionContextStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Rebuildable local projection used until a durable upstream read model is supplied. */
public final class InMemoryConversationContextReadModelProvider
        implements ConversationContextReadModelProvider {
    private final ProviderTokenizer tokenizer;
    private final String strategyRevision;
    private final Map<String, ConversationContextReadModel> values = new ConcurrentHashMap<>();

    public InMemoryConversationContextReadModelProvider(
            ProviderTokenizer tokenizer, String strategyRevision) {
        this.tokenizer = tokenizer;
        this.strategyRevision = strategyRevision == null || strategyRevision.isBlank()
                ? ContextRefactoringStrategy.DEFAULT_REVISION : strategyRevision.trim();
    }

    @Override
    public Optional<ConversationContextReadModel> find(ContextBuildRequest request,
                                                       SessionContextStore.GraphSnapshot authority) {
        return Optional.ofNullable(values.get(key(request)));
    }

    @Override
    public void rebuild(ContextBuildRequest request,
                        SessionContextStore.GraphSnapshot authority) {
        int tokens = authority.activePath().stream()
                .mapToInt(node -> tokenizer.count(node.role() + ": " + node.content())).sum();
        List<SessionContextStore.DerivedSummary> activeSummaries = authority.summaries().stream()
                .filter(summary -> summary.status() == SessionContextStore.SummaryStatus.ACTIVE)
                .toList();
        SessionContextStore.DerivedSummary latest = activeSummaries.isEmpty()
                ? null : activeSummaries.getLast();
        List<String> openThreads = latest == null || latest.structured() == null
                ? List.of() : latest.structured().openThreads();
        Map<String, StructuredContextSummary.Fact> facts = new LinkedHashMap<>();
        activeSummaries.forEach(summary -> {
            if (summary.structured() != null) summary.structured().facts()
                    .forEach(fact -> facts.put(fact.factId(), fact));
        });
        Map<String, SessionContextStore.ContextReference> references = new LinkedHashMap<>();
        authority.references().forEach(reference -> references.put(reference.referenceId(), reference));
        int recentFrom = Math.max(0, authority.activePath().size() - 20);
        values.put(key(request), new ConversationContextReadModel(
                authority.sessionId(), authority.activeLeafMessageId(), null, latest,
                authority.activePath().subList(recentFrom, authority.activePath().size()),
                authority.activePath(), activeSummaries, openThreads, references, facts, tokens,
                authority.revision(), ConversationContextReadModelProvider.digest(authority),
                strategyRevision));
    }

    public void invalidate(String userId, String clientId, String conversationId) {
        values.remove(userId + "\u0000" + clientId + "\u0000" + conversationId);
    }

    private static String key(ContextBuildRequest request) {
        return request.userId() + "\u0000" + request.clientId()
                + "\u0000" + request.conversationId();
    }
}

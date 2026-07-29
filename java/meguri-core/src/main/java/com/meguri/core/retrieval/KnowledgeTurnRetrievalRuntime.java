package com.meguri.core.retrieval;

import java.time.Instant;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * Turn-facing boundary for freezing one tenant's knowledge authority and
 * executing typed retrieval against that immutable snapshot.
 */
public final class KnowledgeTurnRetrievalRuntime implements AutoCloseable {
    private final KnowledgeRepositoryRetrievalBridge bridge;
    private final RetrievalRuntime runtime;

    public KnowledgeTurnRetrievalRuntime(
            KnowledgeRepositoryRetrievalBridge bridge,
            RetrievalRuntime runtime) {
        this.bridge = java.util.Objects.requireNonNull(bridge);
        this.runtime = java.util.Objects.requireNonNull(runtime);
    }

    public Snapshot freeze(String tenantId, Instant at) {
        FrozenKnowledgeSnapshot snapshot = bridge.freezeForNewTurn(at, tenantId);
        long revision = snapshot.versions().stream()
                .map(FrozenKnowledgeSnapshot.VersionRef::publishedAt)
                .mapToLong(Instant::toEpochMilli)
                .max()
                .orElse(0L);
        return new Snapshot(snapshot.snapshotId(), revision, snapshot.versions().size());
    }

    public RetrievalBundle retrieve(
            String query,
            RetrievalMode mode,
            String tenantId,
            String principalId,
            Set<String> aclScopes,
            String snapshotId,
            long revision,
            Instant validAt,
            Instant deadline,
            String traceId) {
        return runtime.retrieve(query, mode, new RetrievalContext(
                principalId, aclScopes, snapshotId, revision,
                validAt, deadline, traceId, tenantId));
    }

    /** Compatibility migration for callers that previously injected only the Knowledge lane. */
    public UnifiedRetrievalFacade unifiedFacade(
            com.meguri.core.rag.RagProvider lore,
            com.meguri.core.memory.MemoryGateway memory,
            com.meguri.core.websearch.WebSearchGateway web) {
        EnumMap<SourceType, RetrievalProvider> providers = new EnumMap<>(SourceType.class);
        providers.put(SourceType.LORE, new LoreRetrievalProviderAdapter(lore));
        if (memory != null) providers.put(SourceType.MEMORY, new MemoryRetrievalProviderAdapter(memory));
        if (web != null) providers.put(SourceType.WEB, new WebSearchRetrievalProviderAdapter(web));
        KnowledgeGraphRetrievalService graph =
                new KnowledgeGraphRetrievalService(bridge, bridge, bridge);
        RetrievalRuntime unified = new RetrievalRuntime(
                new DefaultRetrievalPlanner(), new RetrievalGate(), providers, bridge, graph,
                new BundleAssembler(), runtime.traceRepository(),
                java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor(),
                Duration.ofSeconds(2), Duration.ofMillis(500), true,
                RetrievalAuthorizationPolicy.allowAll());
        return new UnifiedRetrievalFacade(bridge, unified);
    }

    @Override
    public void close() {
        runtime.close();
    }

    public record Snapshot(String id, long revision, int versionCount) {
        public Snapshot {
            if (id == null || id.isBlank() || revision < 0 || versionCount < 0) {
                throw new IllegalArgumentException("invalid knowledge snapshot");
            }
        }
    }
}

package com.meguri.core.retrieval;

import java.time.Instant;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Single turn-facing entry point for all 20.3 retrieval sources. */
public final class UnifiedRetrievalFacade implements AutoCloseable {
    private final KnowledgeRepositoryRetrievalBridge knowledge;
    private final RetrievalRuntime runtime;

    public UnifiedRetrievalFacade(
            KnowledgeRepositoryRetrievalBridge knowledge, RetrievalRuntime runtime) {
        this.knowledge = java.util.Objects.requireNonNull(knowledge);
        this.runtime = java.util.Objects.requireNonNull(runtime);
    }

    /** Makes legacy constructor dependencies obey the same typed Planner/Bundle/Trace path. */
    public static UnifiedRetrievalFacade compatibility(
            com.meguri.core.rag.RagProvider lore,
            com.meguri.core.memory.MemoryGateway memory,
            com.meguri.core.websearch.WebSearchGateway web) {
        KnowledgeSearchProjectionPort empty = new KnowledgeSearchProjectionPort() {
            @Override public List<com.meguri.core.knowledge.KnowledgeDocumentVersion> activeVersions() {
                return List.of();
            }

            @Override public Projection loadVersions(Set<String> versionIds) {
                if (versionIds != null && !versionIds.isEmpty()) {
                    throw new IllegalArgumentException("compatibility knowledge projection is empty");
                }
                return new Projection(List.of(), List.of(), List.of(), List.of(), List.of());
            }
        };
        KnowledgeVectorizer vectorizer = text ->
                com.meguri.core.knowledge.DeterministicSearchProjector.embedding(text).stream()
                        .mapToDouble(Double::doubleValue).toArray();
        KnowledgeRepositoryRetrievalBridge bridge = new KnowledgeRepositoryRetrievalBridge(
                empty, vectorizer, new WeightedRrfFusion(), 512);
        EnumMap<SourceType, RetrievalProvider> providers = new EnumMap<>(SourceType.class);
        providers.put(SourceType.LORE, new LoreRetrievalProviderAdapter(
                java.util.Objects.requireNonNull(lore, "lore")));
        if (memory != null) providers.put(SourceType.MEMORY, new MemoryRetrievalProviderAdapter(memory));
        if (web != null) providers.put(SourceType.WEB, new WebSearchRetrievalProviderAdapter(web));
        KnowledgeGraphRetrievalService graph = new KnowledgeGraphRetrievalService(bridge, bridge, bridge);
        RetrievalRuntime runtime = new RetrievalRuntime(
                new DefaultRetrievalPlanner(), new RetrievalGate(), providers, bridge, graph,
                new BundleAssembler(), new InMemoryRetrievalTraceRepository(),
                java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor(),
                Duration.ofSeconds(2), Duration.ofMillis(500), true,
                RetrievalAuthorizationPolicy.allowAll());
        return new UnifiedRetrievalFacade(bridge, runtime);
    }

    public RetrievalBundle retrieve(Request request) {
        return retrieve(request, freezeForTurn(request.validAt(), request.tenantId()));
    }

    /** Freeze once at Turn acceptance; the same self-describing snapshot is reused on recovery. */
    public FrozenKnowledgeSnapshot freezeForTurn(Instant validAt, String tenantId) {
        return knowledge.freezeForNewTurn(
                validAt == null ? Instant.now() : validAt, tenantId);
    }

    public FrozenKnowledgeSnapshot restoreSnapshot(String snapshotId) {
        return knowledge.restoreSnapshot(snapshotId);
    }

    public RetrievalBundle retrieve(Request request, FrozenKnowledgeSnapshot snapshot) {
        java.util.Objects.requireNonNull(request, "request");
        java.util.Objects.requireNonNull(snapshot, "snapshot");
        long revision = snapshot.versions().stream()
                .map(FrozenKnowledgeSnapshot.VersionRef::publishedAt)
                .mapToLong(Instant::toEpochMilli).max().orElse(0L);
        if (!snapshot.frozenAt().equals(request.validAt())) {
            throw new IllegalArgumentException("retrieval validAt must equal the frozen snapshot time");
        }
        RetrievalContext context = new RetrievalContext(
                request.principalId(), request.aclScopes(), snapshot.snapshotId(), revision,
                request.validAt(), request.deadline(), request.traceId(), request.tenantId(),
                request.runtimeState(), request.turnRequest());
        return runtime.retrieve(request.query(), request.mode(), context);
    }

    @Override
    public void close() {
        runtime.close();
    }

    public record Request(
            String query, RetrievalMode mode, String tenantId, String principalId,
            Set<String> aclScopes, Instant validAt, Instant deadline, String traceId,
            com.meguri.core.dto.RuntimeState runtimeState,
            com.meguri.core.dto.TurnRequest turnRequest) {
        public Request(
                String query, RetrievalMode mode, String tenantId, String principalId,
                Set<String> aclScopes, Instant validAt, Instant deadline, String traceId) {
            this(query, mode, tenantId, principalId, aclScopes, validAt, deadline,
                    traceId, null, null);
        }
        public Request {
            if (query == null || query.isBlank() || tenantId == null || tenantId.isBlank()
                    || principalId == null || principalId.isBlank()) {
                throw new IllegalArgumentException("query, tenantId and principalId are required");
            }
            mode = mode == null ? RetrievalMode.FAST : mode;
            aclScopes = aclScopes == null ? Set.of() : Set.copyOf(aclScopes);
            validAt = validAt == null ? Instant.now() : validAt;
            deadline = deadline == null ? validAt.plusSeconds(2) : deadline;
            traceId = traceId == null || traceId.isBlank()
                    ? UUID.randomUUID().toString() : traceId.trim();
        }
    }
}

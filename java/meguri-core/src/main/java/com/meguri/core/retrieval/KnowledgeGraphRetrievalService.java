package com.meguri.core.retrieval;

import com.meguri.core.retrieval.GraphVisibilityPort.ResourceKind;
import com.meguri.core.retrieval.GraphVisibilityPort.Visibility;
import com.meguri.core.retrieval.KnowledgeGraphStorePort.EvidenceChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Bounded graph retrieval with fail-closed path evidence validation. */
public final class KnowledgeGraphRetrievalService {
    private final EntityAliasResolverPort aliases;
    private final KnowledgeGraphStorePort store;
    private final GraphVisibilityPort visibility;

    public KnowledgeGraphRetrievalService(
            EntityAliasResolverPort aliases,
            KnowledgeGraphStorePort store,
            GraphVisibilityPort visibility) {
        this.aliases = java.util.Objects.requireNonNull(aliases);
        this.store = java.util.Objects.requireNonNull(store);
        this.visibility = java.util.Objects.requireNonNull(visibility);
    }

    public GraphRetrievalResult retrieve(
            RetrievalPlan plan, RetrievalContext context, int limit) {
        if (!plan.graphEnabled()) {
            return new GraphRetrievalResult(
                    GraphRetrievalResult.Status.DISABLED, List.of(), List.of("graph_disabled"));
        }
        int hops = plan.graphMaxHops();
        if (hops < 1 || hops > 3) {
            return fallback("graph_hops_rejected");
        }
        try {
            List<EntityAliasResolverPort.EntityResolution> resolved = aliases.resolve(
                    plan.query().rewrittenQuery(), plan.query().entityMentions(), context);
            List<String> entityIds = resolved == null ? List.of() : resolved.stream()
                    .sorted(Comparator
                            .comparingDouble(EntityAliasResolverPort.EntityResolution::confidence)
                            .reversed()
                            .thenComparing(EntityAliasResolverPort.EntityResolution::entityId))
                    .map(EntityAliasResolverPort.EntityResolution::entityId)
                    .distinct()
                    .toList();
            if (entityIds.isEmpty()) return fallback("graph_entity_unresolved");
            if (entityIds.stream().anyMatch(id ->
                    visibility.visibility(ResourceKind.ENTITY, id, context) != Visibility.VISIBLE)) {
                return fallback("graph_entity_visibility_mismatch");
            }

            List<GraphPath> paths = store.findPaths(entityIds, hops, limit, context);
            if (paths == null) paths = List.of();
            List<RetrievalItem> items = new ArrayList<>();
            LinkedHashSet<String> rejected = new LinkedHashSet<>();
            List<GraphPath> orderedPaths = paths.stream()
                    .sorted(Comparator.comparingDouble(GraphPath::score).reversed()
                            .thenComparing(value -> String.join("/", value.nodeIds())))
                    .toList();
            boolean intentMismatch = false;
            for (int pathIndex = 0; pathIndex < orderedPaths.size(); pathIndex++) {
                if (items.size() >= limit) break;
                if (!matchesIntent(orderedPaths.get(pathIndex), plan.query())) {
                    intentMismatch = true;
                    continue;
                }
                Optional<RetrievalItem> item = validateAndExpand(
                        orderedPaths.get(pathIndex), pathIndex + 1, context, rejected);
                item.ifPresent(items::add);
            }
            if (intentMismatch) rejected.add("graph_relation_intent_mismatch");
            if (items.isEmpty()) {
                String reason = rejected.isEmpty()
                        ? "graph_no_paths" : rejected.iterator().next();
                return fallback(reason);
            }
            return new GraphRetrievalResult(
                    GraphRetrievalResult.Status.SUCCESS, items, List.copyOf(rejected));
        } catch (RuntimeException error) {
            return fallback(error instanceof java.util.concurrent.CancellationException
                    ? "graph_timeout" : "graph_failure");
        }
    }

    private static boolean matchesIntent(GraphPath path, QueryRewriteResult query) {
        return switch (query.graphIntent()) {
            case NONE -> true;
            case RELATION -> query.relationType().isBlank()
                    || path.edges().stream().allMatch(edge ->
                            normalize(edge.relation()).equals(query.relationType()));
            case TIMELINE -> path.edges().stream().allMatch(edge ->
                    TIMELINE_RELATIONS.contains(normalize(edge.relation())));
            case CALL_CHAIN -> path.edges().stream().allMatch(edge ->
                    CALL_CHAIN_RELATIONS.contains(normalize(edge.relation())));
        };
    }

    private static final java.util.Set<String> TIMELINE_RELATIONS = java.util.Set.of(
            "before", "after", "precedes", "follows", "happened_before", "happened_after",
            "occurred_before", "occurred_after", "chronological_next", "timeline_next");
    private static final java.util.Set<String> CALL_CHAIN_RELATIONS = java.util.Set.of(
            "calls", "invokes", "triggers", "delegates_to", "dispatches_to", "publishes_to");

    private static String normalize(String relation) {
        return relation.trim().toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", "_")
                .replaceAll("^_+|_+$", "");
    }

    private Optional<RetrievalItem> validateAndExpand(
            GraphPath path, int structuredRank,
            RetrievalContext context, LinkedHashSet<String> rejected) {
        if (path.hops() < 1 || path.hops() > 3) {
            rejected.add("graph_path_hops_rejected");
            return Optional.empty();
        }
        for (String node : path.nodeIds()) {
            if (visibility.visibility(ResourceKind.ENTITY, node, context) != Visibility.VISIBLE) {
                rejected.add("graph_path_entity_visibility_mismatch");
                return Optional.empty();
            }
        }
        LinkedHashSet<String> evidenceIds = new LinkedHashSet<>();
        for (GraphPath.Edge edge : path.edges()) {
            if (visibility.visibility(ResourceKind.EDGE, edge.edgeId(), context) != Visibility.VISIBLE) {
                rejected.add("graph_edge_visibility_mismatch");
                return Optional.empty();
            }
            if (edge.evidenceChunkIds().isEmpty()) {
                rejected.add("graph_evidence_missing");
                return Optional.empty();
            }
            evidenceIds.addAll(edge.evidenceChunkIds());
        }
        Map<String, EvidenceChunk> chunks = store.expandEvidence(evidenceIds, context);
        if (chunks == null || !chunks.keySet().containsAll(evidenceIds)) {
            rejected.add("graph_evidence_missing");
            return Optional.empty();
        }
        for (String evidenceId : evidenceIds) {
            if (visibility.visibility(ResourceKind.EVIDENCE_CHUNK, evidenceId, context)
                    != Visibility.VISIBLE) {
                rejected.add("graph_evidence_visibility_mismatch");
                return Optional.empty();
            }
        }

        List<EvidenceChunk> ordered = evidenceIds.stream().map(chunks::get).toList();
        String content = ordered.stream().map(EvidenceChunk::content)
                .distinct().reduce((left, right) -> left + "\n" + right).orElseThrow();
        RetrievalCitation citation = RetrievalCitation.merge(
                ordered.stream().map(EvidenceChunk::citation).toList());
        double trust = ordered.stream().mapToDouble(EvidenceChunk::trust).min().orElse(0);
        int tokenCount = ordered.stream().mapToInt(EvidenceChunk::tokenCount).sum();
        java.time.Instant validAt = ordered.stream().map(EvidenceChunk::validAt)
                .min(java.time.Instant::compareTo).orElse(context.validAt());
        String sourceId = "graph:" + String.join("->", path.nodeIds());
        RankTrace trace = new RankTrace(
                Map.of(RankSignal.STRUCTURED, structuredRank), 1.0 / (60 + structuredRank));
        return Optional.of(new RetrievalItem(
                SourceType.KNOWLEDGE, sourceId, content, citation, trust, trace,
                tokenCount, validAt, path, List.copyOf(evidenceIds), List.of(), context.traceId()));
    }

    private static GraphRetrievalResult fallback(String degradation) {
        return new GraphRetrievalResult(
                GraphRetrievalResult.Status.FALLBACK, List.of(), List.of(degradation));
    }
}

package com.meguri.core.retrieval;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Durable trace projection containing decisions and provenance, never retrieved
 * content or query text. It supports diagnostic replay with digest markers.
 */
public record RetrievalTraceProjection(
        String projectionVersion,
        String traceId,
        Plan plan,
        String snapshotId,
        long revision,
        Instant validAt,
        String algorithmRevision,
        Instant completedAt,
        List<Item> items,
        Map<SourceType, Lane> lanes,
        Map<String, RankTrace> ranks,
        List<Candidate> candidates,
        List<String> degradations) {
    public static final String VERSION = "safe-retrieval-trace-v1";
    private static final String DIGEST_PREFIX = "sha256:";
    private static final Pattern SAFE_METADATA =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

    public RetrievalTraceProjection {
        projectionVersion = projectionVersion == null ? VERSION : projectionVersion;
        items = items == null ? List.of() : List.copyOf(items);
        lanes = lanes == null ? Map.of() : Map.copyOf(lanes);
        ranks = ranks == null ? Map.of() : Map.copyOf(ranks);
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        degradations = degradations == null ? List.of() : List.copyOf(degradations);
    }

    public static RetrievalTraceProjection capture(RetrievalTrace trace) {
        List<Item> safeItems = trace.items().stream().map(Item::capture).toList();
        EnumMap<SourceType, Lane> safeLanes = new EnumMap<>(SourceType.class);
        trace.lanes().forEach((source, lane) -> safeLanes.put(source,
                new Lane(lane.status(), safeMetadata(lane.provider()),
                        lane.items().stream().map(Item::capture).toList(),
                        safeMetadata(lane.degradations()))));
        Map<String, RankTrace> safeRanks = new LinkedHashMap<>();
        trace.ranks().forEach((sourceId, rank) ->
                safeRanks.put(safeMetadata(sourceId), rank));
        return new RetrievalTraceProjection(VERSION, trace.traceId(),
                Plan.capture(trace.plan()), trace.snapshotId(), trace.revision(),
                trace.validAt(), trace.algorithmRevision(), trace.completedAt(),
                safeItems, safeLanes, safeRanks,
                trace.candidates().stream().map(Candidate::capture).toList(),
                safeMetadata(trace.degradations()));
    }

    /** Replays ordering and decisions without reconstructing sensitive content. */
    public RetrievalTrace toRedactedTrace() {
        List<RetrievalItem> redactedItems = items.stream()
                .map(Item::toRedactedItem).toList();
        EnumMap<SourceType, RetrievalLaneResult> redactedLanes =
                new EnumMap<>(SourceType.class);
        lanes.forEach((source, lane) -> redactedLanes.put(source,
                new RetrievalLaneResult(source, lane.status(), lane.provider(),
                        lane.items().stream().map(Item::toRedactedItem).toList(),
                        lane.degradations())));
        return new RetrievalTrace(traceId, plan.toRedactedPlan(), snapshotId,
                revision, validAt, algorithmRevision, completedAt,
                redactedItems, redactedLanes, ranks,
                candidates.stream().map(Candidate::toTrace).toList(),
                degradations);
    }

    public record Plan(
            RetrievalMode mode,
            String queryDigest,
            Set<SourceType> sources,
            Map<SourceType, Integer> sourceBudgets,
            Map<SourceType, Integer> sourceSeats,
            boolean graphEnabled,
            int graphMaxHops,
            int totalItemLimit,
            Instant deadline) {
        private static Plan capture(RetrievalPlan plan) {
            return new Plan(plan.mode(), digest(plan.query().originalQuery()
                    + "\u0000" + plan.query().rewrittenQuery()), plan.sources(),
                    plan.sourceBudgets(), plan.sourceSeats(), plan.graphEnabled(),
                    plan.graphMaxHops(), plan.totalItemLimit(), plan.deadline());
        }

        private RetrievalPlan toRedactedPlan() {
            String marker = DIGEST_PREFIX + queryDigest;
            return new RetrievalPlan(mode, QueryRewriteResult.unchanged(marker),
                    sources, sourceBudgets, sourceSeats, graphEnabled,
                    graphMaxHops, totalItemLimit, deadline);
        }
    }

    public record Item(
            SourceType sourceType,
            String sourceId,
            String contentDigest,
            RetrievalCitation citation,
            double trust,
            RankTrace rankTrace,
            int tokenCount,
            Instant validAt,
            GraphPath graphPath,
            List<String> evidenceChunkIds,
            List<String> degradations,
            String traceId) {
        private static Item capture(RetrievalItem item) {
            return new Item(item.sourceType(), safeMetadata(item.sourceId()),
                    digest(item.content()), safeCitation(item.citation()), item.trust(),
                    item.rankTrace(), item.tokenCount(), item.validAt(),
                    safeGraphPath(item.graphPath()),
                    safeMetadata(item.evidenceChunkIds()),
                    safeMetadata(item.degradations()),
                    safeMetadata(item.traceId()));
        }

        private RetrievalItem toRedactedItem() {
            return new RetrievalItem(sourceType, sourceId,
                    DIGEST_PREFIX + contentDigest, citation, trust, rankTrace,
                    tokenCount, validAt, graphPath, evidenceChunkIds,
                    degradations, traceId);
        }

    }

    public record Candidate(
            SourceType sourceType,
            String sourceId,
            int sourceRank,
            RankTrace rankTrace,
            RetrievalCitation citation,
            boolean selected,
            String decisionReason) {
        private static Candidate capture(RetrievalCandidateTrace candidate) {
            return new Candidate(candidate.sourceType(),
                    safeMetadata(candidate.sourceId()), candidate.sourceRank(),
                    candidate.rankTrace(), safeCitation(candidate.citation()),
                    candidate.selected(), safeMetadata(candidate.decisionReason()));
        }

        private RetrievalCandidateTrace toTrace() {
            return new RetrievalCandidateTrace(sourceType, sourceId, sourceRank,
                    rankTrace, citation, selected, decisionReason);
        }
    }

    public record Lane(
            RetrievalLaneResult.Status status,
            String provider,
            List<Item> items,
            List<String> degradations) {
        public Lane {
            items = items == null ? List.of() : List.copyOf(items);
            degradations = degradations == null
                    ? List.of() : List.copyOf(degradations);
        }
    }

    private static RetrievalCitation safeCitation(RetrievalCitation citation) {
        if (citation == null || citation.isEmpty()) {
            return RetrievalCitation.empty();
        }
        return new RetrievalCitation(citation.anchors().stream()
                .map(anchor -> new RetrievalCitation.Anchor(
                        safeUri(anchor.canonicalUri()), "",
                        safeMetadata(anchor.documentVersionId()),
                        safeMetadata(anchor.chunkId()), anchor.sourceStart(),
                        anchor.sourceEnd()))
                .toList());
    }

    private static GraphPath safeGraphPath(GraphPath path) {
        if (path == null) return null;
        List<String> nodeIds = safeMetadata(path.nodeIds());
        List<GraphPath.Edge> edges = path.edges().stream()
                .map(edge -> new GraphPath.Edge(
                        safeMetadata(edge.edgeId()),
                        safeMetadata(edge.fromId()),
                        safeMetadata(edge.relation()),
                        safeMetadata(edge.toId()),
                        safeMetadata(edge.evidenceChunkIds()), edge.score()))
                .toList();
        return new GraphPath(nodeIds, edges, path.score());
    }

    private static List<String> safeMetadata(List<String> values) {
        return values == null ? List.of()
                : values.stream().map(RetrievalTraceProjection::safeMetadata)
                        .toList();
    }

    private static String safeMetadata(String value) {
        if (value != null && SAFE_METADATA.matcher(value).matches()) {
            return value;
        }
        return DIGEST_PREFIX + digest(value == null ? "" : value);
    }

    private static String safeUri(String value) {
        if (value == null || value.isBlank()) {
            return "urn:sha256:" + digest("");
        }
        String withoutQuery = value.strip().split("[?#]", 2)[0];
        if (!withoutQuery.isBlank() && withoutQuery.chars()
                .noneMatch(Character::isWhitespace)) {
            return withoutQuery;
        }
        return "urn:sha256:" + digest(value);
    }

    private static String digest(String value) {
        try {
            byte[] hashed = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}

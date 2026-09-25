package com.meguri.core.retrieval;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;

/** Fully typed evidence passed from retrieval to generation. */
public record RetrievalItem(
        SourceType sourceType,
        String sourceId,
        String content,
        RetrievalCitation citation,
        double trust,
        RankTrace rankTrace,
        int tokenCount,
        Instant validAt,
        GraphPath graphPath,
        List<String> evidenceChunkIds,
        List<String> degradations,
        String traceId) {
    public RetrievalItem {
        if (sourceType == null) throw new IllegalArgumentException("sourceType is required");
        if (blank(sourceId) || blank(content) || blank(traceId)) {
            throw new IllegalArgumentException("sourceId, content and traceId are required");
        }
        citation = citation == null ? RetrievalCitation.empty() : citation;
        if (!Double.isFinite(trust) || trust < 0 || trust > 1) {
            throw new IllegalArgumentException("trust must be between 0 and 1");
        }
        rankTrace = rankTrace == null ? RankTrace.empty() : rankTrace;
        if (tokenCount < 0) throw new IllegalArgumentException("tokenCount must be non-negative");
        validAt = validAt == null ? Instant.EPOCH : validAt;
        evidenceChunkIds = immutableDistinct(evidenceChunkIds);
        degradations = immutableDistinct(degradations);
    }

    public RetrievalItem(
            SourceType sourceType,
            String sourceId,
            String content,
            String citation,
            double trust,
            RankTrace rankTrace,
            int tokenCount,
            Instant validAt,
            GraphPath graphPath,
            List<String> evidenceChunkIds,
            List<String> degradations,
            String traceId) {
        this(sourceType, sourceId, content, RetrievalCitation.external(citation), trust,
                rankTrace, tokenCount, validAt, graphPath, evidenceChunkIds,
                degradations, traceId);
    }

    public RetrievalItem withRankTrace(RankTrace trace) {
        return new RetrievalItem(sourceType, sourceId, content, citation, trust, trace,
                tokenCount, validAt, graphPath, evidenceChunkIds, degradations, traceId);
    }

    public RetrievalItem withDegradation(String degradation) {
        LinkedHashSet<String> values = new LinkedHashSet<>(degradations);
        if (degradation != null && !degradation.isBlank()) values.add(degradation);
        return new RetrievalItem(sourceType, sourceId, content, citation, trust, rankTrace,
                tokenCount, validAt, graphPath, evidenceChunkIds, List.copyOf(values), traceId);
    }

    private static List<String> immutableDistinct(List<String> values) {
        if (values == null) return List.of();
        return List.copyOf(new LinkedHashSet<>(values));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}

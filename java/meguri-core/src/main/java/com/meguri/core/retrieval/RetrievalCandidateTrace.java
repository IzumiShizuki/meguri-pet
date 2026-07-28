package com.meguri.core.retrieval;

/** One source-local candidate and the deterministic bundle decision applied to it. */
public record RetrievalCandidateTrace(
        SourceType sourceType,
        String sourceId,
        int sourceRank,
        RankTrace rankTrace,
        RetrievalCitation citation,
        boolean selected,
        String decisionReason) {

    public RetrievalCandidateTrace {
        if (sourceType == null || sourceId == null || sourceId.isBlank()) {
            throw new IllegalArgumentException("candidate source and sourceId are required");
        }
        if (sourceRank < 1) {
            throw new IllegalArgumentException("candidate sourceRank must be positive");
        }
        rankTrace = rankTrace == null ? RankTrace.empty() : rankTrace;
        citation = citation == null ? RetrievalCitation.empty() : citation;
        if (decisionReason == null || decisionReason.isBlank()) {
            throw new IllegalArgumentException("candidate decisionReason is required");
        }
        decisionReason = decisionReason.trim();
    }
}

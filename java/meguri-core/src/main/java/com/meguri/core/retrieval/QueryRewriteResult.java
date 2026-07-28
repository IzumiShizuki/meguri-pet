package com.meguri.core.retrieval;

import java.util.List;

/** A deterministic rewrite result; no model invocation is implied by this type. */
public record QueryRewriteResult(String originalQuery, String rewrittenQuery,
                                 List<String> entityMentions, boolean relationshipQuestion,
                                 String relationType, GraphIntent graphIntent) {
    public enum GraphIntent {
        NONE,
        RELATION,
        TIMELINE,
        CALL_CHAIN
    }

    public QueryRewriteResult {
        if (originalQuery == null || originalQuery.isBlank()) {
            throw new IllegalArgumentException("originalQuery is required");
        }
        rewrittenQuery = rewrittenQuery == null || rewrittenQuery.isBlank()
                ? originalQuery.trim() : rewrittenQuery.trim();
        entityMentions = entityMentions == null ? List.of() : List.copyOf(entityMentions);
        relationType = normalizeRelationType(relationType);
        graphIntent = graphIntent == null
                ? (relationshipQuestion ? GraphIntent.RELATION : GraphIntent.NONE)
                : graphIntent;
        relationshipQuestion = relationshipQuestion || graphIntent != GraphIntent.NONE;
    }

    /** Compatibility constructor for callers that only distinguished relationship questions. */
    public QueryRewriteResult(String originalQuery, String rewrittenQuery,
                              List<String> entityMentions, boolean relationshipQuestion) {
        this(originalQuery, rewrittenQuery, entityMentions, relationshipQuestion, "",
                relationshipQuestion ? GraphIntent.RELATION : GraphIntent.NONE);
    }

    public static QueryRewriteResult unchanged(String query) {
        return new QueryRewriteResult(query, query, List.of(), false, "", GraphIntent.NONE);
    }

    private static String normalizeRelationType(String value) {
        if (value == null || value.isBlank()) return "";
        return value.trim().toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", "_")
                .replaceAll("^_+|_+$", "");
    }
}

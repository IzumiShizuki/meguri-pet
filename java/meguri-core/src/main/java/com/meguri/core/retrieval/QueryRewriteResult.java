package com.meguri.core.retrieval;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** A deterministic rewrite result; no model invocation is implied by this type. */
public record QueryRewriteResult(String originalQuery, String rewrittenQuery,
                                 List<String> entityMentions, boolean relationshipQuestion,
                                 String relationType, GraphIntent graphIntent,
                                 Map<SourceType, List<String>> sourceQueries) {
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
        EnumMap<SourceType, List<String>> queries = new EnumMap<>(SourceType.class);
        if (sourceQueries != null) {
            sourceQueries.forEach((source, values) -> {
                if (source == null || values == null) return;
                List<String> normalized = values.stream()
                        .filter(value -> value != null && !value.isBlank())
                        .map(String::trim).distinct().toList();
                if (!normalized.isEmpty()) queries.put(source, normalized);
            });
        }
        sourceQueries = Map.copyOf(queries);
    }

    public QueryRewriteResult(String originalQuery, String rewrittenQuery,
                              List<String> entityMentions, boolean relationshipQuestion,
                              String relationType, GraphIntent graphIntent) {
        this(originalQuery, rewrittenQuery, entityMentions, relationshipQuestion,
                relationType, graphIntent, Map.of());
    }

    /** Compatibility constructor for callers that only distinguished relationship questions. */
    public QueryRewriteResult(String originalQuery, String rewrittenQuery,
                              List<String> entityMentions, boolean relationshipQuestion) {
        this(originalQuery, rewrittenQuery, entityMentions, relationshipQuestion, "",
                relationshipQuestion ? GraphIntent.RELATION : GraphIntent.NONE, Map.of());
    }

    public static QueryRewriteResult unchanged(String query) {
        return new QueryRewriteResult(query, query, List.of(), false, "", GraphIntent.NONE,
                Map.of());
    }

    public String queryFor(SourceType source) {
        return sourceQueries.getOrDefault(source, List.of(rewrittenQuery)).getFirst();
    }

    private static String normalizeRelationType(String value) {
        if (value == null || value.isBlank()) return "";
        return value.trim().toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", "_")
                .replaceAll("^_+|_+$", "");
    }
}

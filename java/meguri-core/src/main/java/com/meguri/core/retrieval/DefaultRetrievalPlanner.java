package com.meguri.core.retrieval;

import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Cheap deterministic planning. Relationship questions opt into Graph without an LLM call. */
public final class DefaultRetrievalPlanner implements RetrievalPlanner {
    private static final Pattern CALL_CHAIN = Pattern.compile(
            "(?i)(call chain|invocation chain|calls?|invokes?|triggers?|"
                    + "\u8c03\u7528\u94fe|\u8c03\u7528|\u89e6\u53d1\u94fe|\u8c01\u8c03\u7528)");
    private static final Pattern TIMELINE = Pattern.compile(
            "(?i)(timeline|chronolog|happened before|happened after|before|after|"
                    + "\u65f6\u95f4\u7ebf|\u65f6\u5e8f|\u5148\u540e|"
                    + "\u4e4b\u524d|\u4e4b\u540e|\u6f14\u53d8)");
    private static final Pattern RELATIONSHIP = Pattern.compile(
            "(?i)(relationship|related to|connected to|depends on|works with|belongs to|"
                    + "\u5173\u7cfb|\u5173\u8054|\u8fde\u63a5|\u4f9d\u8d56|"
                    + "\u5c5e\u4e8e|\u5408\u4f5c|\u8c01\u548c\u8c01)");
    private final RetrievalGate gate;

    public DefaultRetrievalPlanner() {
        this(new RetrievalGate());
    }

    public DefaultRetrievalPlanner(RetrievalGate gate) {
        this.gate = gate;
    }

    @Override
    public RetrievalPlan plan(String query, RetrievalMode mode, Instant deadline) {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("query is required");
        mode = mode == null ? RetrievalMode.NONE : mode;
        if (mode == RetrievalMode.NONE) {
            return gate.validate(new RetrievalPlan(mode, QueryRewriteResult.unchanged(query),
                    Set.of(), Map.of(), Map.of(), false, 1, 0, deadline));
        }
        boolean relationship = RELATIONSHIP.matcher(query.toLowerCase(Locale.ROOT)).find();
        QueryRewriteResult.GraphIntent graphIntent = graphIntent(query, relationship);
        String relationType = relationType(query);
        boolean graphQuestion = graphIntent != QueryRewriteResult.GraphIntent.NONE;
        QueryRewriteResult rewrite = new QueryRewriteResult(
                query, query.trim(), java.util.List.of(), graphQuestion,
                relationType, graphIntent);
        EnumSet<SourceType> sources = EnumSet.of(
                SourceType.LORE, SourceType.MEMORY, SourceType.KNOWLEDGE);
        if (mode == RetrievalMode.SLOW) sources.add(SourceType.WEB);
        EnumMap<SourceType, java.util.List<String>> sourceQueries =
                new EnumMap<>(SourceType.class);
        sources.forEach(source -> sourceQueries.put(source, java.util.List.of(query.trim())));
        rewrite = new QueryRewriteResult(
                rewrite.originalQuery(), rewrite.rewrittenQuery(), rewrite.entityMentions(),
                rewrite.relationshipQuestion(), rewrite.relationType(), rewrite.graphIntent(),
                sourceQueries);
        EnumMap<SourceType, Integer> budgets = new EnumMap<>(SourceType.class);
        EnumMap<SourceType, Integer> seats = new EnumMap<>(SourceType.class);
        for (SourceType source : sources) {
            budgets.put(source, source == SourceType.KNOWLEDGE ? 8 : 6);
            seats.put(source, 1);
        }
        return gate.validate(new RetrievalPlan(mode, rewrite, sources, budgets, seats,
                graphQuestion, 3, 12, deadline));
    }

    private static QueryRewriteResult.GraphIntent graphIntent(
            String query, boolean relationship) {
        if (CALL_CHAIN.matcher(query).find()) return QueryRewriteResult.GraphIntent.CALL_CHAIN;
        if (TIMELINE.matcher(query).find()) return QueryRewriteResult.GraphIntent.TIMELINE;
        return relationship
                ? QueryRewriteResult.GraphIntent.RELATION
                : QueryRewriteResult.GraphIntent.NONE;
    }

    private static String relationType(String query) {
        String normalized = query.toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "depends on", "\u4f9d\u8d56")) return "depends_on";
        if (containsAny(normalized, "belongs to", "\u5c5e\u4e8e")) return "belongs_to";
        if (containsAny(normalized, "works with", "cooperates with", "\u5408\u4f5c")) {
            return "works_with";
        }
        if (containsAny(normalized, "connected to", "\u8fde\u63a5")) return "connected_to";
        if (containsAny(normalized, "calls", "call ", "invokes", "\u8c03\u7528")) {
            return "calls";
        }
        return "";
    }

    private static boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) return true;
        }
        return false;
    }
}

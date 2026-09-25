package com.meguri.core.context;

import com.meguri.core.runtime.SessionContextStore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Conservative offline strategy that never invents facts outside source messages. */
public final class DeterministicContextRefactoringStrategy implements ContextRefactoringStrategy {
    private static final Set<String> CONSTRAINT_CUES = Set.of(
            "must", "need", "should", "不要", "不能", "必须", "要求", "改成", "换成", "remember");
    private static final Set<String> DECISION_CUES = Set.of(
            "decide", "selected", "choose", "决定", "确定", "选择", "采用", "改为", "换成");
    private static final Set<String> OPEN_CUES = Set.of(
            "未确定", "待定", "还没", "later", "todo", "?", "？", "unknown");

    private final int maximumSummaryCharacters;
    private final int maximumFacts;

    public DeterministicContextRefactoringStrategy(int maximumSummaryCharacters) {
        if (maximumSummaryCharacters < 1) {
            throw new IllegalArgumentException("maximumSummaryCharacters must be positive");
        }
        this.maximumSummaryCharacters = maximumSummaryCharacters;
        this.maximumFacts = Math.max(1, maximumSummaryCharacters / 200);
    }

    @Override
    public String revision() {
        return DEFAULT_REVISION;
    }

    @Override
    public Output refactor(Input input) {
        List<SessionContextStore.MessageNode> source = input.sourceMessages();
        if (source.isEmpty()) {
            return new Output(StructuredContextSummary.builder("").build(), "");
        }

        int start = Math.max(0, source.size() - maximumFacts);
        List<SessionContextStore.MessageNode> selected = source.subList(start, source.size());
        StructuredContextSummary.Builder builder = StructuredContextSummary.builder(
                "Earlier conversation: " + selected.size() + " source messages");
        for (SessionContextStore.MessageNode dropped : source.subList(0, start)) {
            builder.operation(new StructuredContextSummary.Operation(
                    StructuredContextSummary.OperationType.DROP,
                    List.of(), List.of(dropped.messageId()), null));
        }
        List<StructuredContextSummary.Fact> facts = new ArrayList<>();
        String latestFactId = null;
        for (SessionContextStore.MessageNode message : selected) {
            String text = message.content() == null ? "" : message.content().strip();
            if (text.isBlank()) continue;
            String factId = factId(message.messageId());
            StructuredContextSummary.Importance importance = isConstraint(text)
                    ? StructuredContextSummary.Importance.HIGH
                    : StructuredContextSummary.Importance.NORMAL;
            StructuredContextSummary.Fact fact = new StructuredContextSummary.Fact(
                    factId, bounded(message.role() + ": " + text, 1_200),
                    List.of(message.messageId()), importance,
                    StructuredContextSummary.FactStatus.ACTIVE, List.of());
            facts.add(fact);
            builder.fact(fact);
            latestFactId = factId;
            if ("user".equalsIgnoreCase(message.role())) builder.exactItem(factId);
            if (isConstraint(text)) builder.constraint(factId);
            if (containsCue(text, DECISION_CUES)) builder.decision(factId);
            if (containsCue(text, OPEN_CUES)) builder.openThread(factId);
            builder.currentState(factId);
            builder.operation(new StructuredContextSummary.Operation(
                    "user".equalsIgnoreCase(message.role())
                            ? StructuredContextSummary.OperationType.KEEP_EXACT
                            : StructuredContextSummary.OperationType.COMPRESS,
                    List.of(factId), List.of(message.messageId()), factId));
        }
        if (latestFactId == null) {
            return new Output(StructuredContextSummary.builder("").build(), "");
        }
        StructuredContextSummary structured = builder.build();
        return new Output(structured, compactProjection(structured, facts));
    }

    private String compactProjection(StructuredContextSummary structured,
                                     List<StructuredContextSummary.Fact> facts) {
        StringBuilder result = new StringBuilder();
        if (!structured.gist().isBlank()) result.append(structured.gist()).append('\n');
        appendCategory(result, "Current state", structured.currentState(), facts);
        appendCategory(result, "Decisions", structured.decisions(), facts);
        appendCategory(result, "Constraints", structured.constraints(), facts);
        appendCategory(result, "Open threads", structured.openThreads(), facts);
        appendCategory(result, "Exact items", structured.exactItems(), facts);
        return bounded(result.toString().strip(), maximumSummaryCharacters);
    }

    private static void appendCategory(StringBuilder target, String label, List<String> ids,
                                       List<StructuredContextSummary.Fact> facts) {
        if (ids.isEmpty()) return;
        target.append(label).append(':').append('\n');
        for (String id : ids) {
            facts.stream().filter(fact -> fact.factId().equals(id)).findFirst()
                    .ifPresent(fact -> target.append("- ").append(fact.text()).append('\n'));
        }
    }

    private static boolean isConstraint(String text) {
        return containsCue(text, CONSTRAINT_CUES);
    }

    private static boolean containsCue(String text, Set<String> cues) {
        String normalized = text.toLowerCase(Locale.ROOT);
        return cues.stream().anyMatch(normalized::contains);
    }

    private static String bounded(String value, int maximum) {
        if (value.length() <= maximum) return value;
        return value.substring(0, Math.max(1, maximum - 1)) + "…";
    }

    private static String factId(String messageId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(messageId.getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder("fact_");
            for (int i = 0; i < 8; i++) value.append(String.format("%02x", digest[i]));
            return value.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}

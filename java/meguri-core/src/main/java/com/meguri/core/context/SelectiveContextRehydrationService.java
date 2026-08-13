package com.meguri.core.context;

import com.meguri.core.llm.ProviderTokenizer;
import com.meguri.core.runtime.SessionContextStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Deterministic fact ranking and bounded active-path source recovery for THINK turns. */
public final class SelectiveContextRehydrationService {
    private static final Pattern WORDS = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final Set<String> UNCERTAINTY_CUES = Set.of(
            "exact", "value", "reason", "why", "change", "changed", "old", "previous",
            "path", "code", "quote", "time", "when", "精确", "原因", "变更", "旧", "原话", "路径", "时间");
    private static final Set<String> STOPWORDS = Set.of(
            "the", "a", "an", "is", "are", "was", "were", "to", "of", "and", "or", "for",
            "in", "on", "at", "we", "i", "you", "it", "this", "that", "what", "did", "do",
            "了", "的", "是", "我", "你", "这", "那", "和", "与", "吗", "呢");

    private final ProviderTokenizer tokenizer;
    private final ContextRehydrationService windows;

    public SelectiveContextRehydrationService(ProviderTokenizer tokenizer) {
        this.tokenizer = tokenizer;
        this.windows = new ContextRehydrationService();
    }

    public List<Selection> select(
            SessionContextStore.GraphSnapshot graph,
            List<SessionContextStore.DerivedSummary> summaries,
            String currentInput,
            RehydrationPolicy policy) {
        if (!policy.enabled() || summaries == null || summaries.isEmpty()
                || currentInput == null || currentInput.isBlank()) return List.of();

        Set<String> activeIds = graph.activePath().stream()
                .map(SessionContextStore.MessageNode::messageId).collect(java.util.stream.Collectors.toSet());
        List<ScoredFact> ranked = new ArrayList<>();
        for (SessionContextStore.DerivedSummary summary : summaries) {
            StructuredContextSummary structured = summary.structured();
            if (structured == null) continue;
            for (StructuredContextSummary.Fact fact : structured.facts()) {
                if (fact.status() != StructuredContextSummary.FactStatus.ACTIVE
                        || !activeIds.containsAll(fact.sourceIds())) continue;
                Score score = score(currentInput, fact);
                if (score.value() <= 0d) continue;
                ranked.add(new ScoredFact(fact, score, summary.summaryId()));
            }
        }
        ranked.sort(Comparator.comparingDouble((ScoredFact item) -> item.score.value()).reversed()
                .thenComparing(item -> item.fact.factId()));

        List<Selection> result = new ArrayList<>();
        Map<String, Selection> bySource = new LinkedHashMap<>();
        int totalTokens = 0;
        for (ScoredFact scored : ranked) {
            if (result.stream().map(Selection::factId).distinct().count() >= policy.maxFacts()) break;
            String sourceId = latestActiveSource(scored.fact.sourceIds(), graph.activePath());
            if (sourceId == null) continue;
            ContextRehydrationService.RehydratedWindow window = windows.rehydrateAutomatic(
                    graph, scored.fact.factId(), sourceId, policy.messagesBefore(), policy.messagesAfter());
            if (window == null) continue;
            Selection existing = bySource.get(sourceId);
            if (existing == null) {
                existing = bySource.values().stream()
                        .filter(selection -> overlaps(selection.window(), window)).findFirst().orElse(null);
            }
            if (existing != null) {
                result.add(new Selection(scored.fact.factId(), scored.fact.sourceIds(),
                        scored.score.value(), scored.score.reason() + "; deduplicated source span",
                        existing.window(), existing.content(), existing.tokenCount(), scored.summaryId()));
                continue;
            }
            if (bySource.size() >= policy.maxSourceSpans()) break;
            String content = render(window.messages());
            int available = policy.maxTokens() - totalTokens;
            if (available <= 0) break;
            int tokenCount = tokenizer.count(content);
            if (tokenCount > available) {
                content = truncate(content, available);
                tokenCount = tokenizer.count(content);
            }
            if (tokenCount <= 0) continue;
            Selection selection = new Selection(scored.fact.factId(), scored.fact.sourceIds(),
                    scored.score.value(), scored.score.reason(), window, content, tokenCount,
                    scored.summaryId());
            bySource.put(sourceId, selection);
            result.add(selection);
            totalTokens += tokenCount;
        }
        return List.copyOf(result);
    }

    private static boolean overlaps(
            ContextRehydrationService.RehydratedWindow left,
            ContextRehydrationService.RehydratedWindow right) {
        Set<String> leftIds = left.messages().stream()
                .map(SessionContextStore.MessageNode::messageId).collect(java.util.stream.Collectors.toSet());
        return right.messages().stream()
                .map(SessionContextStore.MessageNode::messageId).anyMatch(leftIds::contains);
    }

    private String truncate(String content, int maxTokens) {
        String candidate = tokenizer.truncate(content, maxTokens);
        if (tokenizer.count(candidate) <= maxTokens) return candidate;
        int low = 0;
        int high = content.length();
        while (low < high) {
            int middle = (low + high + 1) / 2;
            if (tokenizer.count(content.substring(0, middle)) <= maxTokens) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return content.substring(0, low);
    }

    private String render(List<SessionContextStore.MessageNode> messages) {
        StringBuilder content = new StringBuilder();
        for (SessionContextStore.MessageNode message : messages) {
            content.append(message.role()).append(": ").append(message.content()).append('\n');
        }
        return content.toString().strip();
    }

    private static String latestActiveSource(
            List<String> sourceIds, List<SessionContextStore.MessageNode> activePath) {
        Set<String> wanted = new HashSet<>(sourceIds);
        for (int index = activePath.size() - 1; index >= 0; index--) {
            String messageId = activePath.get(index).messageId();
            if (wanted.contains(messageId)) return messageId;
        }
        return null;
    }

    private static Score score(String input, StructuredContextSummary.Fact fact) {
        Set<String> inputTerms = terms(input);
        Set<String> factTerms = terms(fact.text());
        Set<String> overlap = new HashSet<>(inputTerms);
        overlap.retainAll(factTerms);
        if (overlap.isEmpty()) return new Score(0d, "no lexical overlap");
        double value = overlap.size() * 10d;
        if (fact.importance() == StructuredContextSummary.Importance.HIGH) value += 3d;
        Set<String> cues = new HashSet<>(inputTerms);
        cues.retainAll(UNCERTAINTY_CUES);
        if (!cues.isEmpty()) value += 2d;
        String reason = "lexical_overlap=" + overlap.size()
                + (fact.importance() == StructuredContextSummary.Importance.HIGH ? "; importance=HIGH" : "")
                + (cues.isEmpty() ? "" : "; uncertainty_cue=" + cues.stream().sorted().findFirst().orElse(""));
        return new Score(value, reason);
    }

    private static Set<String> terms(String value) {
        Set<String> result = new HashSet<>();
        if (value == null) return result;
        for (String token : WORDS.matcher(value.toLowerCase(Locale.ROOT))
                .results().map(match -> match.group()).toList()) {
            if (STOPWORDS.contains(token)) continue;
            result.add(token);
            if (token.length() > 1 && token.codePoints().allMatch(SelectiveContextRehydrationService::isCjk)) {
                token.codePoints().mapToObj(codePoint -> new String(Character.toChars(codePoint)))
                        .forEach(result::add);
            }
        }
        return result;
    }

    private static boolean isCjk(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }

    public record Selection(
            String factId,
            List<String> sourceIds,
            double score,
            String reason,
            ContextRehydrationService.RehydratedWindow window,
            String content,
            int tokenCount,
            String summaryId) {
        public Selection {
            sourceIds = sourceIds == null ? List.of() : List.copyOf(sourceIds);
            content = content == null ? "" : content;
        }
    }

    private record ScoredFact(
            StructuredContextSummary.Fact fact, Score score, String summaryId) { }

    private record Score(double value, String reason) { }
}

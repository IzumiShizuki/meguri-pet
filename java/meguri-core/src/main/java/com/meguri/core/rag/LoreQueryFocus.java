package com.meguri.core.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Bounded, literal topic spans for explicit canon questions, never generated rewrites. */
final class LoreQueryFocus {
    private static final Pattern CANON = Pattern.compile("(?:原作(?:剧情)?[中里]|角色语料[中里]|原作では|原作の)");
    private static final Pattern EXCLUSION = Pattern.compile(
            "(?:不要|不必|无需|禁止|别|停止|不)(?:再|对|从)?(?:原作|角色语料|语料)?(?:中|里)?(?:检索|召回|讨论|引用|讲述)"
                    + "|(?:不是|并非)(?:在|问|指|说|关于)?\\s*(?:原作|角色语料)"
                    + "|(?:do not|don't)\\s+(?:retrieve|recall|discuss|quote)"
                    + "|(?:検索|引用|話題に)しない", Pattern.CASE_INSENSITIVE);
    private static final Pattern META = Pattern.compile(
            "(?:只|仅)(?:需|要)?(?:分析|检查|改写|翻译|复述|评价)|(?:only translate|only analyze)", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNSAFE_FOCUS = Pattern.compile(
            "不|没|别|禁止|忽略|切换|设置|relationship|system|developer|指令|提示词|ではない", Pattern.CASE_INSENSITIVE);
    private static final String QUOTES = "\"'`“”‘’「」『』";

    private LoreQueryFocus() { }

    static boolean excluded(String query) {
        String visible = outsideQuotes(query);
        return EXCLUSION.matcher(visible).find()
                || (!visible.matches("(?s).*[\\p{L}\\p{N}].*") && hasQuotes(query))
                || (hasQuotes(query) && META.matcher(visible).find());
    }

    static List<String> candidates(String query) {
        // Ambiguous quotation, negative topics and oversized instructions get no
        // expansion. Ordinary full-query retrieval remains the first attempt.
        if (query == null || query.length() > 2000 || excluded(query) || hasQuotes(query)) return List.of();
        List<String> result = new ArrayList<>();
        var markers = CANON.matcher(query);
        while (markers.find() && result.size() < 2) {
            String rest = query.substring(markers.end()).replaceFirst("^[\\s，,:：]+", "");
            String focus = rest.split("[，,。！？!?；;\\r\\n]", 2)[0].trim()
                    .replaceFirst("(?:的(?:传言|情节|场景|剧情)|这(?:段|条)(?:传言|情节|剧情))$", "").trim();
            if (focus.length() < 6 || focus.length() > 80 || UNSAFE_FOCUS.matcher(focus).find()) continue;
            if (!result.contains(focus)) result.add(focus);
        }
        return List.copyOf(result);
    }

    private static boolean hasQuotes(String value) {
        return value != null && value.chars().anyMatch(c -> QUOTES.indexOf(c) >= 0);
    }

    private static String outsideQuotes(String value) {
        if (value == null) return "";
        StringBuilder result = new StringBuilder();
        char closing = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (closing != 0) {
                if (c == closing) closing = 0;
                result.append(' ');
            } else if (QUOTES.indexOf(c) >= 0
                    && !(c == '\'' && i > 0 && Character.isLetterOrDigit(value.charAt(i - 1)))) {
                closing = switch (c) { case '“' -> '”'; case '‘' -> '’'; case '「' -> '」'; case '『' -> '』'; default -> c; };
                result.append(' ');
            } else result.append(c);
        }
        return result.toString();
    }
}

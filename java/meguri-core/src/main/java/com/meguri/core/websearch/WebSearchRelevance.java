package com.meguri.core.websearch;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Conservative subject anchors, independent of result order and provider success status. */
final class WebSearchRelevance {
    private static final Set<String> STOP = Set.of("the", "a", "an", "of", "and", "or", "for", "in",
            "on", "is", "are", "what", "how", "please", "search", "web", "online", "official",
            "website", "page", "docs", "documentation", "latest", "current", "today", "source");
    private static final Pattern LATIN = Pattern.compile("[a-z0-9][a-z0-9+.#-]*");

    private WebSearchRelevance() { }

    static boolean relevant(String query, WebSearchResult result) {
        Set<String> anchors = anchors(query);
        if (anchors.isEmpty()) return true;
        String document = (result.title() + " " + result.url() + " " + result.snippet()).toLowerCase(Locale.ROOT);
        if (anchors.stream().filter(term -> term.matches("\\d{3,}"))
                .anyMatch(term -> !containsAnchor(document, term))) return false;
        long matched = anchors.stream().filter(term -> containsAnchor(document, term)).count();
        return matched >= Math.max(1, (int) Math.ceil(anchors.size() * 0.75));
    }

    private static boolean containsAnchor(String document, String term) {
        if (term.matches("\\d{3,}")) {
            return Pattern.compile("(?<![0-9])" + Pattern.quote(term) + "(?![0-9])")
                    .matcher(document).find();
        }
        return document.contains(term);
    }

    static String recoveryQuery(String query) {
        WebSearchScope scope = WebSearchScope.parse(query);
        Set<String> anchors = anchors(query);
        String subject = anchors.isEmpty() ? "\"" + scope.subject().replace("\"", "") + "\""
                : String.join(" ", anchors.stream().limit(8).map(term -> "\"" + term + "\"").toList());
        return (scope.prefix() + " " + subject).trim();
    }

    private static Set<String> anchors(String query) {
        String normalized = WebSearchScope.parse(query).subject().toLowerCase(Locale.ROOT)
                .replaceAll("(?<![a-z0-9])rfc[- ]?(\\d{3,})(?![0-9])", "rfc $1");
        LinkedHashSet<String> latin = new LinkedHashSet<>();
        var matcher = LATIN.matcher(normalized);
        while (matcher.find()) {
            String term = matcher.group();
            if (term.length() >= 2 && !STOP.contains(term)) latin.add(term);
        }
        if (!latin.isEmpty()) return latin;
        for (String noise : List.of("官方", "页面", "来源", "资料", "搜索", "查询", "请", "一下",
                "最新", "今天", "目前", "联网", "网站", "文档", "是什么", "帮我", "核实")) {
            normalized = normalized.replace(noise, " ");
        }
        LinkedHashSet<String> han = new LinkedHashSet<>();
        var chunks = Pattern.compile("\\p{IsHan}{2,}").matcher(normalized);
        while (chunks.find()) {
            String chunk = chunks.group();
            for (int i = 0; i < chunk.length() - 1; i++) han.add(chunk.substring(i, i + 2));
        }
        return han;
    }
}

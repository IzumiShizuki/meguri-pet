package com.meguri.core.websearch;

import java.util.Locale;
import java.util.regex.Pattern;

/** Explicit-intent gate; ordinary character chat remains offline/local. */
public final class WebSearchPolicy {
    private static final Pattern SEARCH_INTENT = Pattern.compile(
            "(联网搜索|网上搜索|搜索资料|查资料|查一下|搜一下|最新消息|最新资讯|新闻|官网|实时|网上查|search the web|web search|look up|latest news|current news|official website)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private WebSearchPolicy() {}

    public static boolean shouldSearch(String message) {
        if (message == null || message.isBlank()) return false;
        return SEARCH_INTENT.matcher(message.toLowerCase(Locale.ROOT)).find();
    }

    /** Removes the common request wrapper before sending a bounded query upstream. */
    public static String queryFor(String message) {
        if (message == null) return "";
        String query = message.trim()
                .replaceFirst("(?i)^(请|帮我|可以帮我|麻烦)\\s*", "")
                .replaceFirst("(?i)(联网搜索|网上搜索|搜索资料|查资料|查一下|搜一下|search the web|web search|look up)\\s*", "")
                .replaceFirst("(?i)(并(总结|概括|告诉我).*)$", "")
                .trim();
        if (query.isBlank()) query = message.trim();
        return query.length() <= 256 ? query : query.substring(0, 256);
    }
}

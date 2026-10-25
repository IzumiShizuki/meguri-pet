package com.meguri.core.websearch;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Deterministic web-search intent gate.
 *
 * <p>Explicit search requests always opt in. Otherwise a message needs both
 * a freshness signal and a volatile-information signal, or an unambiguous
 * dynamic-information term such as news or release. This keeps ordinary
 * conversation local while covering current updates without a second model
 * call.</p>
 */
public final class WebSearchPolicy {
    private static final Pattern EXPLICIT_SEARCH = Pattern.compile(
            "(联网(?:搜索|查询|查找|检索|查)|网上(?:搜索|查询|查找|检索|查)|"
                    + "搜索资料|查资料|查一下|搜一下|网上查|"
                    + "search the web|web search|look up|browse|search online)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern EXPLICITLY_OFFLINE_REQUEST = Pattern.compile(
            "^\\s*(?:请|麻烦)?\\s*(?:不要|别|不必|无需|不需要|不用|禁止)\\s*(?:再)?\\s*"
                    + "(?:联网(?:搜索|查询|查找|检索|查)?|上网(?:搜索|查询|查找|检索)?|"
                    + "网上(?:搜索|查询|查找|检索)?|web\\s+(?:search|browse)|browse|search online)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern FRESHNESS = Pattern.compile(
            "(今天|今日|现在|当前|目前|刚刚|刚才|最新|最近|近期|本周|本月|截至|实时|"
                    + "today|now|current|currently|latest|newest|recent|recently|this week|this month|as of)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern VOLATILE_INFORMATION = Pattern.compile(
            "(新闻|资讯|消息|公告|发布|发行|更新|改动|变化|动态|状态|版本|价格|报价|"
                    + "赛程|日程|天气|比分|股价|汇率|漏洞|安全问题|支持什么|是否支持|"
                    + "api|文档|兼容性|模型|"
                    + "news|announcement|release|update|changelog|status|price|pricing|"
                    + "schedule|weather|score|exchange rate|stock|security issue|"
                    + "api|docs?|documentation|compatibility|model|supports?)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern DIRECT_CURRENT_FACT = Pattern.compile(
            "(最新消息|最新资讯|最新新闻|今日新闻|今日资讯|今天的新闻|今天有什么新闻|"
                    + "latest news|current news|official website|official docs)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern CONVERSATIONAL_PREFIX = Pattern.compile(
            "^(?:(?:请|麻烦|可以|能否|能不能|please|could you|can you)\\s*)?"
                    + "(?:(?:帮我|帮忙|为我|替我)\\s*)*",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern LOOKUP_WRAPPER = Pattern.compile(
            "^(?:查看|查阅|查询|查找|检索|搜索|搜寻|搜一下|查一下|看看|"
                    + "联网(?:搜索|查询|查找|检索|查)|网上(?:搜索|查询|查找|检索|查)|"
                    + "search(?: the web| online)?|web search|look up)(?:一下|下)?\\s*",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern LEADING_FRESHNESS = Pattern.compile(
            "^(今天|今日|现在|当前|目前|刚刚|刚才|最新|最近|近期|本周|本月|截至|实时|"
                    + "today|currently|current|latest|newest|recently?|this\\s+(?:week|month)|as\\s+of)\\s*",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private WebSearchPolicy() {}

    public static boolean shouldSearch(String message) {
        if (message == null || message.isBlank()) return false;
        if (EXPLICITLY_OFFLINE_REQUEST.matcher(message).find()) return false;
        String normalized = message.toLowerCase(Locale.ROOT);
        if (EXPLICIT_SEARCH.matcher(normalized).find()
                || DIRECT_CURRENT_FACT.matcher(normalized).find()) {
            return true;
        }
        return FRESHNESS.matcher(normalized).find()
                && VOLATILE_INFORMATION.matcher(normalized).find();
    }

    /** Removes the common request wrapper before sending a bounded query upstream. */
    public static String queryFor(String message) {
        if (message == null) return "";
        String original = message.trim();
        String query = original;
        java.util.LinkedHashSet<String> freshnessQualifiers = new java.util.LinkedHashSet<>();
        for (int pass = 0; pass < 4; pass++) {
            String before = query;
            query = CONVERSATIONAL_PREFIX.matcher(query).replaceFirst("").trim();
            query = LOOKUP_WRAPPER.matcher(query).replaceFirst("").trim();
            query = query.replaceFirst("(?i)(?:并)?(?:总结|概括|告诉我|解释一下|分析一下).*$", "")
                    .trim();
            java.util.regex.Matcher freshness = LEADING_FRESHNESS.matcher(query);
            if (freshness.find()) {
                freshnessQualifiers.add(freshness.group(1).trim());
                query = query.substring(freshness.end()).trim();
            }
            if (query.equals(before)) break;
        }
        query = query.replaceFirst("(?:是否|有没有|有无|有)\\s*更新(?:了)?(?:吗|么)?[？?！!。]*$", " 更新")
                .replaceFirst("[？?]+$", "")
                .replaceAll("(?<=[A-Za-z0-9])(?=\\p{IsHan})", " ")
                .replaceAll("(?<=\\p{IsHan})(?=[A-Za-z0-9])", " ")
                .replaceAll("\\s+", " ").trim();
        if (!freshnessQualifiers.isEmpty() && !query.isBlank()) {
            query = query + " " + String.join(" ", freshnessQualifiers);
        }
        if (query.isBlank()) query = original;
        return query.length() <= 256 ? query : query.substring(0, 256);
    }
}

package com.meguri.core.websearch;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Explicit domain operators are constraints, never relevance evidence. */
record WebSearchScope(String subject, List<String> domains, boolean valid) {
    private static final Pattern SITE = Pattern.compile(
            "(?i)(?<![^\\s,，;；(（])site\\s*:\\s*([^\\s,，;；。!?！？\"“”'‘’`()（）<>]*)");
    private static final Pattern DOMAIN = Pattern.compile(
            "(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}");

    static WebSearchScope parse(String query) {
        String value = query == null ? "" : query;
        char[] visible = value.toCharArray();
        char closing = 0;
        for (int i = 0; i < visible.length; i++) {
            char c = visible[i];
            if (closing != 0) {
                visible[i] = ' ';
                if (c == closing) closing = 0;
            } else if (c == '"' || (c == '\'' && (i == 0 || !Character.isLetterOrDigit(value.charAt(i - 1))))
                    || c == '`' || c == '“' || c == '‘' || c == '「' || c == '『') {
                closing = switch (c) { case '“' -> '”'; case '‘' -> '’'; case '「' -> '」'; case '『' -> '』'; default -> c; };
                visible[i] = ' ';
            }
        }
        var matcher = SITE.matcher(new String(visible));
        StringBuilder subject = new StringBuilder(value);
        LinkedHashSet<String> domains = new LinkedHashSet<>();
        boolean valid = true;
        while (matcher.find()) {
            String domain = matcher.group(1).toLowerCase(Locale.ROOT).replaceFirst("\\.$", "");
            if (!DOMAIN.matcher(domain).matches()) valid = false;
            else domains.add(domain);
            for (int i = matcher.start(); i < matcher.end(); i++) subject.setCharAt(i, ' ');
        }
        return new WebSearchScope(subject.toString().trim(), List.copyOf(domains), valid);
    }

    boolean accepts(String canonicalUrl) {
        if (!valid) return false;
        try {
            String host = URI.create(canonicalUrl).getHost();
            if (host == null) return false;
            String normalized = host.toLowerCase(Locale.ROOT).replaceFirst("\\.$", "");
            // Multiple operators are conjunctive; incompatible scopes fail closed.
            return domains.stream().allMatch(domain -> normalized.equals(domain) || normalized.endsWith("." + domain));
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    String prefix() {
        return String.join(" ", domains.stream().map(domain -> "site:" + domain).toList());
    }
}

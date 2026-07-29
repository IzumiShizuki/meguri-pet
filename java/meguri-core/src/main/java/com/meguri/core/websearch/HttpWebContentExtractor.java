package com.meguri.core.websearch;

import java.time.Duration;
import java.time.Instant;
import java.net.InetAddress;
import java.net.URI;
import java.util.regex.Pattern;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/** Bounded data-only HTML extractor. URL policy must run before this boundary. */
public final class HttpWebContentExtractor implements WebContentExtractor {
    private static final Pattern NON_CONTENT = Pattern.compile(
            "(?is)<(script|style|noscript|svg|iframe)[^>]*>.*?</\\1>");
    private static final Pattern TAG = Pattern.compile("(?s)<[^>]+>");
    private static final int MAX_BODY_CHARS = 1_000_000;
    private static final int MAX_CONTENT_CHARS = 12_000;
    private final WebClient client;

    public HttpWebContentExtractor(WebClient client) {
        this.client = client == null ? WebClient.builder().build() : client;
    }

    @Override
    public Mono<ExtractedWebPage> extract(WebSearchResult result, Instant deadline) {
        Duration remaining = Duration.between(Instant.now(), deadline);
        if (remaining.isNegative() || remaining.isZero()
                || !resolvesToPublicAddress(result.url())) return Mono.empty();
        return client.get().uri(result.url())
                .header("Accept", "text/html,application/xhtml+xml,text/plain")
                .retrieve()
                .bodyToMono(String.class)
                .filter(body -> body.length() <= MAX_BODY_CHARS)
                .map(body -> new ExtractedWebPage(
                        result.url(), result.title(), clean(body), Instant.now()))
                .filter(page -> !page.content().isBlank())
                .timeout(remaining);
    }

    private static boolean resolvesToPublicAddress(String rawUrl) {
        try {
            String host = URI.create(rawUrl).getHost();
            if (host == null) return false;
            InetAddress[] addresses = InetAddress.getAllByName(host);
            return addresses.length > 0 && java.util.Arrays.stream(addresses).allMatch(address ->
                    !address.isAnyLocalAddress() && !address.isLoopbackAddress()
                            && !address.isLinkLocalAddress() && !address.isSiteLocalAddress()
                            && !address.isMulticastAddress());
        } catch (Exception error) {
            return false;
        }
    }

    private static String clean(String html) {
        String value = NON_CONTENT.matcher(html == null ? "" : html).replaceAll(" ");
        value = TAG.matcher(value).replaceAll(" ")
                .replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'")
                .replaceAll("\\s+", " ").trim();
        return value.length() <= MAX_CONTENT_CHARS
                ? value : value.substring(0, MAX_CONTENT_CHARS).trim();
    }
}

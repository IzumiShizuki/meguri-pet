package com.meguri.core.websearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Keyless, bounded web lookup using DuckDuckGo's instant-answer endpoint.
 * This is intentionally a retrieval adapter, not arbitrary URL fetching.
 */
public final class DuckDuckGoWebSearchGateway implements WebSearchGateway {
    private final WebClient client;
    private final ObjectMapper mapper;
    private final Duration timeout;
    private final int maxResults;
    private final String baseUrl;

    public DuckDuckGoWebSearchGateway(WebClient client, ObjectMapper mapper,
                                     String baseUrl, Duration timeout, int maxResults) {
        this.client = client == null ? WebClient.builder().build() : client;
        this.mapper = mapper == null ? new ObjectMapper() : mapper;
        this.baseUrl = validateBaseUrl(baseUrl);
        this.timeout = timeout == null || timeout.isNegative() || timeout.isZero()
                ? Duration.ofSeconds(8) : timeout;
        this.maxResults = Math.max(1, Math.min(maxResults, 5));
    }

    @Override
    public Mono<WebSearchRecall> search(String query, int limit) {
        String normalized = query == null ? "" : query.trim();
        if (normalized.isBlank()) return Mono.just(WebSearchRecall.unavailable(providerName()));
        int boundedLimit = Math.max(1, Math.min(limit <= 0 ? maxResults : limit, maxResults));
        return client.get()
                .uri(uriBuilder -> uriBuilder
                        .scheme(URI.create(baseUrl).getScheme())
                        .host(URI.create(baseUrl).getHost())
                        .path(URI.create(baseUrl).getPath().isBlank() ? "/" : URI.create(baseUrl).getPath())
                        .queryParam("q", normalized)
                        .queryParam("format", "json")
                        .queryParam("no_html", "1")
                        .queryParam("skip_disambig", "1")
                        .build())
                .retrieve()
                .bodyToMono(String.class)
                .timeout(timeout)
                .map(body -> parse(body, boundedLimit))
                .onErrorReturn(WebSearchRecall.unavailable(providerName()));
    }

    @Override
    public String providerName() {
        return "duckduckgo-instant-answer";
    }

    WebSearchRecall parse(String body, int limit) {
        try {
            JsonNode root = mapper.readTree(body == null ? "{}" : body);
            List<WebSearchResult> results = new ArrayList<>();
            Set<String> urls = new HashSet<>();
            addResult(results, urls, root.path("Heading").asText(), root.path("AbstractURL").asText(),
                    root.path("AbstractText").asText(), limit);
            collectRelated(root.path("RelatedTopics"), results, urls, limit);
            return new WebSearchRecall(results.isEmpty() ? "empty" : "ok", providerName(), results);
        } catch (Exception ignored) {
            return WebSearchRecall.unavailable(providerName());
        }
    }

    private void collectRelated(JsonNode node, List<WebSearchResult> results,
                                Set<String> urls, int limit) {
        if (node == null || !node.isArray() || results.size() >= limit) return;
        for (JsonNode item : node) {
            if (results.size() >= limit) return;
            if (item.has("Topics")) {
                collectRelated(item.path("Topics"), results, urls, limit);
                continue;
            }
            addResult(results, urls, item.path("Text").asText(), item.path("FirstURL").asText(),
                    item.path("Text").asText(), limit);
        }
    }

    private static void addResult(List<WebSearchResult> results, Set<String> urls,
                                  String title, String url, String snippet, int limit) {
        if (results.size() >= limit || (url == null || url.isBlank()) && (snippet == null || snippet.isBlank())) return;
        String normalizedUrl = url == null ? "" : url.trim();
        if (!normalizedUrl.isBlank() && !urls.add(normalizedUrl)) return;
        results.add(new WebSearchResult(title, normalizedUrl, snippet));
    }

    private static String validateBaseUrl(String raw) {
        String value = raw == null ? "" : raw.trim();
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) throw new IllegalArgumentException();
            return value;
        } catch (Exception ex) {
            throw new IllegalArgumentException("web search base URL must be HTTPS", ex);
        }
    }
}

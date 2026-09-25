package com.meguri.core.websearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.web.reactive.function.client.WebClient;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import reactor.core.publisher.Mono;

/** General web search through Bing's read-only RSS result endpoint. */
public final class BingRssWebSearchGateway implements WebSearchGateway {
    private final WebClient client;
    private final Duration timeout;
    private final int maxResults;
    private final String baseUrl;

    public BingRssWebSearchGateway(WebClient client, ObjectMapper ignoredMapper,
                                   String baseUrl, Duration timeout, int maxResults) {
        this.client = client == null ? WebClient.builder().build() : client;
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
        URI base = URI.create(baseUrl);
        return client.get()
                .uri(uriBuilder -> uriBuilder.scheme(base.getScheme()).host(base.getHost())
                        .path(base.getPath().isBlank() ? "/search" : base.getPath())
                        .queryParam("format", "rss").queryParam("q", normalized).build())
                .header("User-Agent", "MeguriCore/0.1 (local personal assistant)")
                .retrieve()
                .bodyToMono(String.class)
                .timeout(timeout)
                .map(body -> parse(body, boundedLimit))
                .onErrorReturn(WebSearchRecall.unavailable(providerName()));
    }

    @Override
    public String providerName() {
        return "bing-rss";
    }

    WebSearchRecall parse(String body, int limit) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var document = factory.newDocumentBuilder().parse(
                    new ByteArrayInputStream((body == null ? "" : body).getBytes(StandardCharsets.UTF_8)));
            NodeList items = document.getElementsByTagName("item");
            List<WebSearchResult> results = new ArrayList<>();
            for (int i = 0; i < items.getLength() && results.size() < limit; i++) {
                if (!(items.item(i) instanceof Element item)) continue;
                String title = childText(item, "title");
                String url = childText(item, "link");
                String snippet = childText(item, "description");
                if (!url.isBlank() || !snippet.isBlank()) results.add(new WebSearchResult(title, url, snippet));
            }
            return new WebSearchRecall(results.isEmpty() ? "empty" : "ok", providerName(), results);
        } catch (Exception ignored) {
            return WebSearchRecall.unavailable(providerName());
        }
    }

    private static String childText(Element parent, String name) {
        NodeList nodes = parent.getElementsByTagName(name);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
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

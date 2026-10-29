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
        URI target = org.springframework.web.util.UriComponentsBuilder.fromUriString(baseUrl)
                .queryParam("format", "rss").queryParam("q", "{query}")
                .encode(StandardCharsets.UTF_8).buildAndExpand(normalized).toUri();
        return client.get()
                .uri(target)
                .header("User-Agent", "MeguriCore/0.1 (local personal assistant)")
                .exchangeToMono(response -> {
                    if (!response.statusCode().is2xxSuccessful()) {
                        return Mono.just(WebSearchRecall.unavailable(providerName(),
                                "web_http_" + response.statusCode().value()));
                    }
                    if (response.headers().contentType()
                            .map(type -> type.isCompatibleWith(org.springframework.http.MediaType.TEXT_HTML))
                            .orElse(false)) {
                        return Mono.just(WebSearchRecall.unavailable(providerName(), "web_response_non_rss"));
                    }
                    return response.bodyToMono(String.class).defaultIfEmpty("")
                            .map(body -> parse(body, boundedLimit));
                })
                .timeout(timeout)
                .onErrorResume(error -> Mono.just(WebSearchRecall.unavailable(providerName(),
                        transportDiagnostic(error))));
    }

    @Override
    public String providerName() {
        return "bing-rss";
    }

    WebSearchRecall parse(String body, int limit) {
        if (body == null || body.isBlank()) {
            return WebSearchRecall.unavailable(providerName(), "web_response_empty_body");
        }
        // HTML error/landing pages may omit or mislabel Content-Type. Classify them
        // before the secure XML parser rejects the common HTML doctype.
        if (java.util.regex.Pattern.compile("(?is)^\\s*\\uFEFF?\\s*(?:<\\?xml[^>]*>\\s*)?(?:<!doctype\\s+html\\b|<html\\b)")
                .matcher(body).find()) {
            return WebSearchRecall.unavailable(providerName(), "web_response_non_rss");
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
            var document = builder.parse(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
            if (document.getDocumentElement() == null
                    || !"rss".equalsIgnoreCase(document.getDocumentElement().getNodeName())) {
                return WebSearchRecall.unavailable(providerName(), "web_response_non_rss");
            }
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
            return WebSearchRecall.unavailable(providerName(), "web_xml_parse_error");
        }
    }

    private static String transportDiagnostic(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof java.util.concurrent.TimeoutException) return "web_transport_timeout";
            if (current instanceof java.net.UnknownHostException) return "web_dns_error";
            if (current instanceof javax.net.ssl.SSLException) return "web_tls_error";
            if (current instanceof java.net.ConnectException) return "web_connect_error";
        }
        return "web_transport_error";
    }

    private static String childText(Element parent, String name) {
        NodeList nodes = parent.getElementsByTagName(name);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
    }

    private static String validateBaseUrl(String raw) {
        String value = raw == null ? "" : raw.trim();
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getFragment() != null
                    || uri.getQuery() != null) throw new IllegalArgumentException();
            return value;
        } catch (Exception ex) {
            throw new IllegalArgumentException("web search base URL must be HTTPS", ex);
        }
    }
}

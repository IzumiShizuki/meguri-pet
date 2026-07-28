package com.meguri.core.knowledge.notion;

import com.meguri.core.knowledge.KnowledgeSourceSyncException;
import com.meguri.core.knowledge.NotionHttpPort;
import com.meguri.core.knowledge.NotionApiSourceRegistry;
import com.meguri.core.knowledge.NotionHttpRequest;
import com.meguri.core.knowledge.NotionHttpResponse;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;

/** Blocking transport used only by the scheduled ingestion worker. */
public final class JdkNotionHttpPort implements NotionHttpPort {
    private final HttpClient client;
    private final URI baseUri;

    public JdkNotionHttpPort(HttpClient client, String baseUrl) {
        this.client = Objects.requireNonNull(client, "client");
        this.baseUri = normalizedBaseUri(baseUrl);
    }

    @Override
    public NotionHttpResponse execute(NotionHttpRequest request) {
        Objects.requireNonNull(request, "request");
        if (!NotionApiSourceRegistry.DEFAULT_NOTION_VERSION.equals(request.notionVersion())) {
            throw new KnowledgeSourceSyncException("Unsupported Notion API version");
        }
        URI uri = requestUri(request.path(), request.query());
        HttpRequest httpRequest = HttpRequest.newBuilder(uri)
                .timeout(request.timeout())
                .header("Authorization", request.credentials().authorizationHeader())
                .header("Notion-Version", request.notionVersion())
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> response = client.send(
                    httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new NotionHttpResponse(response.statusCode(), response.body());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw transportFailure();
        } catch (IOException | RuntimeException error) {
            throw transportFailure();
        }
    }

    private URI requestUri(String path, Map<String, String> query) {
        if (path == null || !path.startsWith("/v1/") || path.contains("://")
                || path.contains("?") || path.contains("#")) {
            throw new KnowledgeSourceSyncException("Notion HTTP request path is invalid");
        }
        StringBuilder target = new StringBuilder(baseUri.toString()).append(path);
        query.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .forEach(entry -> target.append(target.indexOf("?") < 0 ? '?' : '&')
                        .append(encode(entry.getKey()))
                        .append('=')
                        .append(encode(entry.getValue())));
        try {
            URI uri = URI.create(target.toString());
            if (!sameAuthority(baseUri, uri)) {
                throw new IllegalArgumentException("authority changed");
            }
            return uri;
        } catch (RuntimeException error) {
            throw new KnowledgeSourceSyncException("Notion HTTP request URI is invalid");
        }
    }

    private static URI normalizedBaseUri(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Notion base URL is required");
        }
        URI uri = URI.create(value.strip());
        boolean secure = "https".equalsIgnoreCase(uri.getScheme());
        boolean loopbackHttp = "http".equalsIgnoreCase(uri.getScheme())
                && isLoopback(uri.getHost());
        if (!(secure || loopbackHttp)
                || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Notion base URL is invalid");
        }
        String normalized = uri.toString();
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        return URI.create(normalized);
    }

    private static boolean isLoopback(String host) {
        return host != null && (host.equalsIgnoreCase("localhost")
                || host.equals("127.0.0.1")
                || host.equals("::1")
                || host.equals("[::1]"));
    }

    private static boolean sameAuthority(URI expected, URI actual) {
        return expected.getScheme().equalsIgnoreCase(actual.getScheme())
                && expected.getHost().equalsIgnoreCase(actual.getHost())
                && effectivePort(expected) == effectivePort(actual);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static String encode(String value) {
        if (value == null) throw new KnowledgeSourceSyncException("Notion query value is invalid");
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static KnowledgeSourceSyncException transportFailure() {
        return new KnowledgeSourceSyncException("Notion HTTP transport failed");
    }

    public static HttpClient defaultClient(Duration connectTimeout) {
        if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()) {
            throw new IllegalArgumentException("Notion connect timeout must be positive");
        }
        return HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }
}

package com.meguri.core.capability;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import org.springframework.http.MediaType;

import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** One document, bounded redirects and a total deadline; never crawls returned links. */
public final class PublicWebFetcher {
    static final int MAX_BYTES = 2_000_000;
    static final int MAX_CHARS = 12_000;
    static final int MAX_REDIRECTS = 3;
    private final PublicWebFetchPolicy policy;
    private final Transport transport;
    private final Duration deadline;

    public PublicWebFetcher() { this(new PublicWebFetchPolicy(), new PinnedWebFetchTransport(), Duration.ofSeconds(12)); }
    PublicWebFetcher(PublicWebFetchPolicy policy, Transport transport, Duration deadline) {
        this.policy = policy; this.transport = transport; this.deadline = deadline;
    }

    public Mono<Map<String,Object>> fetch(String url, String mode, int maxChars) {
        return Mono.defer(() -> {
            if (!"text".equals(mode) || maxChars < 1 || maxChars > MAX_CHARS)
                return Mono.error(new IllegalArgumentException("WEB_FETCH_ARGUMENT_INVALID"));
            URI uri = PublicWebFetchPolicy.parse(url);
            return hop(uri, 0, new HashSet<>(), maxChars);
        }).timeout(deadline);
    }

    private Mono<Map<String,Object>> hop(URI uri, int redirects, Set<URI> visited, int maxChars) {
        return Mono.defer(() -> {
            if (!visited.add(uri)) return Mono.error(new IllegalArgumentException("WEB_FETCH_REDIRECT_LOOP"));
            return Mono.fromCallable(() -> policy.resolve(uri)).subscribeOn(Schedulers.boundedElastic())
                    .flatMap(transport::get).flatMap(response -> {
                        if (redirect(response.status())) {
                            if (redirects == MAX_REDIRECTS || response.location().isBlank())
                                return Mono.error(new IllegalArgumentException("WEB_FETCH_REDIRECT_LIMIT"));
                            URI next = PublicWebFetchPolicy.parse(uri.resolve(response.location()).toString());
                            if (uri.getScheme().equals("https") && next.getScheme().equals("http"))
                                return Mono.error(new IllegalArgumentException("WEB_FETCH_HTTPS_DOWNGRADE"));
                            return hop(next, redirects + 1, visited, maxChars);
                        }
                        if (response.status() < 200 || response.status() >= 300)
                            return Mono.error(new IllegalArgumentException("WEB_FETCH_HTTP_" + response.status()));
                        validateHeaders(response.contentType(), "identity", Integer.toString(response.body().length));
                        MediaType type = MediaType.parseMediaType(response.contentType());
                        Charset charset = type.getCharset() == null ? StandardCharsets.UTF_8 : type.getCharset();
                        String body = new String(response.body(), charset);
                        return Mono.fromCallable(() -> PublicWebDocument.extract(
                                uri, body, type.getSubtype().equalsIgnoreCase("plain"), maxChars, redirects))
                                .subscribeOn(Schedulers.boundedElastic());
                    });
        });
    }

    static boolean redirect(int status) { return Set.of(301, 302, 303, 307, 308).contains(status); }
    static void validateHeaders(String contentType, String encoding, String length) {
        try {
            MediaType type = MediaType.parseMediaType(contentType);
            String mime = type.getType().toLowerCase(java.util.Locale.ROOT) + "/" + type.getSubtype().toLowerCase(java.util.Locale.ROOT);
            if (!Set.of("text/plain", "text/html", "application/xhtml+xml").contains(mime)
                    || !encoding.equalsIgnoreCase("identity"))
                throw new IllegalArgumentException();
            if (!length.isBlank() && (Long.parseLong(length) < 0 || Long.parseLong(length) > MAX_BYTES))
                throw new IllegalArgumentException();
            // Resolve unsupported charset before receiving/parsing a document.
            type.getCharset();
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("WEB_FETCH_RESPONSE_REJECTED");
        }
    }

    interface Transport { Mono<Response> get(PublicWebFetchPolicy.Approved target); }
    record Response(int status, String location, String contentType, byte[] body) { }
}

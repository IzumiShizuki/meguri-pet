package com.meguri.core.websearch;

import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.reactive.MockClientHttpResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.*;

class HttpWebContentExtractorTest {
    @Test void readsPublicDocumentLargerThanDefaultCodecLimitButBoundsModelContent() {
        var extractor = extractor("<title>Public reference</title><p>" + "body ".repeat(240_000) + "</p>");
        var page = extractor.extract(source(), Instant.now().plusSeconds(3)).block(Duration.ofSeconds(3));
        assertThat(page).isNotNull();
        assertThat(page.content()).startsWith("Public reference body").hasSizeLessThanOrEqualTo(12_000);
    }

    @Test void oversizedResponseStillFailsWithinTheExplicitBodyBound() {
        var extractor = extractor("x".repeat(2_100_000));
        assertThatThrownBy(() -> extractor.extract(source(), Instant.now().plusSeconds(3)).block())
                .hasStackTraceContaining("DataBufferLimitException");
    }

    private static WebSearchResult source() {
        // Public literal avoids DNS in a unit test; the connector never opens a socket.
        return new WebSearchResult("reference", "https://93.184.216.34/reference", "");
    }

    private static HttpWebContentExtractor extractor(String html) {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        var factory = new DefaultDataBufferFactory();
        WebClient client = WebClient.builder().clientConnector((method, uri, callback) -> {
            var response = new MockClientHttpResponse(HttpStatus.OK);
            response.getHeaders().set("Content-Type", "text/html; charset=UTF-8");
            response.setBody(Flux.range(0, (bytes.length + 65_535) / 65_536).map(index -> {
                            int start = index * 65_536;
                            return factory.wrap(java.util.Arrays.copyOfRange(bytes, start, Math.min(bytes.length, start + 65_536)));
                        }));
            return Mono.just(response);
        }).build();
        return new HttpWebContentExtractor(client);
    }
}

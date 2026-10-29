package com.meguri.core.websearch;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

class BingRssDiagnosticsTest {
    @Test
    void successfulHttpHtmlIsUnavailableNotEmptyRss() {
        for (String mime : List.of("text/html; charset=UTF-8", "application/xml", "application/xhtml+xml", "")) {
            for (String html : List.of("<!doctype html><html><title>Landing</title></html>", "<html><body>Landing</body></html>")) {
                var response = ClientResponse.create(HttpStatus.OK).body(html);
                if (!mime.isEmpty()) response.header("Content-Type", mime);
                var recall = gateway(response.build()).search("RFC 9110", 5).block();
                assertThat(recall.status()).isEqualTo("unavailable");
                assertThat(recall.results()).isEmpty();
                assertThat(recall.diagnosticCodes()).containsExactly("web_response_non_rss");
            }
        }
    }

    @Test
    void emptyBodyEmptyRssWrongRootMalformedXmlAndUnsafeXmlRemainDistinct() {
        var gateway = gateway(ClientResponse.create(HttpStatus.OK).build());
        assertThat(gateway.search("RFC 9110", 5).block().diagnosticCodes()).containsExactly("web_response_empty_body");
        assertThat(gateway.parse("<rss><channel/></rss>", 5).status()).isEqualTo("empty");
        assertThat(gateway.parse("<rss><channel/></rss>", 5).diagnosticCodes()).isEmpty();
        assertThat(gateway.parse("<feed/>", 5).diagnosticCodes()).containsExactly("web_response_non_rss");
        assertThat(gateway.parse("<rss><channel>", 5).diagnosticCodes()).containsExactly("web_xml_parse_error");
        assertThat(gateway.parse("<!DOCTYPE rss [<!ENTITY xxe SYSTEM 'https://example.org/never-fetch'>]><rss>&xxe;</rss>", 5)
                .diagnosticCodes()).containsExactly("web_xml_parse_error");
    }

    @Test
    void httpFailuresAreStatusOnlyWithoutResponseOrExceptionDetails() {
        for (HttpStatus status : List.of(HttpStatus.TOO_MANY_REQUESTS, HttpStatus.FORBIDDEN, HttpStatus.INTERNAL_SERVER_ERROR)) {
            var recall = gateway(ClientResponse.create(status).header("Content-Type", "text/html")
                    .body("private response detail").build()).search("RFC 9110", 5).block();
            assertThat(recall.diagnosticCodes()).containsExactly("web_http_" + status.value());
            assertThat(recall.results()).isEmpty();
        }
    }

    @Test
    void transportFailureCategoriesDoNotExposeExceptions() {
        List<Throwable> errors = List.of(new java.util.concurrent.TimeoutException("private"),
                new java.net.UnknownHostException("private"), new javax.net.ssl.SSLException("private"),
                new java.net.ConnectException("private"), new IllegalStateException("private"));
        List<String> codes = List.of("web_transport_timeout", "web_dns_error", "web_tls_error", "web_connect_error", "web_transport_error");
        for (int i = 0; i < errors.size(); i++) {
            Throwable error = errors.get(i);
            var client = WebClient.builder().exchangeFunction(request -> Mono.error(new RuntimeException("private", error))).build();
            var recall = new BingRssWebSearchGateway(client, null, "https://www.bing.com/search", Duration.ofSeconds(1), 5)
                    .search("RFC 9110", 5).block();
            assertThat(recall.diagnosticCodes()).containsExactly(codes.get(i));
            assertThat(recall.results()).isEmpty();
        }
    }

    private static BingRssWebSearchGateway gateway(ClientResponse response) {
        return new BingRssWebSearchGateway(WebClient.builder().exchangeFunction(request -> Mono.just(response)).build(),
                null, "https://www.bing.com/search", Duration.ofSeconds(1), 5);
    }
}

package com.meguri.core.knowledge.notion;

import com.meguri.core.knowledge.KnowledgeSourceSyncException;
import com.meguri.core.knowledge.NotionApiCredentials;
import com.meguri.core.knowledge.NotionHttpRequest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdkNotionHttpPortTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void sendsRequiredHeadersAndPreservesOpaqueCursorEncoding() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> notionVersion = new AtomicReference<>();
        AtomicReference<String> rawQuery = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/blocks/page-1/children", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            notionVersion.set(exchange.getRequestHeaders().getFirst("Notion-Version"));
            rawQuery.set(exchange.getRequestURI().getRawQuery());
            byte[] body = "{\"results\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        String token = "ntn_transport_test_secret";
        JdkNotionHttpPort port = port();

        var response = port.execute(new NotionHttpRequest(
                "/v1/blocks/page-1/children",
                Map.of("start_cursor", "opaque+/== cursor", "page_size", "100"),
                "2026-03-11", new NotionApiCredentials(token), Duration.ofSeconds(2)));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(authorization.get()).isEqualTo("Bearer " + token);
        assertThat(notionVersion.get()).isEqualTo("2026-03-11");
        assertThat(rawQuery.get())
                .contains("page_size=100")
                .contains("start_cursor=opaque%2B%2F%3D%3D%20cursor");
    }

    @Test
    void timeoutAndTransportErrorsNeverExposeCredential() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/pages/slow", exchange -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.start();
        String token = "ntn_timeout_secret";

        assertThatThrownBy(() -> port().execute(new NotionHttpRequest(
                "/v1/pages/slow", Map.of(), "2026-03-11",
                new NotionApiCredentials(token), Duration.ofMillis(50))))
                .isInstanceOf(KnowledgeSourceSyncException.class)
                .hasMessage("Notion HTTP transport failed")
                .satisfies(error -> {
                    assertThat(error.toString()).doesNotContain(token);
                    assertThat(error.getCause()).isNull();
                });
    }

    @Test
    void rejectsPathsThatCouldEscapeConfiguredNotionAuthority() {
        JdkNotionHttpPort port = portWithoutServer();

        assertThatThrownBy(() -> port.execute(new NotionHttpRequest(
                "/v1/pages/x?redirect=https://attacker.invalid", Map.of(),
                "2026-03-11", new NotionApiCredentials("secret"), Duration.ofSeconds(1))))
                .isInstanceOf(KnowledgeSourceSyncException.class)
                .hasMessageContaining("path is invalid");
    }

    @Test
    void rejectsAnyApiVersionOtherThanPinnedContract() {
        JdkNotionHttpPort port = portWithoutServer();

        assertThatThrownBy(() -> port.execute(new NotionHttpRequest(
                "/v1/pages/page-1", Map.of(), "2022-06-28",
                new NotionApiCredentials("secret"), Duration.ofSeconds(1))))
                .isInstanceOf(KnowledgeSourceSyncException.class)
                .hasMessage("Unsupported Notion API version");
    }

    private JdkNotionHttpPort port() {
        return new JdkNotionHttpPort(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(),
                "http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static JdkNotionHttpPort portWithoutServer() {
        return new JdkNotionHttpPort(
                HttpClient.newHttpClient(), "https://api.notion.com");
    }
}

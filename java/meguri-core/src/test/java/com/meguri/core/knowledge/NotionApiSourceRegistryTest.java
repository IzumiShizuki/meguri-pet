package com.meguri.core.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotionApiSourceRegistryTest {
    private static final KnowledgeAcl ACL = new KnowledgeAcl("meguri", Set.of("user:izumi"));
    private static final String TOKEN = "ntn_secret_must_never_be_persisted";

    @Test
    void retrievesAllowlistedPageAndRecursivelyPaginatesOpaqueBlockCursors() {
        FixtureHttpPort http = new FixtureHttpPort();
        http.respond("/v1/pages/page-1", null, ok("""
                {
                  "object":"page",
                  "id":"page-1",
                  "last_edited_time":"2026-07-28T10:15:30Z",
                  "archived":false,
                  "in_trash":false,
                  "url":"https://www.notion.so/Knowledge-Page-page-1",
                  "parent":{"type":"page_id","page_id":"parent-page"},
                  "properties":{
                    "Name":{"type":"title","title":[{"plain_text":"Knowledge Page"}]}
                  }
                }
                """));
        http.respond("/v1/blocks/page-1/children", null, ok("""
                {
                  "results":[
                    {"id":"block-1","type":"paragraph","has_children":false,
                     "paragraph":{"rich_text":[
                       {"plain_text":"Root evidence"},
                       {"type":"mention","plain_text":"",
                        "mention":{"type":"page","page":{"id":"linked-page"}}}
                     ]}},
                    {"id":"block-2","type":"toggle","has_children":true,
                     "toggle":{"rich_text":[{"plain_text":"Nested context"}]}},
                    {"id":"block-link","type":"link_to_page","has_children":false,
                     "link_to_page":{"type":"page_id","page_id":"linked-two"}}
                  ],
                  "has_more":true,
                  "next_cursor":"opaque+/== cursor"
                }
                """));
        http.respond("/v1/blocks/block-2/children", null, ok("""
                {
                  "results":[
                    {"id":"block-2-1","type":"paragraph","has_children":false,
                     "paragraph":{"rich_text":[{"plain_text":"Deep evidence"}]}}
                  ],
                  "has_more":false,
                  "next_cursor":null
                }
                """));
        http.respond("/v1/blocks/page-1/children", "opaque+/== cursor", ok("""
                {
                  "results":[
                    {"id":"block-3","type":"heading_2","has_children":false,
                     "heading_2":{"rich_text":[{"plain_text":"Second page"}]}}
                  ],
                  "has_more":false,
                  "next_cursor":null
                }
                """));
        NotionApiSourceRegistry registry = registry(
                http, Set.of("page-1"), NotionSyncLimits.defaults());

        SourcePage first = registry.scan().getFirst();
        SourcePage second = registry.scan().getFirst();

        assertThat(first.title()).isEqualTo("Knowledge Page");
        assertThat(first.lastEditedTime()).isEqualTo(Instant.parse("2026-07-28T10:15:30Z"));
        assertThat(first.content()).isEqualTo("""
                Root evidence

                Nested context

                  Deep evidence

                ## Second page""");
        assertThat(first.contentHash()).isEqualTo(second.contentHash());
        assertThat(first.tombstone()).isFalse();
        assertThat(first.metadata().sourceUri()).isEqualTo("notion://page/page-1");
        assertThat(first.metadata().canonicalUri())
                .isEqualTo("https://www.notion.so/Knowledge-Page-page-1");
        assertThat(first.metadata().projectId()).isEqualTo("notion");
        assertThat(first.metadata().sourceParentId()).isEqualTo("parent-page");
        assertThat(first.metadata().linkedDocumentIds())
                .containsExactly("linked-page", "linked-two");
        assertThat(first.metadata().language()).isEqualTo("en");
        assertThat(http.requests)
                .allMatch(request -> request.notionVersion().equals("2026-03-11"))
                .anyMatch(request -> "opaque+/== cursor".equals(
                        request.query().get("start_cursor")));
        assertThat(http.requests).noneMatch(request -> request.toString().contains(TOKEN));
        assertThat(first.toString()).doesNotContain(TOKEN);
    }

    @Test
    void emitsTombstonesForDeletedAndInaccessiblePages() {
        FixtureHttpPort http = new FixtureHttpPort();
        http.respond("/v1/pages/deleted", null, ok("""
                {
                  "object":"page",
                  "id":"deleted",
                  "last_edited_time":"2026-07-28T11:00:00Z",
                  "archived":false,
                  "in_trash":true,
                  "properties":{}
                }
                """));
        http.respond("/v1/pages/forbidden", null,
                new NotionHttpResponse(403, "{\"object\":\"error\"}"));
        NotionApiSourceRegistry registry = registry(
                http, Set.of("deleted", "forbidden"), NotionSyncLimits.defaults());

        List<SourcePage> pages = registry.scan();

        assertThat(pages).extracting(SourcePage::pageId)
                .containsExactly("deleted", "forbidden");
        assertThat(pages).allMatch(SourcePage::tombstone);
        assertThat(pages).allMatch(page -> page.content().isEmpty());
    }

    @Test
    void failsClosedWhenDepthOrBlockLimitIsExceeded() {
        FixtureHttpPort depthHttp = nestedPageFixture();
        NotionApiSourceRegistry depthRegistry = registry(
                depthHttp, Set.of("page-1"),
                new NotionSyncLimits(0, 100, Duration.ofSeconds(5)));
        assertThatThrownBy(depthRegistry::scan)
                .isInstanceOf(KnowledgeSourceSyncException.class)
                .hasMessageContaining("depth");

        FixtureHttpPort blockHttp = nestedPageFixture();
        NotionApiSourceRegistry blockRegistry = registry(
                blockHttp, Set.of("page-1"),
                new NotionSyncLimits(5, 1, Duration.ofSeconds(5)));
        assertThatThrownBy(blockRegistry::scan)
                .isInstanceOf(KnowledgeSourceSyncException.class)
                .hasMessageContaining("block limit");
    }

    @Test
    void failsClosedWhenOverallTimeoutExpires() {
        AtomicLong ticker = new AtomicLong();
        FixtureHttpPort http = new FixtureHttpPort() {
            @Override
            public NotionHttpResponse execute(NotionHttpRequest request) {
                NotionHttpResponse response = super.execute(request);
                ticker.addAndGet(Duration.ofMillis(11).toNanos());
                return response;
            }
        };
        http.respond("/v1/pages/page-1", null, pageResponse());
        NotionApiSourceRegistry registry = new NotionApiSourceRegistry(
                "notion", ACL, Set.of("page-1"), new NotionApiCredentials(TOKEN),
                http, new ObjectMapper(), new NotionSyncLimits(5, 100, Duration.ofMillis(10)),
                "2026-03-11", ticker::get);

        assertThatThrownBy(registry::scan)
                .isInstanceOf(KnowledgeSourceSyncException.class)
                .hasMessageContaining("timeout");
    }

    @Test
    void credentialAndRequestStringRepresentationsAlwaysRedactToken() {
        NotionApiCredentials credentials = new NotionApiCredentials(TOKEN);
        NotionHttpRequest request = new NotionHttpRequest(
                "/v1/pages/page-1", Map.of(), "2026-03-11",
                credentials, Duration.ofSeconds(1));

        assertThat(credentials.toString()).doesNotContain(TOKEN).contains("REDACTED");
        assertThat(request.toString()).doesNotContain(TOKEN).contains("REDACTED");
    }

    @Test
    void transportFailureCannotPropagateASecretBearingMessageOrCause() {
        NotionHttpPort leakingTransport = request -> {
            throw new IllegalStateException(
                    "transport leaked " + request.credentials().authorizationHeader());
        };
        NotionApiSourceRegistry registry = registry(
                leakingTransport, Set.of("page-1"), NotionSyncLimits.defaults());

        assertThatThrownBy(registry::scan)
                .isInstanceOf(KnowledgeSourceSyncException.class)
                .hasMessage("Notion HTTP transport failed")
                .satisfies(error -> {
                    assertThat(error.getCause()).isNull();
                    assertThat(error.toString()).doesNotContain(TOKEN);
                });
    }

    private static NotionApiSourceRegistry registry(
            NotionHttpPort http, Set<String> allowlist, NotionSyncLimits limits) {
        return new NotionApiSourceRegistry(
                "notion", ACL, allowlist, new NotionApiCredentials(TOKEN),
                http, new ObjectMapper(), limits);
    }

    private static FixtureHttpPort nestedPageFixture() {
        FixtureHttpPort http = new FixtureHttpPort();
        http.respond("/v1/pages/page-1", null, pageResponse());
        http.respond("/v1/blocks/page-1/children", null, ok("""
                {
                  "results":[
                    {"id":"parent","type":"toggle","has_children":true,
                     "toggle":{"rich_text":[{"plain_text":"Parent"}]}},
                    {"id":"sibling","type":"paragraph","has_children":false,
                     "paragraph":{"rich_text":[{"plain_text":"Sibling"}]}}
                  ],
                  "has_more":false,
                  "next_cursor":null
                }
                """));
        http.respond("/v1/blocks/parent/children", null, ok("""
                {
                  "results":[
                    {"id":"child","type":"paragraph","has_children":false,
                     "paragraph":{"rich_text":[{"plain_text":"Child"}]}}
                  ],
                  "has_more":false,
                  "next_cursor":null
                }
                """));
        return http;
    }

    private static NotionHttpResponse pageResponse() {
        return ok("""
                {
                  "object":"page",
                  "id":"page-1",
                  "last_edited_time":"2026-07-28T10:15:30Z",
                  "archived":false,
                  "in_trash":false,
                  "properties":{"Name":{"type":"title","title":[{"plain_text":"Page"}]}}
                }
                """);
    }

    private static NotionHttpResponse ok(String body) {
        return new NotionHttpResponse(200, body);
    }

    private static class FixtureHttpPort implements NotionHttpPort {
        private final Map<RequestKey, NotionHttpResponse> responses = new LinkedHashMap<>();
        private final List<NotionHttpRequest> requests = new ArrayList<>();

        void respond(String path, String cursor, NotionHttpResponse response) {
            responses.put(new RequestKey(path, cursor), response);
        }

        @Override
        public NotionHttpResponse execute(NotionHttpRequest request) {
            requests.add(request);
            RequestKey key = new RequestKey(request.path(), request.query().get("start_cursor"));
            NotionHttpResponse response = responses.get(key);
            if (response == null) throw new AssertionError("missing fixture response: " + key);
            return response;
        }
    }

    private record RequestKey(String path, String cursor) {
    }
}

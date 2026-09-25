package com.meguri.core.resources;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceSearchControllerTest {
    @Test
    void exposesMetadataOnlyCandidatesAndBoundsTheRequestedLimit() {
        int[] observedLimit = new int[1];
        ResourceSearchGateway gateway = (query, limit) -> {
            observedLimit[0] = limit;
            return Mono.just(new ResourceSearchResponse(query, true, List.of(
                    new LocalResourceCandidate("r-1", "日报.md", "D:/notes/日报.md", "file", 42L,
                            "2026-07-22T09:00:00+08:00")), ""));
        };
        WebTestClient client = WebTestClient.bindToController(new ResourceSearchController(gateway)).build();

        client.get().uri(uri -> uri.path("/v1/resources/search")
                        .queryParam("q", "日报").queryParam("limit", "999").build())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.available").isEqualTo(true)
                .jsonPath("$.candidates[0].name").isEqualTo("日报.md")
                .jsonPath("$.candidates[0].path").isEqualTo("D:/notes/日报.md")
                .jsonPath("$.candidates[0].content").doesNotExist();

        assertThat(observedLimit[0]).isEqualTo(20);
    }

    @Test
    void blankQueriesDoNotCallTheGateway() {
        ResourceSearchGateway gateway = (query, limit) -> Mono.error(new AssertionError("must not search"));
        WebTestClient client = WebTestClient.bindToController(new ResourceSearchController(gateway)).build();

        client.get().uri("/v1/resources/search?q=")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.available").isEqualTo(false)
                .jsonPath("$.message").value(value -> assertThat(String.valueOf(value)).contains("@"));
    }

    @Test
    void searchFailuresStayAReadOnlyUnavailableResult() {
        ResourceSearchGateway gateway = (query, limit) -> Mono.error(new IllegalStateException("offline"));
        WebTestClient client = WebTestClient.bindToController(new ResourceSearchController(gateway)).build();

        client.get().uri("/v1/resources/search?q=report")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.available").isEqualTo(false)
                .jsonPath("$.candidates.length()").isEqualTo(0);
    }
}

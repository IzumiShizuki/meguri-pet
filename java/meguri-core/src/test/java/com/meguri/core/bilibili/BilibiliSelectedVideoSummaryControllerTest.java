package com.meguri.core.bilibili;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

class BilibiliSelectedVideoSummaryControllerTest {
    @Test
    void requiresAnExplicitBvidAndDoesNotPretendContentWasRead() {
        WebTestClient client = WebTestClient.bindToController(
                new BilibiliSelectedVideoSummaryController(
                        new UnavailableBilibiliSelectedVideoSummaryGateway()))
                .build();

        BilibiliSelectedVideoSummaryResult result = client.post()
                .uri("/v1/daily/bilibili/selected-summary")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"selected_bvids":["BV1xx411c7mD"],"instruction":"总结观点"}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody(BilibiliSelectedVideoSummaryResult.class)
                .returnResult().getResponseBody();

        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo("unavailable");
        assertThat(result.summary()).isNull();
        assertThat(result.selectedBvids()).containsExactly("BV1xx411c7mD");
    }

    @Test
    void rejectsMissingSelection() {
        WebTestClient.bindToController(
                        new BilibiliSelectedVideoSummaryController(
                                new UnavailableBilibiliSelectedVideoSummaryGateway()))
                .build()
                .post()
                .uri("/v1/daily/bilibili/selected-summary")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"selected_bvids\":[]}")
                .exchange()
                .expectStatus().isBadRequest();
    }
}

package com.meguri.core.input;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

class InputControllerTest {
    private final WebTestClient client = WebTestClient.bindToController(new InputController()).build();

    @Test
    void exposesThePrefixContractOverLoopbackHttp() {
        client.post().uri("/v1/input/resolve")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"message\":\"~增加导出功能\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.kind").isEqualTo("preprocess")
                .jsonPath("$.message").value(value -> org.assertj.core.api.Assertions.assertThat(String.valueOf(value))
                        .contains("目标：增加导出功能"));
    }
}

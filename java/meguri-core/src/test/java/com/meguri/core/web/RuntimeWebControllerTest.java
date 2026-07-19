package com.meguri.core.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.meguri.core.dto.ClientCapabilities;
import com.meguri.core.dto.RuntimeOverride;
import com.meguri.core.dto.TurnRequest;
import com.meguri.core.runtime.TurnOrchestrator;
import com.meguri.core.runtime.TurnRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.http.MediaType;

import java.time.OffsetDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Contract tests for REST, idempotency, cancellation and replayable SSE. */
class RuntimeWebControllerTest {
    private TurnOrchestrator orchestrator;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        orchestrator = new TurnOrchestrator();
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        client = WebTestClient.bindToController(new RuntimeWebController(orchestrator, mapper)).build();
    }

    @AfterEach
    void tearDown() {
        orchestrator.reset();
    }

    @Test
    void createsIdempotentTurnAndReplaysOrderedEvents() {
        String payload = """
                {"user_id":"u-test","client_id":"website","session_id":"s-test",
                 "message":"hello","client_capabilities":{"text":true,"sprite":true}}
                """;
        client.post().uri("/v1/turns")
                .header("Idempotency-Key", "same")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .exchange()
                .expectStatus().isAccepted()
                .expectBody().jsonPath("$.turn_id").exists();
        client.post().uri("/v1/turns")
                .header("Idempotency-Key", "same")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .exchange()
                .expectStatus().isAccepted()
                .expectBody().jsonPath("$.turn_id").exists();
        // Wait through the in-memory record rather than sleeping an arbitrary time.
        String id = orchestrator.turns().keySet().iterator().next();
        orchestrator.turn(id).getDone().join();
        assertThat(orchestrator.eventsFor("s-test")).isNotEmpty();
        assertThat(orchestrator.eventsFor("s-test").get(0).getSequence()).isEqualTo(1L);
        assertThat(orchestrator.eventsFor("s-test").get(orchestrator.eventsFor("s-test").size() - 1).getType())
                .isEqualTo("turn.completed");

        client.get().uri(uriBuilder -> uriBuilder.path("/v1/sessions/s-test/events")
                        .queryParam("after_sequence", 1).build())
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith("text/event-stream")
                .expectBody(String.class)
                .value(body -> assertThat(body).contains("text.delta").contains("turn.completed"));
    }

    @Test
    void runtimeStateAndOverrideHonorWireContract() {
        client.get().uri(uriBuilder -> uriBuilder.path("/v1/runtime/state")
                        .queryParam("user_id", "u-test").queryParam("client_id", "website").build())
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.client_id").isEqualTo("website")
                .jsonPath("$.allowed_expression_tags").isArray();

        client.post().uri(uriBuilder -> uriBuilder.path("/v1/runtime/override")
                        .queryParam("user_id", "u-test").build())
                .bodyValue(new RuntimeOverride(null, null, "05", OffsetDateTime.now().plusMinutes(1)))
                .exchange().expectStatus().isOk();
        client.get().uri(uriBuilder -> uriBuilder.path("/v1/runtime/state")
                        .queryParam("user_id", "u-test").build())
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.outfit_code").isEqualTo("05");
    }

    @Test
    void cancellationProducesTerminalCancelledStatus() {
        TurnRequest request = new TurnRequest("u-test", "website", "cancel-session", "cancel ".repeat(400),
                java.util.List.of(), new ClientCapabilities(true, true, false, false), null, null, true);
        TurnRecord record = orchestrator.start(request, null);
        client.post().uri("/v1/turns/{id}/cancel", record.getTurnId())
                .exchange().expectStatus().isOk();
        record.getDone().join();
        assertThat(record.statusValue()).isEqualTo("cancelled");
        assertThat(orchestrator.eventsFor("cancel-session").stream().map(event -> event.getType()))
                .contains("turn.cancelled").doesNotContain("turn.completed");
    }

    @Test
    void voiceCapabilityEmitsTtsRequestAfterText() {
        TurnRequest request = new TurnRequest("u-test", "desktop_pet", "voice-session", "hello",
                java.util.List.of(), new ClientCapabilities(true, true, true, false), null, null, true);
        TurnRecord record = orchestrator.start(request, null);
        record.getDone().join();
        var types = orchestrator.eventsFor("voice-session").stream().map(event -> event.getType()).toList();
        assertThat(types).containsSubsequence("text.completed", "tts.requested", "turn.completed");
    }
}

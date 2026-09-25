package com.meguri.core.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.meguri.core.adapter.domain.PlatformActorMapper;
import com.meguri.core.dto.ClientCapabilities;
import com.meguri.core.dto.RuntimeOverride;
import com.meguri.core.dto.TurnRequest;
import com.meguri.core.runtime.TurnOrchestrator;
import com.meguri.core.runtime.TurnRecord;
import com.meguri.core.security.CoreIdentityVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.http.MediaType;

import java.time.OffsetDateTime;
import java.util.List;
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
        client = WebTestClient.bindToController(new RuntimeWebController(orchestrator, mapper))
                .controllerAdvice(new AdapterProtocolExceptionHandler())
                .build();
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
        assertThat(orchestrator.eventsFor("s-test"))
                .allSatisfy(event -> {
                    assertThat(event.getProtocolVersion()).isEqualTo("1.0");
                    assertThat(event.getEventId()).startsWith("event_");
                });
        assertThat(orchestrator.eventsFor("s-test").stream().map(event -> event.getEventId()).distinct().toList())
                .hasSize(orchestrator.eventsFor("s-test").size());
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
    void canonicalHelloBindsIdentityAndDrivesTurnSnapshot() {
        client.post().uri("/v1/hello")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "protocol_versions":["1.9","1.0"],
                          "identity":{
                            "meguri_user":{"id":"u-canonical"},
                            "platform_actor":{"platform":"meguri.website","actor_id":"account-42"},
                            "client_instance":{"id":"browser-tab-01","profile":"website"},
                            "session":{"id":"s-canonical"}
                          },
                          "capabilities":{
                            "text":true,"voice":true,"sprite":true,
                            "screen_context":false,"formal_memory":true,"sse":true
                          },
                          "permissions":{
                            "screen_read":false,"microphone":true,"audio_playback":true,
                            "notifications":false,"formal_memory_write":true
                          },
                          "future_optional":{"accepted":true}
                        }
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.selected_protocol_version").isEqualTo("1.0")
                .jsonPath("$.effective_capabilities.voice").isEqualTo(true)
                .jsonPath("$.granted_permissions.formal_memory_write").isEqualTo(true)
                .jsonPath("$.server_capabilities_revision").exists();

        Map<?, ?> created = client.post().uri("/v1/turns")
                .header("Idempotency-Key", "canonical-turn")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "protocol_version":"1.7",
                          "identity":{
                            "meguri_user":{"id":"u-canonical"},
                            "platform_actor":{"platform":"meguri.website","actor_id":"account-42"},
                            "client_instance":{"id":"browser-tab-01","profile":"website"},
                            "session":{"id":"s-canonical"}
                          },
                          "message":"hello canonical",
                          "retrieval_mode":"NONE"
                        }
                        """)
                .exchange()
                .expectStatus().isAccepted()
                .expectBody(Map.class)
                .returnResult()
                .getResponseBody();
        assertThat(created).isNotNull();
        assertThat(created.get("protocol_version")).isEqualTo("1.0");
        assertThat(created.get("events_url"))
                .isEqualTo("/v1/sessions/s-canonical/events");

        String turnId = String.valueOf(created.get("turn_id"));
        TurnRecord turn = orchestrator.turn(turnId);
        turn.getDone().join();
        assertThat(turn.getRequest().getPlatformId()).isEqualTo("meguri.website");
        assertThat(turn.getRequest().getPlatformActorId())
                .isEqualTo(PlatformActorMapper.hash(
                        "meguri.website", "account-42"));
        assertThat(turn.getRequest().getClientInstanceId()).isEqualTo("browser-tab-01");

        List<String> onceIds = orchestrator.eventsFor("s-canonical").stream()
                .filter(event -> "tts.requested".equals(event.getType()))
                .map(event -> event.getEventId())
                .toList();
        assertThat(onceIds).hasSize(1);
        client.get().uri("/v1/sessions/s-canonical/snapshot")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.protocol_version").isEqualTo("1.0")
                .jsonPath("$.session_id").isEqualTo("s-canonical")
                .jsonPath("$.turns[0].turn_id").isEqualTo(turnId)
                .jsonPath("$.processed_once_event_ids[0]").isEqualTo(onceIds.getFirst());
    }

    @Test
    void canonicalProtocolErrorsAreStableAndFailClosed() {
        client.post().uri("/v1/hello")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "protocol_versions":["2.0"],
                          "identity":{
                            "meguri_user":{"id":"u-major"},
                            "platform_actor":{"platform":"meguri.website","actor_id":"account-1"},
                            "client_instance":{"id":"browser-major","profile":"website"},
                            "session":{"id":"s-major"}
                          },
                          "capabilities":{
                            "text":true,"voice":false,"sprite":false,
                            "screen_context":false,"formal_memory":false,"sse":true
                          },
                          "permissions":{
                            "screen_read":false,"microphone":false,"audio_playback":false,
                            "notifications":false,"formal_memory_write":false
                          }
                        }
                        """)
                .exchange()
                .expectStatus().isEqualTo(426)
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("UNSUPPORTED_PROTOCOL_MAJOR")
                .jsonPath("$.error.retryable").isEqualTo(false);

        client.post().uri("/v1/turns")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "protocol_version":"1.0",
                          "identity":{
                            "meguri_user":{"id":"u-unbound"},
                            "platform_actor":{"platform":"meguri.website","actor_id":"account-2"},
                            "client_instance":{"id":"never-hello","profile":"website"},
                            "session":{"id":"s-unbound"}
                          },
                          "message":"must fail closed"
                        }
                        """)
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("CAPABILITY_UNAVAILABLE");
    }

    @Test
    void rejectsIdempotencyKeyReuseWithDifferentPayload() {
        client.post().uri("/v1/turns")
                .header("Idempotency-Key", "conflict")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"user_id":"u-test","client_id":"website","session_id":"s-conflict",
                         "message":"first","client_capabilities":{"text":true,"sprite":true}}
                        """)
                .exchange().expectStatus().isAccepted();

        client.post().uri("/v1/turns")
                .header("Idempotency-Key", "conflict")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"user_id":"u-test","client_id":"website","session_id":"s-conflict",
                         "message":"different","client_capabilities":{"text":true,"sprite":true}}
                        """)
                .exchange().expectStatus().isEqualTo(409);
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

    @Test
    void firstSubscriptionReplaysOnceEventsProducedBeforeConnection() {
        TurnRequest request = new TurnRequest("u-test", "desktop_pet", "late-subscriber", "hello",
                java.util.List.of(), new ClientCapabilities(true, true, true, false), null, null, true);
        TurnRecord record = orchestrator.start(request, null);
        record.getDone().join();

        client.get().uri(uriBuilder -> uriBuilder.path("/v1/sessions/late-subscriber/events")
                        .queryParam("after_sequence", 0).build())
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> assertThat(body)
                        .contains("event:tts.requested")
                        .contains("\"replay_policy\":\"ONCE\""));
    }

    @Test
    void hostedTurnStatusAndCancellationRequireResourceOwnership() {
        CoreIdentityVerifier verifier = new CoreIdentityVerifier(
                true, "tenant-a", "", "shared-secret");
        WebTestClient hosted = WebTestClient.bindToController(
                new RuntimeWebController(orchestrator, orchestrator,
                        new ObjectMapper().registerModule(new JavaTimeModule()), verifier)).build();
        TurnRecord record = orchestrator.start(new TurnRequest(
                "owner", "website", "owned-session", "hello"));

        hosted.get().uri("/v1/turns/{id}", record.getTurnId())
                .headers(headers -> identityHeaders(headers, "attacker", "owned-session"))
                .exchange().expectStatus().isForbidden();
        hosted.post().uri("/v1/turns/{id}/cancel", record.getTurnId())
                .headers(headers -> identityHeaders(headers, "attacker", "owned-session"))
                .exchange().expectStatus().isForbidden();

        hosted.get().uri("/v1/turns/{id}", record.getTurnId())
                .headers(headers -> identityHeaders(headers, "owner", "owned-session"))
                .exchange().expectStatus().isOk();
    }

    private static void identityHeaders(org.springframework.http.HttpHeaders headers,
                                        String userId, String sessionId) {
        headers.setBearerAuth("shared-secret");
        headers.set("X-Meguri-Tenant-ID", "tenant-a");
        headers.set("X-Meguri-User-ID", userId);
        headers.set("X-Meguri-Client-ID", "website");
        headers.set("X-Meguri-Session-ID", sessionId);
    }
}

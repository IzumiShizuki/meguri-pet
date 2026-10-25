package com.meguri.core.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.adapter.application.ClientHandshakeService;
import com.meguri.core.llm.LlmProvider;
import com.meguri.core.security.CoreIdentityVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DesktopMemoryCorrectionHttpTest {
    @TempDir Path temporary;
    final ObjectMapper mapper = new ObjectMapper();
    final String memoryId = UUID.randomUUID().toString();
    final String versionId = UUID.randomUUID().toString();
    PythonMemoryGateway gateway;
    WebTestClient http;

    @BeforeEach void setup() throws Exception {
        Path token = temporary.resolve("desktop-token");
        Files.writeString(token, "correction-test-token");
        var identity = new DesktopMemoryIdentity(new CoreIdentityVerifier(false, "tenant-a",
                token.toString(), "", "owner", "desktop_pet"), token.toString(), "owner");
        gateway = mock(PythonMemoryGateway.class);
        when(gateway.desktopCall(anyString(), anyMap())).thenAnswer(call -> Mono.just(mapper.valueToTree(call.getArgument(1))));
        var service = new DesktopMemoryService(List::of, gateway, mock(LlmProvider.class));
        http = WebTestClient.bindToController(new DesktopMemoryController(identity, service, mock(ClientHandshakeService.class)))
                .build().mutate().defaultHeader("Authorization", "Bearer correction-test-token")
                .defaultHeader("X-Meguri-Tenant-ID", "tenant-a").defaultHeader("X-Meguri-User-ID", "owner")
                .defaultHeader("X-Meguri-Client-ID", "desktop_pet").defaultHeader("X-Meguri-Session-ID", "session-a").build();
    }

    @Test void detailUsesAuthenticatedScopeAndPathMemory() {
        http.get().uri("/v1/memory/desktop/memories/" + memoryId).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.user_id").isEqualTo("owner")
                .jsonPath("$.memory_id").isEqualTo(memoryId).jsonPath("$.session_id").isEqualTo("session-a");
        verify(gateway).desktopCall(eq("detail"), anyMap());
    }

    @Test void correctionForwardsOnlyBoundedValidatedFieldsAndRequestId() {
        submit(Map.of("base_version_id", versionId, "content_text", "  用户喜欢咖啡  ", "change_reason", " 纠正偏好 "), true)
                .expectStatus().isOk().expectBody().jsonPath("$.content_text").isEqualTo("用户喜欢咖啡")
                .jsonPath("$.base_version_id").isEqualTo(versionId).jsonPath("$.request_id").isEqualTo("correction-1")
                .jsonPath("$.client_id").isEqualTo("desktop_pet").jsonPath("$.change_reason").isEqualTo("纠正偏好");
        verify(gateway).desktopCall(eq("correct"), anyMap());
    }

    @Test void malformedUnknownOrUnboundedFieldsNeverReachBridge() {
        var valid = Map.<String, Object>of("base_version_id", versionId, "content_text", "Coffee", "change_reason", "Updated preference");
        List<Map<String, Object>> invalid = new ArrayList<>();
        for (var override : List.of(Map.of("content_text", " "), Map.of("content_text", "a".repeat(4001)),
                Map.of("content_text", 5), Map.of("change_reason", ""), Map.of("change_reason", "a".repeat(1001)),
                Map.of("base_version_id", "1-1-1-1-1"), Map.of("base_version_id", "invalid"),
                Map.of("actor_id", "admin"), Map.of("user_id", "foreign"), Map.of("content_json", Map.of("relationship_level", 10)))) {
            var body = new java.util.HashMap<>(valid); body.putAll(override); invalid.add(body);
        }
        invalid.add(Map.of("content_text", "Coffee", "change_reason", "Missing version"));
        for (var body : invalid) submit(body, true).expectStatus().isBadRequest();
        submit(List.of(valid), true).expectStatus().isBadRequest();
        submit(valid, false).expectStatus().isBadRequest();
        verifyNoInteractions(gateway);
    }

    @Test void foreignIdentityAndInvalidPathAreRejected() {
        http.get().uri("/v1/memory/desktop/memories/" + memoryId).header("X-Meguri-User-ID", "foreign")
                .exchange().expectStatus().isForbidden();
        http.get().uri("/v1/memory/desktop/memories/" + memoryId).header("Authorization", "Bearer wrong")
                .exchange().expectStatus().isUnauthorized();
        http.get().uri("/v1/memory/desktop/memories/not-a-uuid").exchange().expectStatus().isBadRequest();
        verifyNoInteractions(gateway);
    }

    @Test void gatewayAllowsNewOperationsThroughExistingBridgeOnly() {
        AtomicReference<String> uri = new AtomicReference<>();
        var client = WebClient.builder().exchangeFunction(request -> {
            uri.set(request.url().getPath());
            return Mono.just(ClientResponse.create(org.springframework.http.HttpStatus.OK)
                    .header("Content-Type", "application/json").body("{\"ok\":true}").build());
        });
        var real = new PythonMemoryGateway(client, mapper, "http://memory-test", "test-bridge", "", 1000);
        for (String op : List.of("detail", "correct")) {
            assertThat(real.desktopCall(op, Map.of()).block().path("ok").asBoolean()).isTrue();
            assertThat(uri.get()).isEqualTo("/internal/memory/desktop/" + op);
        }
    }

    private WebTestClient.ResponseSpec submit(Object body, boolean requestId) {
        var request = http.post().uri("/v1/memory/desktop/memories/" + memoryId + "/corrections").contentType(MediaType.APPLICATION_JSON);
        if (requestId) request.header("X-Request-ID", "correction-1");
        return request.bodyValue(body).exchange();
    }
}

package com.meguri.core.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.adapter.application.ClientHandshakeService;
import com.meguri.core.llm.LlmProvider;
import com.meguri.core.runtime.SessionContextStore;
import com.meguri.core.security.CoreIdentityVerifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DesktopMemoryTest {
    @TempDir Path temporary;
    private final ObjectMapper mapper = new ObjectMapper();

    private MockServerWebExchange exchange(String user) {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/v1/memory/desktop/status")
                .header("Authorization", "Bearer desktop-test")
                .header("X-Meguri-Tenant-ID", "tenant-a").header("X-Meguri-User-ID", user)
                .header("X-Meguri-Client-ID", "desktop_pet").header("X-Meguri-Session-ID", "session-a"));
    }

    private DesktopMemoryIdentity identity(boolean hosted, String users) throws Exception {
        Path token = temporary.resolve("token.txt");
        Files.writeString(token, "desktop-test");
        return new DesktopMemoryIdentity(new CoreIdentityVerifier(hosted, "tenant-a", token.toString(), "", users, "desktop_pet"), token.toString(), "owner");
    }

    @Test void localManagementStillRequiresCredentialAndRejectsForeignIdentity() throws Exception {
        DesktopMemoryIdentity identity = identity(false, "");
        assertThat(identity.verify(exchange("owner")).authorized()).isTrue();
        assertThatThrownBy(() -> identity.verify(exchange("other-user"))).isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThatThrownBy(() -> identity.verify(MockServerWebExchange.from(MockServerHttpRequest.get("/v1/memory/desktop/status"))))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test void hostedUserAndClientAllowListsRemainRequired() throws Exception {
        assertThat(identity(true, "").verify(exchange("owner")).authorized()).isFalse();
        assertThat(identity(true, "owner").verify(exchange("owner")).authorized()).isTrue();
    }

    @Test void clientCannotSupplyActorOrUserOverrideInMutationBody() throws Exception {
        DesktopMemoryController controller = new DesktopMemoryController(mock(DesktopMemoryIdentity.class), mock(DesktopMemoryService.class), mock(ClientHandshakeService.class));
        assertThatThrownBy(() -> controller.delete(java.util.UUID.randomUUID(), mapper.readTree("{\"user_id\":\"foreign\",\"actor_id\":\"admin\"}"), exchange("owner")))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test void unavailableBridgeHasTruthfulStatusAndNoFakeSuccess() {
        PythonMemoryGateway gateway = mock(PythonMemoryGateway.class);
        when(gateway.desktopCall(eq("status"), anyMap())).thenReturn(Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "bridge_not_configured")));
        DesktopMemoryService service = new DesktopMemoryService(List::of, gateway, mock(LlmProvider.class));
        Map<String, Object> status = service.status(new DesktopMemoryIdentity.Scope("tenant-a", "owner", "desktop_pet", "session-a", true)).block();
        assertThat(status).containsEntry("available", false).containsEntry("reason", "bridge_not_configured");
    }

    @Test void statusForwardsOnlyRecognizedBridgeCapabilitiesAndSupportsOlderBridges() throws Exception {
        PythonMemoryGateway gateway = mock(PythonMemoryGateway.class);
        when(gateway.desktopCall(eq("status"), anyMap())).thenReturn(
                Mono.just(mapper.readTree("{\"available\":true}")),
                Mono.just(mapper.readTree("{\"available\":true,\"capabilities\":[\"memory_detail\",\"memory_corrections\",\"admin\"]}")));
        DesktopMemoryService service = new DesktopMemoryService(List::of, gateway, mock(LlmProvider.class));
        var scope = new DesktopMemoryIdentity.Scope("tenant-a", "owner", "desktop_pet", "session-a", true);
        assertThat(service.status(scope).block()).containsEntry("capabilities", List.of());
        assertThat(service.status(scope).block()).containsEntry("capabilities", List.of("memory_detail", "memory_corrections"));
    }

    @Test void summaryUsesOnlyCurrentSessionBoundedRedactedHistoryAndCheckpointIdempotency() throws Exception {
        PythonMemoryGateway gateway = mock(PythonMemoryGateway.class);
        LlmProvider llm = mock(LlmProvider.class);
        when(llm.extractMemoryCandidates(anyList())).thenReturn(Mono.just(List.of()));
        when(gateway.desktopCall(eq("status"), anyMap())).thenReturn(Mono.just(mapper.readTree("{\"available\":true}")));
        AtomicReference<Map<String, Object>> written = new AtomicReference<>();
        when(gateway.desktopCall(eq("summary"), anyMap())).thenAnswer(call -> {
            written.set(call.getArgument(1));
            return Mono.just(mapper.readTree("{\"status\":\"persisted\"}"));
        });
        List<SessionContextStore.Message> history = new java.util.ArrayList<>();
        for (int index = 0; index < 24; index++) history.add(new SessionContextStore.Message(index % 2 == 0 ? "user" : "assistant", "message-" + index));
        history.set(23, new SessionContextStore.Message("assistant", "api_key=never-save"));
        DesktopMemoryService service = new DesktopMemoryService(() -> List.of(
                new SessionContextStore.Snapshot("other-user", "desktop_pet", "session-a", List.of(new SessionContextStore.Message("user", "foreign history"))),
                new SessionContextStore.Snapshot("owner", "desktop_pet", "session-a", history)), gateway, llm);
        var scope = new DesktopMemoryIdentity.Scope("tenant-a", "owner", "desktop_pet", "session-a", true);
        service.summarize(scope, 20, "request-a").block();
        service.summarize(scope, 20, "request-b").block();
        verify(gateway, times(1)).desktopCall(eq("summary"), anyMap());
        List<SessionContextStore.Message> messages = (List<SessionContextStore.Message>) written.get().get("messages");
        assertThat(messages).hasSize(20).extracting(SessionContextStore.Message::content)
                .doesNotContain("message-0", "foreign history", "api_key=never-save")
                .contains("[已省略可能包含敏感凭据的消息]");
        assertThat(written.get()).containsEntry("tenant_id", "tenant-a").containsEntry("user_id", "owner").containsEntry("session_id", "session-a");
    }
}

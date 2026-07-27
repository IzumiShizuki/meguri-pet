package com.meguri.core.security;

import com.meguri.core.dto.TurnRequest;
import com.meguri.core.dto.ClientCapabilities;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CoreIdentityVerifierTest {
    @Test
    void hostedMemoryPermissionRequiresAuthenticatedHeaderAndServerPolicy() {
        CoreIdentityVerifier verifier = new CoreIdentityVerifier(
                true, "tenant-a", "", "shared-secret", "user-a", "airi");
        TurnRequest request = new TurnRequest(
                "user-a", "airi", "session-a", "hello",
                java.util.List.of(), new ClientCapabilities(),
                null, null, true);

        TurnRequest denied = verifier.verifyBody(
                exchange(false), request);
        TurnRequest allowed = verifier.verifyBody(
                exchange(true), request);

        assertFalse(denied.isFormalMemoryAllowed());
        assertTrue(allowed.isFormalMemoryAllowed());
    }

    @Test
    void authenticatedAdapterCannotGrantFormalMemoryWithoutServerPolicy() {
        CoreIdentityVerifier verifier = new CoreIdentityVerifier(
                true, "tenant-a", "", "shared-secret");
        TurnRequest request = new TurnRequest(
                "user-a", "airi", "session-a", "hello",
                java.util.List.of(), new ClientCapabilities(),
                null, null, true);

        TurnRequest denied = verifier.verifyBody(exchange(true), request);

        assertFalse(denied.isFormalMemoryAllowed());
    }

    @Test
    void hostedResourceScopeCannotBeChangedByAnAuthenticatedCaller() {
        CoreIdentityVerifier verifier = new CoreIdentityVerifier(
                true, "tenant-a", "", "shared-secret");

        assertThatThrownBy(() -> verifier.verifyScope(
                        exchange(false), "different-user", "airi", "session-a"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("does not belong");
    }

    private static MockServerWebExchange exchange(boolean formalMemoryAllowed) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest
                .post("/v1/turns")
                .header(HttpHeaders.AUTHORIZATION, "Bearer shared-secret")
                .header("X-Meguri-Tenant-ID", "tenant-a")
                .header("X-Meguri-User-ID", "user-a")
                .header("X-Meguri-Client-ID", "airi")
                .header("X-Meguri-Session-ID", "session-a")
                .header("X-Meguri-Formal-Memory-Allowed", Boolean.toString(formalMemoryAllowed));
        return MockServerWebExchange.from(builder.build());
    }
}

package com.meguri.core.memory;

import com.meguri.core.security.CoreIdentityVerifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

/** A dedicated desktop credential remains required even in a local-mock Core. */
@Component
public final class DesktopMemoryIdentity {
    private final CoreIdentityVerifier verifier;
    private final String token;
    private final String userId;

    public DesktopMemoryIdentity(CoreIdentityVerifier verifier,
            @Value("${meguri.memory.desktop-token-file:${meguri.security.shared-token-file:}}") String tokenFile,
            @Value("${meguri.memory.desktop-user-id:local-airi-user}") String userId) {
        this.verifier = verifier;
        this.userId = userId;
        String value = "";
        try {
            if (tokenFile != null && !tokenFile.isBlank() && Path.of(tokenFile).isAbsolute())
                value = Files.readString(Path.of(tokenFile)).trim();
        } catch (Exception ignored) { }
        this.token = value;
    }

    public Scope verify(ServerWebExchange exchange) {
        return verify(exchange, false);
    }

    /** Legacy AIRI supervision shares host authentication without granting memory management. */
    public Scope verifyTraining(ServerWebExchange exchange) {
        return verify(exchange, true);
    }

    private Scope verify(ServerWebExchange exchange, boolean allowAiri) {
        if (token.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "desktop_auth_not_configured");
        String authorization = header(exchange, "Authorization");
        String supplied = authorization.startsWith("Bearer ") ? authorization.substring(7).trim() : "";
        if (supplied.isBlank() || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "desktop_auth_required");
        String tenant = header(exchange, "X-Meguri-Tenant-ID");
        String user = header(exchange, "X-Meguri-User-ID");
        String client = header(exchange, "X-Meguri-Client-ID");
        String session = header(exchange, "X-Meguri-Session-ID");
        if (!verifier.tenantId().equals(tenant) || !userId.equals(user)
                || !("desktop_pet".equals(client) || (allowAiri && "airi".equals(client)))
                || !session.matches("[A-Za-z0-9._:-]{1,200}"))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "desktop_identity_mismatch");
        verifier.verifyScope(exchange, user, client, session);
        return new Scope(tenant, user, client, session,
                "desktop_pet".equals(client) && verifier.formalMemoryAllowed(user, client, true));
    }

    private static String header(ServerWebExchange exchange, String name) {
        String value = exchange.getRequest().getHeaders().getFirst(name);
        return value == null ? "" : value.trim();
    }

    public record Scope(String tenantId, String userId, String clientId, String sessionId, boolean authorized) {
        public java.util.Map<String, Object> payload() {
            return new java.util.LinkedHashMap<>(java.util.Map.of("tenant_id", tenantId, "user_id", userId,
                    "client_id", clientId, "session_id", sessionId));
        }
    }
}

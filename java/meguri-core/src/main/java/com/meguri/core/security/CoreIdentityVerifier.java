package com.meguri.core.security;

import com.meguri.core.dto.TurnRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Protects the loopback Core when it is placed behind AstrBot or OpenResty.
 * Local mock runs keep the filter disabled; hosted runs require a bearer token
 * and an identity header set that matches the request contract.
 */
@Component
public final class CoreIdentityVerifier implements WebFilter {
    private static final Set<String> CLIENTS =
            Set.of("airi", "astrbot", "desktop_pet", "website", "custom");

    private final boolean required;
    private final String tenantId;
    private final String expectedToken;
    private final Set<String> formalMemoryUsers;
    private final Set<String> formalMemoryClients;

    @Autowired
    public CoreIdentityVerifier(
            @Value("${meguri.security.auth-required:false}") boolean required,
            @Value("${meguri.security.tenant-id:meguri-local}") String tenantId,
            @Value("${meguri.security.shared-token-file:}") String tokenFile,
            @Value("${meguri.security.shared-token:}") String inlineToken,
            @Value("${meguri.security.formal-memory-allowed-users:}") String formalMemoryUsers,
            @Value("${meguri.security.formal-memory-allowed-clients:}") String formalMemoryClients) {
        this.required = required;
        this.tenantId = tenantId == null || tenantId.isBlank() ? "meguri-local" : tenantId.trim();
        this.expectedToken = readToken(tokenFile, inlineToken);
        this.formalMemoryUsers = parseList(formalMemoryUsers);
        this.formalMemoryClients = parseList(formalMemoryClients);
        if (required && expectedToken.isBlank()) {
            throw new IllegalStateException("Core auth is required but no shared token is configured");
        }
    }

    public CoreIdentityVerifier(
            boolean required, String tenantId, String tokenFile, String inlineToken) {
        this(required, tenantId, tokenFile, inlineToken, "", "");
    }

    public boolean required() {
        return required;
    }

    public String tenantId() {
        return tenantId;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!required || isPublic(exchange.getRequest().getPath().value())) {
            return chain.filter(exchange);
        }
        try {
            verifyHeaders(exchange);
            return chain.filter(exchange);
        } catch (ResponseStatusException error) {
            exchange.getResponse().setStatusCode(error.getStatusCode());
            return exchange.getResponse().setComplete();
        }
    }

    /** Ensures body identity cannot override the authenticated adapter identity. */
    public TurnRequest verifyBody(ServerWebExchange exchange, TurnRequest request) {
        if (!required) return request.withTenantId(tenantId);
        Identity identity = verifyScope(
                exchange, request.getUserId(), request.getClientId(), request.getSessionId());
        boolean requested = "true".equalsIgnoreCase(
                header(exchange, "X-Meguri-Formal-Memory-Allowed"));
        boolean allowed = formalMemoryAllowed(identity.userId(), identity.clientId(), requested);
        return request.withFormalMemoryAllowed(allowed).withTenantId(tenantId);
    }

    public boolean formalMemoryAllowed(String userId, String clientId, boolean requested) {
        if (!requested) return false;
        if (!required) return true;
        return formalMemoryUsers.contains(userId) && formalMemoryClients.contains(clientId);
    }

    public boolean screenContextAllowed(String userId, String clientId, boolean requested) {
        // Hosted screen context needs a dedicated account policy before it can be enabled.
        return requested && !required;
    }

    public boolean localResourceMetadataAllowed(String userId, String clientId, boolean requested) {
        // Local metadata is safe for loopback use but is fail-closed in hosted mode.
        return requested && !required;
    }

    /** Verifies that an authenticated adapter owns the requested resource scope. */
    public Identity verifyScope(ServerWebExchange exchange, String userId, String clientId, String sessionId) {
        if (!required) return new Identity(userId, clientId, sessionId);
        verifyHeaders(exchange);
        Identity identity = new Identity(
                header(exchange, "X-Meguri-User-ID"),
                header(exchange, "X-Meguri-Client-ID"),
                header(exchange, "X-Meguri-Session-ID"));
        if ((userId != null && !userId.equals(identity.userId()))
                || (clientId != null && !clientId.equals(identity.clientId()))
                || (sessionId != null && !sessionId.equals(identity.sessionId()))) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "requested resource does not belong to the authenticated identity");
        }
        return identity;
    }

    private void verifyHeaders(ServerWebExchange exchange) {
        String authorization = exchange.getRequest().getHeaders().getFirst("Authorization");
        String supplied = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring("Bearer ".length()).trim() : "";
        if (supplied.isBlank() || !MessageDigest.isEqual(
                supplied.getBytes(StandardCharsets.UTF_8), expectedToken.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Meguri Core authorization required");
        }
        String tenant = header(exchange, "X-Meguri-Tenant-ID");
        String user = header(exchange, "X-Meguri-User-ID");
        String client = header(exchange, "X-Meguri-Client-ID");
        String session = header(exchange, "X-Meguri-Session-ID");
        if (!tenantId.equals(tenant) || user.isBlank() || session.isBlank() || !CLIENTS.contains(client)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "authenticated Meguri identity is invalid");
        }
    }

    private static String header(ServerWebExchange exchange, String name) {
        String value = exchange.getRequest().getHeaders().getFirst(name);
        return value == null ? "" : value.trim();
    }

    private static boolean isPublic(String path) {
        return path.equals("/health") || path.equals("/health/live") || path.equals("/health/ready")
                || path.startsWith("/actuator/health");
    }

    private static String readToken(String configuredFile, String inlineToken) {
        String file = configuredFile == null ? "" : configuredFile.trim();
        if (!file.isBlank()) {
            try {
                Path path = Path.of(file);
                if (!path.isAbsolute() || !Files.isRegularFile(path)) {
                    throw new IllegalStateException("Meguri Core shared token file is unavailable");
                }
                String value = Files.readString(path).trim();
                if (!value.isBlank()) return value;
            } catch (IllegalStateException error) {
                throw error;
            } catch (Exception error) {
                throw new IllegalStateException("Meguri Core shared token file is unreadable", error);
            }
        }
        return inlineToken == null ? "" : inlineToken.trim();
    }

    private static Set<String> parseList(String configured) {
        if (configured == null || configured.isBlank()) return Set.of();
        return Arrays.stream(configured.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    public record Identity(String userId, String clientId, String sessionId) { }
}

package com.meguri.core.companion;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.meguri.core.security.CoreIdentityVerifier;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Positive;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/v1/companion/reach-outs")
public final class CompanionReachOutController {
    public record ScreenContext(@NotBlank @Size(max = 1000) String text,
            @NotBlank @Size(max = 80) String app, @Positive long observedAt) { }
    public record Request(
            @JsonProperty("user_id") @NotBlank @Size(max = 200) String userId,
            @JsonProperty("client_id") @Pattern(regexp = "desktop_pet") @NotBlank String clientId,
            @JsonProperty("session_id") @NotBlank @Pattern(regexp = "[A-Za-z0-9._:-]{1,200}") String sessionId,
            @JsonProperty("screen_context") @Valid ScreenContext screenContext,
            @JsonProperty("active_until") @Positive Long activeUntil) { }
    private final CompanionReachOutService service;
    private final CoreIdentityVerifier identity;

    public CompanionReachOutController(CompanionReachOutService service, CoreIdentityVerifier identity) {
        this.service = service;
        this.identity = identity;
    }

    @PostMapping
    public Mono<ResponseEntity<CompanionReachOutService.Candidate>> draft(
            ServerWebExchange exchange, @Valid @RequestBody Request request) {
        var screen = request.screenContext();
        return service.draft(scope(exchange, request), screen == null ? null
                        : new CompanionReachOutService.ScreenContext(screen.text(), screen.app(), screen.observedAt()),
                        request.activeUntil() == null ? Long.MAX_VALUE : request.activeUntil()).map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.noContent().build());
    }

    @PostMapping("/{id}/delivered")
    public Mono<Map<String, Boolean>> acknowledge(ServerWebExchange exchange,
            @PathVariable String id, @Valid @RequestBody Request request) {
        if (!id.matches("reachout:[a-f0-9-]{36}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid reach-out id");
        return service.acknowledge(scope(exchange, request), id).map(accepted -> Map.of("accepted", accepted));
    }

    private CompanionReachOutService.Scope scope(ServerWebExchange exchange, Request request) {
        identity.verifyScope(exchange, request.userId(), request.clientId(), request.sessionId());
        return new CompanionReachOutService.Scope(request.userId(), request.clientId(), request.sessionId());
    }
}

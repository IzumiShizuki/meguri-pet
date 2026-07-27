package com.meguri.core.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.dto.EventEnvelope;
import com.meguri.core.dto.RuntimeOverride;
import com.meguri.core.dto.RuntimeState;
import com.meguri.core.dto.TurnCreateResponse;
import com.meguri.core.dto.TurnRequest;
import com.meguri.core.dto.TurnStatusResponse;
import com.meguri.core.dto.TurnStatus;
import com.meguri.core.harness.EventCursor;
import com.meguri.core.harness.HarnessControlPlane;
import com.meguri.core.harness.TurnCommand;
import com.meguri.core.harness.TurnRuntime;
import com.meguri.core.runtime.TurnOrchestrator;
import com.meguri.core.runtime.IdempotencyConflictException;
import com.meguri.core.runtime.TurnEventTypes;
import com.meguri.core.security.CoreIdentityVerifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/** Reactive HTTP/SSE boundary corresponding to services/meguri_core/app.py. */
@RestController
@CrossOrigin(
        origins = {
                "http://127.0.0.1:4173", "http://127.0.0.1:5173",
                "http://localhost:4173", "http://localhost:5173"
        },
        methods = {org.springframework.web.bind.annotation.RequestMethod.GET,
                org.springframework.web.bind.annotation.RequestMethod.POST,
                org.springframework.web.bind.annotation.RequestMethod.DELETE,
                org.springframework.web.bind.annotation.RequestMethod.OPTIONS},
        allowedHeaders = {
                "Authorization", "Content-Type", "Idempotency-Key", "Last-Event-ID", "X-Request-ID",
                "X-Meguri-Tenant-ID", "X-Meguri-User-ID", "X-Meguri-Client-ID", "X-Meguri-Session-ID"
        })
public final class RuntimeWebController {
    private final TurnRuntime turnRuntime;
    private final HarnessControlPlane controlPlane;
    private final ObjectMapper objectMapper;
    private final CoreIdentityVerifier identityVerifier;

    public RuntimeWebController(TurnOrchestrator orchestrator, ObjectMapper objectMapper) {
        this(orchestrator, orchestrator, objectMapper,
                new CoreIdentityVerifier(false, "meguri-local", "", ""));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RuntimeWebController(TurnRuntime turnRuntime, HarnessControlPlane controlPlane,
                                ObjectMapper objectMapper,
                                CoreIdentityVerifier identityVerifier) {
        this.turnRuntime = turnRuntime;
        this.controlPlane = controlPlane;
        this.identityVerifier = identityVerifier;
        this.objectMapper = objectMapper == null
                ? new ObjectMapper().findAndRegisterModules()
                : objectMapper.findAndRegisterModules();
    }

    @GetMapping(path = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> health() {
        HarnessControlPlane.HarnessDescription description = controlPlane.describe();
        return Map.ofEntries(
                Map.entry("status", "ok"),
                Map.entry("service", "meguri-core"),
                Map.entry("runtime", "java"),
                Map.entry("protocol_version", description.protocolVersion()),
                Map.entry("build_id", description.buildId()),
                Map.entry("mode", "mock".equalsIgnoreCase(description.llmProvider())
                        ? "local-mock" : "configured-provider"),
                Map.entry("llm_provider", description.llmProvider()),
                Map.entry("rag_provider", description.ragProvider()),
                Map.entry("memory_provider", description.memoryProvider()),
                Map.entry("web_search_provider", description.webSearchProvider()),
                Map.entry("rag_chunks", description.ragChunks()));
    }

    @GetMapping(path = "/health/live", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> live() {
        return Map.of("status", "alive", "service", "meguri-core",
                "build_id", controlPlane.describe().buildId());
    }

    @GetMapping(path = "/health/ready", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> ready() {
        return ResponseEntity.ok(Map.of(
                "status", "ready",
                "build_id", controlPlane.describe().buildId(),
                "checks", Map.of("local_unmanaged", "passed")));
    }

    @PostMapping(path = "/v1/chat/respond", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<com.meguri.core.dto.ChatResponse>> chatRespond(
            @RequestBody TurnRequest request, org.springframework.web.server.ServerWebExchange exchange) {
        TurnRequest verified = identityVerifier.verifyBody(exchange, request);
        return turnRuntime.submit(new TurnCommand.Start(verified, null))
                .flatMap(accepted -> turnRuntime.events(new EventCursor(
                                accepted.sessionId(), accepted.lastSequence(), accepted.turnId(),
                                accepted.userId(), accepted.clientId()))
                        .filter(event -> TurnEventTypes.isTerminal(event.getType()))
                        .next()
                        .then(turnRuntime.snapshot(accepted.turnId())))
                .flatMap(snapshot -> snapshot.result() == null
                        ? Mono.error(new IllegalStateException(snapshot.error() == null
                                ? "turn ended without a response" : snapshot.error()))
                        : Mono.just(ResponseEntity.ok(snapshot.result())));
    }

    @PostMapping(path = "/v1/turns", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<TurnCreateResponse>> createTurn(
            @RequestBody TurnRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            org.springframework.web.server.ServerWebExchange exchange) {
        TurnRequest verified = identityVerifier.verifyBody(exchange, request);
        return turnRuntime.submit(new TurnCommand.Start(verified, idempotencyKey))
                .map(snapshot -> ResponseEntity.status(HttpStatus.ACCEPTED).body(new TurnCreateResponse(
                        snapshot.turnId(), snapshot.sessionId(), controlPlane.describe().buildId(),
                        TurnStatus.fromValue(snapshot.status()))))
                .onErrorMap(IdempotencyConflictException.class,
                        error -> new ResponseStatusException(HttpStatus.CONFLICT, error.getMessage(), error));
    }

    @GetMapping(path = "/v1/turns/{turnId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<TurnStatusResponse>> getTurn(
            @PathVariable String turnId,
            org.springframework.web.server.ServerWebExchange exchange) {
        return turnRuntime.snapshot(turnId)
                .map(snapshot -> {
                    identityVerifier.verifyScope(
                            exchange, snapshot.userId(), snapshot.clientId(), snapshot.sessionId());
                    return ResponseEntity.ok(new TurnStatusResponse(
                            snapshot.turnId(), snapshot.sessionId(), TurnStatus.fromValue(snapshot.status()),
                            controlPlane.describe().buildId(), snapshot.error()));
                })
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "turn not found")));
    }

    @PostMapping(path = "/v1/turns/{turnId}/cancel", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, String>>> cancelTurn(
            @PathVariable String turnId,
            org.springframework.web.server.ServerWebExchange exchange) {
        return turnRuntime.snapshot(turnId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "turn not found")))
                .doOnNext(snapshot -> identityVerifier.verifyScope(
                        exchange, snapshot.userId(), snapshot.clientId(), snapshot.sessionId()))
                .then(turnRuntime.submit(new TurnCommand.Cancel(turnId, "client_request")))
                .map(snapshot -> ResponseEntity.ok(Map.of(
                        "turn_id", turnId,
                        "status", snapshot.terminal() ? snapshot.status() : "cancel_requested")))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "turn not found")));
    }

    @GetMapping(path = "/v1/sessions/{sessionId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> sessionEvents(
            @PathVariable String sessionId,
            @RequestParam(name = "after_sequence", defaultValue = "0") long afterSequence,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId,
            ServerHttpResponse httpResponse,
            org.springframework.web.server.ServerWebExchange exchange) {
        httpResponse.getHeaders().setCacheControl("no-cache");
        httpResponse.getHeaders().set("X-Accel-Buffering", "no");
        httpResponse.getHeaders().set("X-Meguri-Build", controlPlane.describe().buildId());
        httpResponse.getHeaders().set("X-Meguri-Protocol", controlPlane.describe().protocolVersion());
        if (afterSequence < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "after_sequence must be non-negative");
        }
        long cursor = afterSequence;
        if (lastEventId != null && lastEventId.matches("\\d+")) {
            try {
                cursor = Math.max(cursor, Long.parseLong(lastEventId));
            } catch (NumberFormatException ignored) {
                // Keep the query cursor when the header overflows a long.
            }
        }
        CoreIdentityVerifier.Identity identity = identityVerifier.verifyScope(exchange, null, null, sessionId);
        return eventStream(sessionId, cursor, identity.userId(), identity.clientId());
    }

    @GetMapping(path = "/v1/runtime/state", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> runtimeState(
            @RequestParam(name = "user_id") String userId,
            @RequestParam(name = "client_id", defaultValue = "website") String clientId,
            @RequestParam(name = "session_id", defaultValue = "state") String sessionId,
            org.springframework.web.server.ServerWebExchange exchange) {
        identityVerifier.verifyScope(exchange, userId, clientId, sessionId);
        TurnRequest request = new TurnRequest(userId, clientId, sessionId, "state");
        RuntimeState state = controlPlane.resolvePersona(request);
        return asMap(state);
    }

    @PostMapping(path = "/v1/runtime/override", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> setRuntimeOverride(
            @RequestParam(name = "user_id") String userId,
            @RequestBody RuntimeOverride override,
            org.springframework.web.server.ServerWebExchange exchange) {
        identityVerifier.verifyScope(exchange, userId, null, null);
        controlPlane.setRuntimeOverride(userId, override);
        return Map.of("user_id", userId, "override", override);
    }

    @DeleteMapping(path = "/v1/runtime/override/{scope}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> clearRuntimeOverride(
            @PathVariable String scope,
            org.springframework.web.server.ServerWebExchange exchange) {
        identityVerifier.verifyScope(exchange, scope, null, null);
        controlPlane.clearRuntimeOverride(scope);
        return Map.of("scope", scope, "status", "cleared");
    }

    private Flux<ServerSentEvent<String>> eventStream(
            String sessionId, long initialCursor, String userId, String clientId) {
        return turnRuntime.events(new EventCursor(
                        sessionId, Math.max(0L, initialCursor), null, userId, clientId))
                .map(this::toSse);
    }

    private ServerSentEvent<String> toSse(EventEnvelope event) {
        try {
            return ServerSentEvent.<String>builder()
                    .id(Long.toString(event.getSequence()))
                    .event(event.getType())
                    .data(objectMapper.writeValueAsString(event))
                    .build();
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("failed to encode turn event", error);
        }
    }

    private Map<String, Object> asMap(Object value) {
        try {
            return objectMapper.convertValue(value, Map.class);
        } catch (IllegalArgumentException ignored) {
            return new LinkedHashMap<>();
        }
    }
}

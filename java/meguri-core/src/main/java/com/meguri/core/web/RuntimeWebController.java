package com.meguri.core.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.adapter.application.AdapterProtocolException;
import com.meguri.core.adapter.application.ClientHandshakeService;
import com.meguri.core.adapter.application.UnsupportedRequiredExtensionException;
import com.meguri.core.adapter.application.UnsupportedProtocolVersionException;
import com.meguri.core.adapter.domain.AdapterProtocolHello;
import com.meguri.core.adapter.domain.AdapterProtocolHelloResponse;
import com.meguri.core.adapter.domain.AdapterSessionSnapshotResponse;
import com.meguri.core.adapter.domain.AdapterTurnCreateRequest;
import com.meguri.core.adapter.domain.ClientBinding;
import com.meguri.core.adapter.domain.ClientHello;
import com.meguri.core.adapter.domain.ClientHelloResponse;
import com.meguri.core.adapter.infrastructure.InMemoryClientBindingRepository;
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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
                "X-Meguri-Tenant-ID", "X-Meguri-User-ID", "X-Meguri-Client-ID", "X-Meguri-Session-ID",
                "X-Meguri-Formal-Memory-Allowed"
        })
public final class RuntimeWebController {
    private final TurnRuntime turnRuntime;
    private final HarnessControlPlane controlPlane;
    private final ObjectMapper objectMapper;
    private final CoreIdentityVerifier identityVerifier;
    private final ClientHandshakeService clientHandshakeService;

    public RuntimeWebController(TurnOrchestrator orchestrator, ObjectMapper objectMapper) {
        this(orchestrator, orchestrator, objectMapper,
                new CoreIdentityVerifier(false, "meguri-local", "", ""),
                new ClientHandshakeService(new InMemoryClientBindingRepository()));
    }

    public RuntimeWebController(TurnRuntime turnRuntime, HarnessControlPlane controlPlane,
                                ObjectMapper objectMapper,
                                CoreIdentityVerifier identityVerifier) {
        this(turnRuntime, controlPlane, objectMapper, identityVerifier,
                new ClientHandshakeService(new InMemoryClientBindingRepository()));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RuntimeWebController(TurnRuntime turnRuntime, HarnessControlPlane controlPlane,
                                ObjectMapper objectMapper,
                                CoreIdentityVerifier identityVerifier,
                                ClientHandshakeService clientHandshakeService) {
        this.turnRuntime = turnRuntime;
        this.controlPlane = controlPlane;
        this.identityVerifier = identityVerifier;
        this.clientHandshakeService = clientHandshakeService;
        this.objectMapper = objectMapper == null
                ? new ObjectMapper().findAndRegisterModules()
                : objectMapper.findAndRegisterModules();
    }

    @PostMapping(path = "/v1/clients:hello", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ClientHelloResponse> clientHello(
            @RequestBody ClientHello hello,
            org.springframework.web.server.ServerWebExchange exchange) {
        CoreIdentityVerifier.Identity identity = identityVerifier.required()
                ? identityVerifier.verifyScope(
                        exchange, null, hello.client().clientId(), hello.sessionId())
                : new CoreIdentityVerifier.Identity(
                        hello.meguriUserId(), hello.client().clientId(), hello.sessionId());
        if (identity.userId() == null || identity.userId().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "meguri_user_id is required for local Client Hello");
        }
        try {
            ClientHandshakeService.ServerPermissionEnvelope permissions =
                    new ClientHandshakeService.ServerPermissionEnvelope(
                            identityVerifier.formalMemoryAllowed(
                                    identity.userId(), hello.client().clientId(),
                                    hello.permissions().formalMemoryAllowed()),
                            identityVerifier.screenContextAllowed(
                                    identity.userId(), hello.client().clientId(),
                                    hello.permissions().screenContextAllowed()),
                            identityVerifier.localResourceMetadataAllowed(
                                    identity.userId(), hello.client().clientId(),
                                    hello.permissions().localResourceMetadataAllowed()));
            return ResponseEntity.ok(clientHandshakeService.negotiate(
                    identityVerifier.tenantId(), identity.userId(), hello, permissions));
        } catch (UnsupportedProtocolVersionException error) {
            throw new ResponseStatusException(
                    HttpStatus.UPGRADE_REQUIRED, "UNSUPPORTED_PROTOCOL_VERSION", error);
        } catch (IllegalArgumentException | IllegalStateException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage(), error);
        }
    }

    @PostMapping(path = "/v1/hello", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AdapterProtocolHelloResponse> adapterHello(
            @RequestBody AdapterProtocolHello hello,
            org.springframework.web.server.ServerWebExchange exchange) {
        String bodyUserId = hello.identity().meguriUser().id();
        String bodyClientId = hello.identity().clientInstance().profile();
        String bodySessionId = hello.identity().session().id();
        CoreIdentityVerifier.Identity identity = identityVerifier.verifyScope(
                exchange, bodyUserId, bodyClientId, bodySessionId);
        String userId = identity.userId() == null ? bodyUserId : identity.userId();
        try {
            ClientHandshakeService.ServerPermissionEnvelope permissions =
                    new ClientHandshakeService.ServerPermissionEnvelope(
                            identityVerifier.formalMemoryAllowed(
                                    userId, bodyClientId,
                                    hello.permissions().formalMemoryWrite()),
                            identityVerifier.screenContextAllowed(
                                    userId, bodyClientId,
                                    hello.permissions().screenRead()),
                            false);
            return ResponseEntity.ok(clientHandshakeService.negotiate(
                    identityVerifier.tenantId(), userId, hello, permissions));
        } catch (UnsupportedProtocolVersionException error) {
            throw protocolError(
                    HttpStatus.UPGRADE_REQUIRED,
                    "UNSUPPORTED_PROTOCOL_MAJOR",
                    error.getMessage(),
                    false,
                    Map.of("server_protocol_version",
                            clientHandshakeService.describeCanonical().protocolVersion()),
                    error);
        } catch (UnsupportedRequiredExtensionException error) {
            throw protocolError(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "UNSUPPORTED_REQUIRED_EXTENSION",
                    error.getMessage(),
                    false,
                    Map.of(),
                    error);
        } catch (IllegalArgumentException | IllegalStateException error) {
            throw protocolError(
                    HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                    error.getMessage(), false, Map.of(), error);
        }
    }

    @GetMapping(path = "/v1/clients/capabilities", produces = MediaType.APPLICATION_JSON_VALUE)
    public ClientHandshakeService.ServerDescription clientCapabilities() {
        return clientHandshakeService.describe();
    }

    @GetMapping(path = "/v1/capabilities", produces = MediaType.APPLICATION_JSON_VALUE)
    public ClientHandshakeService.CanonicalServerDescription adapterCapabilities() {
        return clientHandshakeService.describeCanonical();
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
            @RequestBody JsonNode payload,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            org.springframework.web.server.ServerWebExchange exchange) {
        TurnRequest verified = parseTurnRequest(payload, exchange);
        return turnRuntime.submit(new TurnCommand.Start(verified, idempotencyKey))
                .map(snapshot -> ResponseEntity.status(HttpStatus.ACCEPTED).body(new TurnCreateResponse(
                        EventEnvelope.CURRENT_PROTOCOL_VERSION,
                        snapshot.turnId(), snapshot.sessionId(), controlPlane.describe().buildId(),
                        TurnStatus.fromValue(snapshot.status()),
                        snapshot.lastSequence(),
                        "/v1/sessions/" + snapshot.sessionId() + "/events")))
                .onErrorMap(IdempotencyConflictException.class,
                        error -> protocolError(
                                HttpStatus.CONFLICT,
                                "CONFLICT",
                                error.getMessage(),
                                false,
                                Map.of(),
                                error));
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
                .switchIfEmpty(Mono.error(protocolError(
                        HttpStatus.NOT_FOUND, "NOT_FOUND",
                        "turn not found", false,
                        Map.of("turn_id", turnId), null)));
    }

    @PostMapping(path = {"/v1/turns/{turnId}/cancel", "/v1/turns/{turnId}:cancel"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, String>>> cancelTurn(
            @PathVariable String turnId,
            org.springframework.web.server.ServerWebExchange exchange) {
        return turnRuntime.snapshot(turnId)
                .switchIfEmpty(Mono.error(protocolError(
                        HttpStatus.NOT_FOUND, "NOT_FOUND",
                        "turn not found", false,
                        Map.of("turn_id", turnId), null)))
                .doOnNext(snapshot -> identityVerifier.verifyScope(
                        exchange, snapshot.userId(), snapshot.clientId(), snapshot.sessionId()))
                .then(turnRuntime.submit(new TurnCommand.Cancel(turnId, "client_request")))
                .map(snapshot -> ResponseEntity.ok(Map.of(
                        "turn_id", turnId,
                        "status", snapshot.terminal() ? snapshot.status() : "cancel_requested")))
                .switchIfEmpty(Mono.error(protocolError(
                        HttpStatus.NOT_FOUND, "NOT_FOUND",
                        "turn not found", false,
                        Map.of("turn_id", turnId), null)));
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
        httpResponse.getHeaders().set(
                "X-Meguri-Snapshot-URL",
                "/v1/sessions/" + sessionId + "/snapshot");
        long resolvedCursor = cursor;
        return turnRuntime.replayWindow(sessionId, identity.userId(), identity.clientId())
                .flatMapMany(window -> {
                    if (window.cursorExpired(resolvedCursor)) {
                        return Flux.error(protocolError(
                                HttpStatus.GONE,
                                "CURSOR_EXPIRED",
                                "event cursor is outside retention",
                                true,
                                Map.of(
                                        "oldest_available_sequence", window.oldestAvailableSequence(),
                                        "latest_sequence", window.latestSequence(),
                                        "snapshot_url", "/v1/sessions/" + sessionId + "/snapshot"),
                                null));
                    }
                    return eventStream(
                            sessionId,
                            resolvedCursor,
                            identity.userId(),
                            identity.clientId());
                });
    }

    @GetMapping(path = "/v1/sessions/{sessionId}/snapshot",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<AdapterSessionSnapshotResponse>> sessionSnapshot(
            @PathVariable String sessionId,
            org.springframework.web.server.ServerWebExchange exchange) {
        CoreIdentityVerifier.Identity identity =
                identityVerifier.verifyScope(exchange, null, null, sessionId);
        return turnRuntime.sessionSnapshot(sessionId, identity.userId(), identity.clientId())
                .map(AdapterSessionSnapshotResponse::from)
                .map(ResponseEntity::ok)
                .switchIfEmpty(Mono.error(protocolError(
                        HttpStatus.NOT_FOUND, "NOT_FOUND",
                        "session snapshot not found", false,
                        Map.of("session_id", sessionId), null)));
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
            String sessionId,
            long initialCursor,
            String userId,
            String clientId) {
        Flux<ServerSentEvent<String>> events = turnRuntime.events(new EventCursor(
                        sessionId, Math.max(0L, initialCursor), null, userId, clientId))
                .map(this::toSse);
        Flux<ServerSentEvent<String>> heartbeats = Flux.interval(Duration.ofSeconds(15))
                .map(ignored -> heartbeat());
        return events.publish(shared -> Flux.merge(
                shared,
                heartbeats.takeUntilOther(shared.ignoreElements())));
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

    private ServerSentEvent<String> heartbeat() {
        try {
            return ServerSentEvent.<String>builder()
                    .event("heartbeat")
                    .data(objectMapper.writeValueAsString(Map.of(
                            "protocol_version", EventEnvelope.CURRENT_PROTOCOL_VERSION,
                            "type", "heartbeat",
                            "created_at", Instant.now())))
                    .build();
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("failed to encode heartbeat", error);
        }
    }

    private TurnRequest parseTurnRequest(
            JsonNode payload,
            org.springframework.web.server.ServerWebExchange exchange) {
        try {
            if (payload != null && payload.has("identity")) {
                AdapterTurnCreateRequest adapterRequest =
                        objectMapper.treeToValue(payload, AdapterTurnCreateRequest.class);
                String userId = adapterRequest.identity().meguriUser().id();
                String clientId = adapterRequest.identity().clientInstance().profile();
                String sessionId = adapterRequest.identity().session().id();
                identityVerifier.verifyScope(exchange, userId, clientId, sessionId);
                ClientBinding binding = clientHandshakeService.binding(
                                adapterRequest.identity().clientInstance().id())
                        .orElseThrow(() -> protocolError(
                                HttpStatus.CONFLICT,
                                "CAPABILITY_UNAVAILABLE",
                                "Client Hello must complete before creating a Turn",
                                true,
                                Map.of("client_instance_id",
                                        adapterRequest.identity().clientInstance().id()),
                                null));
                return adapterRequest.toCoreRequest(binding);
            }
            TurnRequest legacy = objectMapper.treeToValue(payload, TurnRequest.class);
            return identityVerifier.verifyBody(exchange, legacy);
        } catch (AdapterProtocolException error) {
            throw error;
        } catch (UnsupportedProtocolVersionException error) {
            throw protocolError(
                    HttpStatus.UPGRADE_REQUIRED,
                    "UNSUPPORTED_PROTOCOL_MAJOR",
                    error.getMessage(),
                    false,
                    Map.of(),
                    error);
        } catch (UnsupportedRequiredExtensionException error) {
            throw protocolError(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "UNSUPPORTED_REQUIRED_EXTENSION",
                    error.getMessage(),
                    false,
                    Map.of(),
                    error);
        } catch (JsonProcessingException | IllegalArgumentException error) {
            throw protocolError(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_REQUEST",
                    error.getMessage() == null ? "invalid Turn request" : error.getMessage(),
                    false,
                    Map.of(),
                    error);
        }
    }

    private static AdapterProtocolException protocolError(
            HttpStatus status,
            String code,
            String message,
            boolean retryable,
            Map<String, Object> details,
            Throwable cause) {
        String safeMessage = message == null || message.isBlank() ? code : message;
        return new AdapterProtocolException(
                status, code, safeMessage, retryable, details, cause);
    }

    private Map<String, Object> asMap(Object value) {
        try {
            return objectMapper.convertValue(value, Map.class);
        } catch (IllegalArgumentException ignored) {
            return new LinkedHashMap<>();
        }
    }
}

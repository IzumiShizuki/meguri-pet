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
import com.meguri.core.runtime.TurnOrchestrator;
import com.meguri.core.runtime.TurnRecord;
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
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

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
        allowedHeaders = {"Content-Type", "Idempotency-Key", "Last-Event-ID", "X-Request-ID"})
public final class RuntimeWebController {
    private final TurnOrchestrator orchestrator;
    private final ObjectMapper objectMapper;

    public RuntimeWebController(TurnOrchestrator orchestrator, ObjectMapper objectMapper) {
        this.orchestrator = orchestrator;
        this.objectMapper = objectMapper == null
                ? new ObjectMapper().findAndRegisterModules()
                : objectMapper.findAndRegisterModules();
    }

    @GetMapping(path = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> health() {
        return Map.of(
                "status", "ok",
                "service", "meguri-core",
                "build_id", orchestrator.buildId(),
                "mode", "mock".equalsIgnoreCase(orchestrator.llmProviderName()) ? "local-mock" : "configured-provider",
                "llm_provider", orchestrator.llmProviderName(),
                "memory_provider", "unavailable",
                "web_search_provider", orchestrator.webSearchProviderName(),
                "rag_chunks", orchestrator.ragSize());
    }

    @GetMapping(path = "/health/live", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> live() {
        return Map.of("status", "alive", "service", "meguri-core", "build_id", orchestrator.buildId());
    }

    @GetMapping(path = "/health/ready", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> ready() {
        return ResponseEntity.ok(Map.of(
                "status", "ready",
                "build_id", orchestrator.buildId(),
                "checks", Map.of("local_unmanaged", "passed")));
    }

    @PostMapping(path = "/v1/chat/respond", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<com.meguri.core.dto.ChatResponse>> chatRespond(@RequestBody TurnRequest request) {
        return orchestrator.runInline(request).map(ResponseEntity::ok);
    }

    @PostMapping(path = "/v1/turns", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<TurnCreateResponse>> createTurn(
            @RequestBody TurnRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        TurnRecord record = orchestrator.start(request, idempotencyKey);
        TurnCreateResponse body = new TurnCreateResponse(
                record.getTurnId(), request.getSessionId(), orchestrator.buildId(), toDtoStatus(record.getStatus()));
        return Mono.just(ResponseEntity.status(HttpStatus.ACCEPTED).body(body));
    }

    @GetMapping(path = "/v1/turns/{turnId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<TurnStatusResponse>> getTurn(@PathVariable String turnId) {
        TurnRecord record = orchestrator.turn(turnId);
        if (record == null) return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "turn not found"));
        TurnStatusResponse body = new TurnStatusResponse(
                record.getTurnId(), record.getRequest().getSessionId(), toDtoStatus(record.getStatus()),
                orchestrator.buildId(), record.getError());
        return Mono.just(ResponseEntity.ok(body));
    }

    @PostMapping(path = "/v1/turns/{turnId}/cancel", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, String>>> cancelTurn(@PathVariable String turnId) {
        TurnRecord record = orchestrator.cancel(turnId);
        if (record == null) return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "turn not found"));
        String status = record.isTerminal() ? record.statusValue() : "cancel_requested";
        return Mono.just(ResponseEntity.ok(Map.of("turn_id", turnId, "status", status)));
    }

    @GetMapping(path = "/v1/sessions/{sessionId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> sessionEvents(
            @PathVariable String sessionId,
            @RequestParam(name = "after_sequence", defaultValue = "0") long afterSequence,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId,
            ServerHttpResponse httpResponse) {
        httpResponse.getHeaders().setCacheControl("no-cache");
        httpResponse.getHeaders().set("X-Accel-Buffering", "no");
        httpResponse.getHeaders().set("X-Meguri-Build", orchestrator.buildId());
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
        return eventStream(sessionId, cursor);
    }

    @GetMapping(path = "/v1/runtime/state", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> runtimeState(
            @RequestParam(name = "user_id") String userId,
            @RequestParam(name = "client_id", defaultValue = "website") String clientId,
            @RequestParam(name = "session_id", defaultValue = "state") String sessionId) {
        TurnRequest request = new TurnRequest(userId, clientId, sessionId, "state");
        RuntimeState state = orchestrator.stateMachine().stateFor(request);
        return asMap(state);
    }

    @PostMapping(path = "/v1/runtime/override", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> setRuntimeOverride(
            @RequestParam(name = "user_id") String userId,
            @RequestBody RuntimeOverride override) {
        orchestrator.stateMachine().setOverride(userId, override);
        return Map.of("user_id", userId, "override", override);
    }

    @DeleteMapping(path = "/v1/runtime/override/{scope}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> clearRuntimeOverride(@PathVariable String scope) {
        orchestrator.stateMachine().clearOverride(scope);
        return Map.of("scope", scope, "status", "cleared");
    }

    private Flux<ServerSentEvent<String>> eventStream(String sessionId, long initialCursor) {
        return Flux.defer(() -> Flux.create(sink -> {
            AtomicLong cursor = new AtomicLong(Math.max(0L, initialCursor));
            AtomicLong lastEmission = new AtomicLong(System.nanoTime());
            Disposable ticker = Flux.interval(Duration.ofMillis(20))
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe(ignored -> {
                        if (sink.isCancelled()) return;
                        List<EventEnvelope> available = orchestrator.eventsFor(sessionId);
                        boolean emitted = false;
                        for (EventEnvelope event : available) {
                            if (event.getSequence() <= cursor.get()) continue;
                            cursor.set(event.getSequence());
                            sink.next(toSse(event));
                            emitted = true;
                            lastEmission.set(System.nanoTime());
                        }
                        if (!orchestrator.sessionIsActive(sessionId)
                                && available.stream().noneMatch(event -> event.getSequence() > cursor.get())) {
                            sink.complete();
                            return;
                        }
                        if (!emitted && System.nanoTime() - lastEmission.get() >= Duration.ofSeconds(1).toNanos()) {
                            sink.next(ServerSentEvent.<String>builder().comment("heartbeat").build());
                            lastEmission.set(System.nanoTime());
                        }
                    }, sink::error);
            sink.onDispose(ticker);
        }));
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

    private static TurnStatus toDtoStatus(com.meguri.core.runtime.TurnStatus status) {
        return TurnStatus.fromValue(status.wireValue());
    }

    private Map<String, Object> asMap(Object value) {
        try {
            return objectMapper.convertValue(value, Map.class);
        } catch (IllegalArgumentException ignored) {
            return new LinkedHashMap<>();
        }
    }
}

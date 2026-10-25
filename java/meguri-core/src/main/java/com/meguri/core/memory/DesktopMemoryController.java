package com.meguri.core.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.meguri.core.adapter.application.ClientHandshakeService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/v1/memory/desktop")
public final class DesktopMemoryController {
    private final DesktopMemoryIdentity identity;
    private final DesktopMemoryService service;
    private final ClientHandshakeService handshake;

    public DesktopMemoryController(DesktopMemoryIdentity identity, DesktopMemoryService service, ClientHandshakeService handshake) {
        this.identity = identity;
        this.service = service;
        this.handshake = handshake;
    }

    @GetMapping("/status")
    public Mono<Map<String, Object>> status(ServerWebExchange exchange) {
        return service.status(identity.verify(exchange));
    }

    @GetMapping("/records")
    public Mono<JsonNode> records(ServerWebExchange exchange) {
        return service.call(identity.verify(exchange), "records", Map.of());
    }

    @GetMapping("/candidates")
    public Mono<JsonNode> candidates(ServerWebExchange exchange) {
        return service.call(identity.verify(exchange), "candidates", Map.of());
    }

    @GetMapping("/memories/{memoryId}")
    public Mono<JsonNode> detail(@PathVariable UUID memoryId, ServerWebExchange exchange) {
        return service.call(identity.verify(exchange), "detail", Map.of("memory_id", memoryId.toString()));
    }

    @PostMapping("/memories/{memoryId}/corrections")
    public Mono<JsonNode> correct(@PathVariable UUID memoryId, @RequestBody JsonNode body, ServerWebExchange exchange) {
        strictBody(body, Set.of("base_version_id", "content_text", "change_reason"));
        String version = boundedText(body, "base_version_id", 36);
        try {
            if (!UUID.fromString(version).toString().equalsIgnoreCase(version)) throw invalid();
        } catch (IllegalArgumentException error) {
            throw invalid();
        }
        String text = boundedText(body, "content_text", 4000);
        String reason = boundedText(body, "change_reason", 1000);
        return service.call(identity.verify(exchange), "correct", Map.of(
                "memory_id", memoryId.toString(), "base_version_id", version,
                "content_text", text, "change_reason", reason, "request_id", requestId(exchange)));
    }

    @PostMapping("/candidates/{candidateId}/review")
    public Mono<JsonNode> review(@PathVariable UUID candidateId, @RequestBody JsonNode body, ServerWebExchange exchange) {
        strictBody(body, Set.of("decision"));
        String decision = body.path("decision").asText("");
        if (!Set.of("approve", "reject").contains(decision)) throw invalid();
        return service.call(identity.verify(exchange), "review", Map.of("candidate_id", candidateId.toString(),
                "decision", decision, "request_id", requestId(exchange)));
    }

    @PostMapping("/memories/{memoryId}/delete")
    public Mono<JsonNode> delete(@PathVariable UUID memoryId, @RequestBody JsonNode body, ServerWebExchange exchange) {
        strictBody(body, Set.of());
        return service.call(identity.verify(exchange), "delete", Map.of("memory_id", memoryId.toString(), "request_id", requestId(exchange)));
    }

    @PostMapping("/memories/{memoryId}/restore")
    public Mono<JsonNode> restore(@PathVariable UUID memoryId, @RequestBody JsonNode body, ServerWebExchange exchange) {
        strictBody(body, Set.of());
        return service.call(identity.verify(exchange), "restore", Map.of("memory_id", memoryId.toString(), "request_id", requestId(exchange)));
    }

    @PostMapping("/export")
    public Mono<ResponseEntity<String>> export(@RequestBody JsonNode body, ServerWebExchange exchange) {
        strictBody(body, Set.of());
        return service.export(identity.verify(exchange), requestId(exchange)).map(value -> ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/x-ndjson"))
                .header("Content-Disposition", "attachment; filename=meguri-memory-export.jsonl").body(value));
    }

    @PostMapping("/summary")
    public Mono<JsonNode> summary(@RequestBody JsonNode body, ServerWebExchange exchange) {
        strictBody(body, Set.of("checkpoint", "client_instance_id"));
        if (!body.path("checkpoint").isIntegralNumber() || body.path("checkpoint").asLong() < 1) throw invalid();
        DesktopMemoryIdentity.Scope scope = identity.verify(exchange);
        String installId = body.path("client_instance_id").asText("");
        if (!installId.matches("[A-Za-z0-9._:-]{1,200}")) throw invalid();
        var binding = handshake.binding(installId).orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "memory_consent_required"));
        if (!binding.tenantId().equals(scope.tenantId()) || !binding.meguriUserId().equals(scope.userId())
                || !binding.clientId().equals(scope.clientId()) || !binding.permissions().formalMemoryAllowed())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "memory_consent_required");
        return service.summarize(scope, body.path("checkpoint").asLong(), requestId(exchange));
    }

    private static String requestId(ServerWebExchange exchange) {
        String value = exchange.getRequest().getHeaders().getFirst("X-Request-ID");
        if (value == null || value.isBlank() || value.length() > 200) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request_id_required");
        return value.trim();
    }

    private static void strictBody(JsonNode body, Set<String> allowed) {
        if (!body.isObject()) throw invalid();
        body.fieldNames().forEachRemaining(name -> { if (!allowed.contains(name)) throw invalid(); });
    }

    private static String boundedText(JsonNode body, String field, int limit) {
        JsonNode value = body.path(field);
        if (!value.isTextual()) throw invalid();
        String text = value.asText().strip();
        if (text.isBlank() || text.codePointCount(0, text.length()) > limit) throw invalid();
        return text;
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_memory_request");
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> memoryError(ResponseStatusException error) {
        String code = error.getReason();
        if (code == null || !code.matches("[a-z_]{1,80}")) code = "memory_operation_rejected";
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("detail", Map.of("code", code)));
    }
}

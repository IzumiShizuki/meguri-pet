package com.meguri.core.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.dto.LlmResponse;
import com.meguri.core.dto.MemoryCandidate;
import com.meguri.core.dto.TurnRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** HTTP adapter for the loopback-only Python memory bridge. */
@Component
public final class PythonMemoryGateway implements MemoryGateway {
    private final WebClient client;
    private final ObjectMapper mapper;
    private final String token;
    private final Duration timeout;

    public PythonMemoryGateway(
            WebClient.Builder builder,
            ObjectMapper mapper,
            @Value("${meguri.memory.bridge-url:http://127.0.0.1:8000}") String baseUrl,
            @Value("${meguri.memory.bridge-token:}") String token,
            @Value("${meguri.memory.timeout-ms:1500}") long timeoutMs) {
        this.client = builder.baseUrl(baseUrl).build();
        this.mapper = mapper;
        this.token = token == null ? "" : token.trim();
        this.timeout = Duration.ofMillis(Math.max(timeoutMs, 50));
    }

    @Override
    public Mono<MemoryRecall> recall(TurnRequest request) {
        if (!enabled() || !request.isFormalMemoryAllowed()) {
            return Mono.just(MemoryRecall.unavailable());
        }
        Map<String, Object> payload = Map.of(
                "user_id", request.getUserId(),
                "query", request.getMessage(),
                "limit", 5);
        return post("/internal/memory/search", payload)
                .map(node -> {
                    List<String> memories = new ArrayList<>();
                    node.path("items").forEach(item -> {
                        JsonNode record = item.path("record");
                        String text = record.path("canonical_text").asText("");
                        if (!text.isBlank()) memories.add(text);
                    });
                    return MemoryRecall.available(memories);
                })
                .onErrorReturn(MemoryRecall.unavailable());
    }

    @Override
    public Mono<List<MemoryCandidate>> extract(TurnRequest request) {
        if (!enabled() || !request.isFormalMemoryAllowed()) {
            return Mono.just(List.of());
        }
        Map<String, Object> payload = Map.of(
                "user_id", request.getUserId(),
                "content", request.getMessage(),
                "source_client", request.getClientId(),
                "source_session", request.getSessionId());
        return post("/internal/memory/extract", payload)
                .map(node -> {
                    List<MemoryCandidate> values = new ArrayList<>();
                    node.path("items").forEach(item -> values.add(mapper.convertValue(item, MemoryCandidate.class)));
                    return List.copyOf(values);
                })
                .onErrorReturn(List.of());
    }

    @Override
    public Mono<MemoryWriteResult> write(TurnRequest request, LlmResponse response, String turnId, String traceId) {
        if (!enabled() || !request.isFormalMemoryAllowed()) {
            return Mono.just(MemoryWriteResult.unavailable());
        }
        Mono<List<MemoryCandidate>> candidates = response.getMemoryCandidates().isEmpty()
                ? extract(request)
                : Mono.just(response.getMemoryCandidates());
        return candidates.flatMap(values -> {
                    Map<String, Object> payload = Map.of(
                            "user_id", request.getUserId(),
                            "source_client", request.getClientId(),
                            "source_session", request.getSessionId(),
                            "source_turn_id", turnId,
                            "trace_id", traceId,
                            "candidates", values);
                    return post("/internal/memory/write", payload);
                })
                .map(node -> new MemoryWriteResult(
                        node.path("status").asText("unavailable"),
                        strings(node.path("written_ids")),
                        strings(node.path("candidate_ids")),
                        strings(node.path("decisions")),
                        mapper.convertValue(node.path("events"), List.class)))
                .onErrorReturn(MemoryWriteResult.unavailable());
    }

    private Mono<JsonNode> post(String path, Map<String, Object> payload) {
        return client.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Meguri-Internal-Token", token)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(timeout);
    }

    private boolean enabled() {
        return !token.isBlank();
    }

    private static List<String> strings(JsonNode node) {
        List<String> values = new ArrayList<>();
        node.forEach(item -> values.add(item.asText()));
        return List.copyOf(values);
    }
}

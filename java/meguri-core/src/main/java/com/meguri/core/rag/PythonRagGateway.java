package com.meguri.core.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.dto.RuntimeState;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Uses the remote Python DashScope retriever and degrades to canonical lexical search. */
public final class PythonRagGateway implements RagProvider {
    private final WebClient client;
    private final String token;
    private final Duration timeout;
    private final RagProvider fallback;

    public PythonRagGateway(WebClient.Builder builder, String baseUrl,
                            String token, String tokenFile, long timeoutMs,
                            RagProvider fallback) {
        if (builder == null) throw new IllegalArgumentException("WebClient.Builder is required");
        this.client = builder.baseUrl(baseUrl).build();
        this.token = readToken(tokenFile, token);
        this.timeout = Duration.ofMillis(Math.max(100, timeoutMs));
        this.fallback = fallback;
    }

    @Override
    public List<String> search(String query, RuntimeState state, int limit) {
        if (query == null || query.isBlank() || state == null || limit <= 0) return List.of();
        if (token.isBlank()) return fallback.search(query, state, limit);
        try {
            JsonNode result = client.post()
                    .uri("/internal/rag/search")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Meguri-Internal-Token", token)
                    .bodyValue(Map.of(
                            "query", query,
                            "runtime_state", state,
                            "limit", Math.min(limit, 10)))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(timeout)
                    .block();
            if (result == null) return fallback.search(query, state, limit);
            List<String> values = new ArrayList<>();
            result.path("items").forEach(item -> {
                String value = item.asText("").trim();
                if (!value.isBlank()) values.add(value);
            });
            return List.copyOf(values);
        } catch (RuntimeException error) {
            return fallback.search(query, state, limit);
        }
    }

    private static String readToken(String tokenFile, String inlineToken) {
        String configured = tokenFile == null ? "" : tokenFile.trim();
        if (!configured.isBlank()) {
            try {
                Path path = Path.of(configured);
                if (path.isAbsolute() && Files.isRegularFile(path)) {
                    String value = Files.readString(path).trim();
                    if (!value.isBlank()) return value;
                }
            } catch (Exception ignored) {
                // Retrieval remains available through the deterministic fallback.
            }
        }
        return inlineToken == null ? "" : inlineToken.trim();
    }
}

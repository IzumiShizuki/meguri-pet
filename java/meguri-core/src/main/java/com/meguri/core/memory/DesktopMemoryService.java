package com.meguri.core.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.meguri.core.dto.MemoryCandidate;
import com.meguri.core.llm.LlmProvider;
import com.meguri.core.runtime.SessionContextStore;
import com.meguri.core.runtime.TurnOrchestrator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Current-session summaries use server history and durable Python idempotency. */
@Service
public final class DesktopMemoryService {
    private static final Pattern CREDENTIAL = Pattern.compile("(?i)(password|passphrase|api[_ -]?key|access[_ -]?token|refresh[_ -]?token|cookie|private[_ -]?key|银行卡|身份证|密码|令牌|私钥)");
    private final Supplier<List<SessionContextStore.Snapshot>> snapshots;
    private final PythonMemoryGateway memory;
    private final LlmProvider llm;
    private final Map<String, Mono<JsonNode>> summaries = new LinkedHashMap<>();

    @Autowired
    public DesktopMemoryService(TurnOrchestrator orchestrator, PythonMemoryGateway memory, LlmProvider llm) {
        this(orchestrator::sessionSnapshots, memory, llm);
    }

    DesktopMemoryService(Supplier<List<SessionContextStore.Snapshot>> snapshots, PythonMemoryGateway memory, LlmProvider llm) {
        this.snapshots = snapshots;
        this.memory = memory;
        this.llm = llm;
    }

    public Mono<Map<String, Object>> status(DesktopMemoryIdentity.Scope scope) {
        Map<String, Object> base = new LinkedHashMap<>(Map.of("authorized", scope.authorized(), "userId", scope.userId(),
                "clientId", scope.clientId(), "sessionId", scope.sessionId()));
        if (!scope.authorized()) {
            base.putAll(Map.of("available", false, "provider", "unavailable", "reason", "formal_memory_not_authorized"));
            return Mono.just(base);
        }
        return memory.desktopCall("status", scope.payload()).map(result -> {
            base.putAll(Map.of("available", result.path("available").asBoolean(false), "provider", result.path("provider").asText("unavailable"),
                    "reason", result.path("reason").asText("memory_bridge_unavailable")));
            List<String> capabilities = new java.util.ArrayList<>();
            if (result.path("capabilities").isArray()) result.path("capabilities").forEach(value -> {
                if (value.isTextual() && List.of("memory_detail", "memory_corrections").contains(value.asText()))
                    capabilities.add(value.asText());
            });
            base.put("capabilities", List.copyOf(capabilities));
            return base;
        }).onErrorResume(error -> {
            String reason = error instanceof ResponseStatusException status ? status.getReason() : "memory_bridge_unavailable";
            base.putAll(Map.of("available", false, "provider", "unavailable", "reason", reason == null ? "memory_bridge_unavailable" : reason));
            return Mono.just(base);
        });
    }

    public Mono<JsonNode> call(DesktopMemoryIdentity.Scope scope, String operation, Map<String, Object> fields) {
        requireAuthorized(scope);
        Map<String, Object> payload = scope.payload();
        payload.putAll(fields);
        return memory.desktopCall(operation, payload);
    }

    public Mono<String> export(DesktopMemoryIdentity.Scope scope, String requestId) {
        requireAuthorized(scope);
        Map<String, Object> payload = scope.payload();
        payload.put("request_id", requestId);
        return memory.desktopExport(payload);
    }

    public Mono<JsonNode> summarize(DesktopMemoryIdentity.Scope scope, long checkpoint, String requestId) {
        requireAuthorized(scope);
        String key = String.join("\0", scope.tenantId(), scope.userId(), scope.clientId(), scope.sessionId(), Long.toString(checkpoint));
        synchronized (summaries) {
            Mono<JsonNode> existing = summaries.get(key);
            if (existing != null) return existing;
            Mono<JsonNode> result = Mono.defer(() -> {
                SessionContextStore.Snapshot snapshot = snapshots.get().stream().filter(value -> value.userId().equals(scope.userId())
                        && value.clientId().equals(scope.clientId()) && value.sessionId().equals(scope.sessionId())).findFirst()
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "session_history_unavailable"));
                List<SessionContextStore.Message> valid = snapshot.messages().stream().filter(value -> List.of("user", "assistant").contains(value.role())).toList();
                List<SessionContextStore.Message> messages = valid.subList(Math.max(0, valid.size() - 20), valid.size()).stream()
                        .map(value -> new SessionContextStore.Message(value.role(), CREDENTIAL.matcher(value.content()).find()
                                ? "[已省略可能包含敏感凭据的消息]" : value.content().substring(0, Math.min(2000, value.content().length())))).toList();
                if (messages.size() < 2) return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, "session_history_unavailable"));
                return memory.desktopCall("status", scope.payload()).flatMap(ignored -> llm.extractMemoryCandidates(
                        messages.stream().map(value -> value.role() + ": " + value.content()).toList())
                        .timeout(Duration.ofSeconds(15)).onErrorReturn(List.of()).defaultIfEmpty(List.of()))
                        .flatMap(candidates -> {
                            Map<String, Object> payload = scope.payload();
                            payload.put("messages", messages);
                            payload.put("structured_candidates", candidates == null ? List.<MemoryCandidate>of() : candidates.stream().limit(3).toList());
                            payload.put("checkpoint", checkpoint);
                            payload.put("request_id", requestId);
                            return memory.desktopCall("summary", payload);
                        });
            }).doOnError(ignored -> { synchronized (summaries) { summaries.remove(key); } }).cache();
            summaries.put(key, result);
            while (summaries.size() > 128) summaries.remove(summaries.keySet().iterator().next());
            return result;
        }
    }

    private static void requireAuthorized(DesktopMemoryIdentity.Scope scope) {
        if (!scope.authorized()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "formal_memory_not_authorized");
    }
}

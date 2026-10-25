package com.meguri.core.companion;

import com.meguri.core.llm.LlmProvider;
import com.meguri.core.runtime.SessionContextStore;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Independent draft/ack flow: no turns, tools, or automatic memory extraction. */
@Service
public final class CompanionReachOutService {
    public record Scope(String userId, String clientId, String sessionId) { }
    public record Candidate(String id, String text, long createdAt, long expiresAt) { }
    public record ScreenContext(String text, String app, long observedAt) { }
    private record Attempt(String leaf, Instant expiresAt, Mono<Candidate> result) { }
    private final LlmProvider provider;
    private final SessionContextStore contexts;
    private final Clock clock;
    private final Map<Scope, Attempt> attempts = new LinkedHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public CompanionReachOutService(LlmProvider provider, SessionContextStore contexts) {
        this(provider, contexts, Clock.systemUTC());
    }

    CompanionReachOutService(LlmProvider provider, SessionContextStore contexts, Clock clock) {
        this.provider = provider;
        this.contexts = contexts;
        this.clock = clock;
    }

    public Mono<Candidate> draft(Scope scope) {
        return draft(scope, null);
    }

    public Mono<Candidate> draft(Scope scope, ScreenContext screen) {
        return draft(scope, screen, Long.MAX_VALUE);
    }

    public Mono<Candidate> draft(Scope scope, ScreenContext screen, long activeUntil) {
        return Mono.defer(() -> draftCurrent(scope, screen, activeUntil)).subscribeOn(Schedulers.boundedElastic());
    }

    private synchronized Mono<Candidate> draftCurrent(Scope scope, ScreenContext screen, long activeUntil) {
        if (activeUntil != Long.MAX_VALUE && (activeUntil <= clock.millis() || activeUntil > clock.millis() + 605_000)) return Mono.empty();
        if (screen != null && (screen.text() == null || screen.text().isBlank() || screen.text().length() > 1000
                || screen.app() == null || screen.app().length() > 80
                || screen.observedAt() < clock.millis() - 60_000 || screen.observedAt() > clock.millis() + 5000)) return Mono.empty();
        var graph = contexts.graph(scope.userId(), scope.clientId(), scope.sessionId());
        var path = graph.activePath();
        if (path.size() < 2) return Mono.empty();
        var last = path.getLast();
        if (!"assistant".equals(last.role()) || last.messageId().startsWith("reachout:")
                || path.stream().noneMatch(node -> "user".equals(node.role()))) return Mono.empty();
        Instant now = clock.instant();
        attempts.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
        Attempt previous = attempts.get(scope);
        // Cache failures/declines too. Polling and reconnects must not burn model calls.
        if (previous != null) return Objects.equals(previous.leaf(), last.messageId())
                ? previous.result() : Mono.empty();
        if (attempts.size() >= 256) return Mono.empty();
        String id = "reachout:" + UUID.nameUUIDFromBytes(
                (scope.toString() + "|" + last.messageId()).getBytes(StandardCharsets.UTF_8));
        long createdAt = now.toEpochMilli();
        long expiresAt = now.plus(Duration.ofMinutes(10)).toEpochMilli();
        var recent = path.stream().skip(Math.max(0, path.size() - 8))
                .map(node -> node.role() + ": " + node.content().substring(0, Math.min(600, node.content().length())))
                .toList();
        Mono<Candidate> result = Mono.defer(() -> clock.millis() >= activeUntil ? Mono.empty()
                        : screen == null ? provider.generateReachOut(recent)
                        : provider.generateReachOut(recent, Map.of("text", screen.text(), "app", screen.app(), "observed_at", screen.observedAt())))
                .timeout(Duration.ofSeconds(20))
                .map(String::trim)
                .filter(text -> !text.isBlank() && !"[SKIP]".equals(text) && text.length() <= 240)
                .filter(text -> !text.startsWith("{") && !text.startsWith("```"))
                .filter(text -> Objects.equals(last.messageId(), contexts.graph(
                        scope.userId(), scope.clientId(), scope.sessionId()).activeLeafMessageId()))
                .map(text -> new Candidate(id, text, createdAt, expiresAt))
                .onErrorResume(error -> Mono.empty()).cache();
        attempts.put(scope, new Attempt(last.messageId(), now.plus(Duration.ofMinutes(15)), result));
        return result;
    }

    public Mono<Boolean> acknowledge(Scope scope, String id) {
        return Mono.defer(() -> {
            Attempt attempt;
            synchronized (this) { attempt = attempts.get(scope); }
            if (attempt == null) return Mono.just(false);
            return attempt.result().filter(candidate -> candidate.id().equals(id)
                            && candidate.expiresAt() > clock.millis())
                    .map(candidate -> contexts.appendAssistantIfCurrent(scope.userId(), scope.clientId(),
                            scope.sessionId(), attempt.leaf(), id, candidate.text()))
                    .defaultIfEmpty(false);
        }).subscribeOn(Schedulers.boundedElastic());
    }
}

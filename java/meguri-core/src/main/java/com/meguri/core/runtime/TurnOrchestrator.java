package com.meguri.core.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.dto.ChatResponse;
import com.meguri.core.dto.EventEnvelope;
import com.meguri.core.dto.EventMetadata;
import com.meguri.core.dto.ExpressionTag;
import com.meguri.core.dto.Intensity;
import com.meguri.core.dto.LlmResponse;
import com.meguri.core.dto.MemoryStatus;
import com.meguri.core.dto.ResolvedExpression;
import com.meguri.core.dto.RuntimeState;
import com.meguri.core.dto.TurnRequest;
import com.meguri.core.llm.LlmProvider;
import com.meguri.core.llm.LlmProviderFactory;
import com.meguri.core.rag.CanonicalRagRetriever;
import com.meguri.core.rag.MockRagProvider;
import com.meguri.core.rag.RagProvider;
import com.meguri.core.memory.MemoryGateway;
import com.meguri.core.memory.MemoryRecall;
import com.meguri.core.memory.MemoryWriteResult;
import com.meguri.core.websearch.NoopWebSearchGateway;
import com.meguri.core.websearch.WebSearchGateway;
import com.meguri.core.websearch.WebSearchPolicy;
import com.meguri.core.websearch.WebSearchRecall;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Coordinates one turn from acceptance through semantic/text/expression/memory
 * events. The event log is session-scoped and append-only for reconnect replay.
 */
@Service
public class TurnOrchestrator {
    private static final int TEXT_CHUNK_SIZE = 18;

    private final RuntimeStateMachine stateMachine;
    private final ExpressionResolver expressionResolver;
    private final RagProvider rag;
    private final LlmProvider llm;
    private final SessionContextStore sessions;
    private final ObjectMapper objectMapper;
    private final String buildId;
    private final Duration streamInterval;
    private final MemoryGateway memory;
    private final WebSearchGateway webSearch;

    private final Map<String, CopyOnWriteArrayList<EventEnvelope>> events = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> sequence = new ConcurrentHashMap<>();
    private final Map<String, TurnRecord> turns = new ConcurrentHashMap<>();
    private final Map<IdempotencyKey, String> idempotency = new ConcurrentHashMap<>();
    private final Map<String, Disposable> running = new ConcurrentHashMap<>();

    /** Spring's offline-first default; callers can inject real providers through the overload. */
    public TurnOrchestrator() {
        this(null, null, new RuntimeStateMachine(), new ExpressionResolver(), Duration.ofMillis(10), new ObjectMapper(),
                new com.meguri.core.memory.NoopMemoryGateway(), new NoopWebSearchGateway());
    }

    public TurnOrchestrator(LlmProvider llm, RagProvider rag) {
        this(llm, rag, new RuntimeStateMachine(), new ExpressionResolver(), Duration.ofMillis(10), new ObjectMapper(),
                new com.meguri.core.memory.NoopMemoryGateway(), new NoopWebSearchGateway());
    }

    @Autowired
    public TurnOrchestrator(LlmProvider llm, RagProvider rag, MemoryGateway memory,
                            WebSearchGateway webSearch, ObjectMapper objectMapper) {
        this(llm, rag, new RuntimeStateMachine(), new ExpressionResolver(), Duration.ofMillis(10), objectMapper, memory, webSearch);
    }

    public TurnOrchestrator(LlmProvider llm, RagProvider rag,
                            RuntimeStateMachine stateMachine,
                            ExpressionResolver expressionResolver,
                            Duration streamInterval,
                            ObjectMapper objectMapper) {
        this(llm, rag, stateMachine, expressionResolver, streamInterval, objectMapper, new com.meguri.core.memory.NoopMemoryGateway());
    }

    public TurnOrchestrator(LlmProvider llm, RagProvider rag,
                            RuntimeStateMachine stateMachine,
                            ExpressionResolver expressionResolver,
                            Duration streamInterval,
                            ObjectMapper objectMapper,
                            MemoryGateway memory) {
        this(llm, rag, stateMachine, expressionResolver, streamInterval, objectMapper, memory, new NoopWebSearchGateway());
    }

    public TurnOrchestrator(LlmProvider llm, RagProvider rag,
                            RuntimeStateMachine stateMachine,
                            ExpressionResolver expressionResolver,
                            Duration streamInterval,
                            ObjectMapper objectMapper,
                            MemoryGateway memory,
                            WebSearchGateway webSearch) {
        this.stateMachine = Objects.requireNonNull(stateMachine, "stateMachine");
        this.expressionResolver = Objects.requireNonNull(expressionResolver, "expressionResolver");
        this.buildId = expressionResolver.buildId();
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.llm = llm == null ? defaultLlm(this.objectMapper) : llm;
        this.rag = rag == null ? defaultRag(this.buildId) : rag;
        this.streamInterval = streamInterval == null ? Duration.ofMillis(10) : streamInterval;
        this.sessions = new SessionContextStore();
        this.memory = memory == null ? new com.meguri.core.memory.NoopMemoryGateway() : memory;
        this.webSearch = webSearch == null ? new NoopWebSearchGateway() : webSearch;
    }

    public RuntimeStateMachine stateMachine() {
        return stateMachine;
    }

    public RuntimeStateMachine getStateMachine() {
        return stateMachine;
    }

    public ExpressionResolver expressionResolver() {
        return expressionResolver;
    }

    public ExpressionResolver getExpressionResolver() {
        return expressionResolver;
    }

    public Map<String, CopyOnWriteArrayList<EventEnvelope>> events() {
        return Collections.unmodifiableMap(events);
    }

    public Map<String, CopyOnWriteArrayList<EventEnvelope>> getEvents() {
        return events();
    }

    public Map<String, TurnRecord> turns() {
        return Collections.unmodifiableMap(turns);
    }

    public Map<String, TurnRecord> getTurns() {
        return turns();
    }

    public String buildId() {
        return buildId;
    }

    public int ragSize() {
        if (rag instanceof CanonicalRagRetriever retriever && retriever.size() > 0) {
            return retriever.size();
        }
        // The Spring example profile may be launched from either the repository
        // root or java/meguri-core; report the canonical row count when that
        // relative path differs, without changing the injected provider itself.
        try {
            return new MockRagProvider(resolveDataRoot(), new ObjectMapper(), buildId).size();
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    public String llmProviderName() {
        return llm.providerName();
    }

    public String ragProviderName() {
        return rag.getClass().getSimpleName();
    }

    public String webSearchProviderName() {
        return webSearch.providerName();
    }

    public TurnRecord turn(String turnId) {
        return turns.get(turnId);
    }

    public TurnRecord getTurn(String turnId) {
        return turn(turnId);
    }

    public List<EventEnvelope> eventsFor(String sessionId) {
        List<EventEnvelope> value = events.get(sessionId);
        return value == null ? List.of() : List.copyOf(value);
    }

    public List<EventEnvelope> getEventsFor(String sessionId) {
        return eventsFor(sessionId);
    }

    /**
     * Accept a turn and schedule it in the background. Idempotency is scoped to
     * user/client/session exactly as the Python runtime contract specifies.
     */
    public synchronized TurnRecord start(TurnRequest request, String idempotencyKey) {
        Objects.requireNonNull(request, "request");
        String keyValue = idempotencyKey == null ? null : idempotencyKey.trim();
        if (keyValue != null && !keyValue.isEmpty()) {
            IdempotencyKey key = new IdempotencyKey(request.getUserId(), request.getClientId(),
                    request.getSessionId(), keyValue);
            String existingId = idempotency.get(key);
            if (existingId != null) return turns.get(existingId);
            TurnRecord record = createRecord(request);
            idempotency.put(key, record.getTurnId());
            schedule(record);
            return record;
        }
        TurnRecord record = createRecord(request);
        schedule(record);
        return record;
    }

    public TurnRecord start(TurnRequest request) {
        return start(request, null);
    }

    public Mono<TurnRecord> startAsync(TurnRequest request, String idempotencyKey) {
        return Mono.fromSupplier(() -> start(request, idempotencyKey));
    }

    public Mono<TurnRecord> startAsync(TurnRequest request) {
        return startAsync(request, null);
    }

    public Mono<ChatResponse> runInline(TurnRequest request) {
        TurnRecord record = createRecord(request);
        return runRecord(record)
                .then(Mono.defer(() -> {
                    if (record.getResult() != null) return Mono.just(record.getResult());
                    return Mono.error(new IllegalStateException(record.getError() == null
                            ? "turn ended with status " + record.getStatus() : record.getError()));
                }));
    }

    public TurnRecord cancel(String turnId) {
        TurnRecord record = turns.get(turnId);
        if (record == null) return null;
        if (!record.isTerminal()) {
            record.requestCancel();
            // The running pipeline checks the flag between every externally visible
            // chunk. If the provider is still pending, cancellation is observed by
            // the provider callback and the terminal event is emitted there.
        }
        return record;
    }

    public TurnRecord cancelTurn(String turnId) {
        return cancel(turnId);
    }

    public boolean sessionIsActive(String sessionId) {
        return turns.values().stream().anyMatch(record ->
                sessionId.equals(record.getRequest().getSessionId()) && !record.isTerminal());
    }

    /**
     * A cold, polling event stream. Polling avoids a replay/live race between the
     * initial snapshot and a newly appended event, and also gives us a deterministic
     * heartbeat for clients behind buffering proxies.
     */
    public Flux<EventEnvelope> sessionEvents(String sessionId, long afterSequence) {
        long cursor = Math.max(0L, afterSequence);
        return Flux.defer(() -> Flux.create(sink -> {
            final AtomicLong current = new AtomicLong(cursor);
            final AtomicLong lastEmissionNanos = new AtomicLong(System.nanoTime());
            Disposable ticker = Flux.interval(Duration.ofMillis(20))
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe(ignored -> {
                        if (sink.isCancelled()) return;
                        List<EventEnvelope> available = eventsFor(sessionId);
                        boolean emitted = false;
                        for (EventEnvelope event : available) {
                            if (event.getSequence() <= current.get()) continue;
                            current.set(event.getSequence());
                            sink.next(event);
                            emitted = true;
                            lastEmissionNanos.set(System.nanoTime());
                        }
                        if (!sessionIsActive(sessionId) && !hasAfter(available, current.get())) {
                            sink.complete();
                            return;
                        }
                        // Heartbeats are transport comments, not turn envelopes. The
                        // WebFlux controller emits them as SSE comments; this domain
                        // stream deliberately exposes only protocol events.
                        if (!emitted && System.nanoTime() - lastEmissionNanos.get() >= Duration.ofSeconds(1).toNanos()) {
                            lastEmissionNanos.set(System.nanoTime());
                        }
                    }, sink::error);
            sink.onDispose(ticker);
        }));
    }

    /** Used by tests and local lifecycle shutdown. */
    public synchronized void reset() {
        turns.values().forEach(record -> {
            if (!record.isTerminal()) {
                record.requestCancel();
                record.setStatus(TurnStatus.CANCELLED);
                record.completeDone();
            }
        });
        running.values().forEach(Disposable::dispose);
        running.clear();
        turns.clear();
        events.clear();
        sequence.clear();
        idempotency.clear();
        sessions.clear();
        stateMachine.clear();
    }

    private TurnRecord createRecord(TurnRequest request) {
        String turnId = newId("turn");
        String traceId = newId("trace");
        TurnRecord record = new TurnRecord(turnId, traceId, request);
        turns.put(turnId, record);
        return record;
    }

    private void schedule(TurnRecord record) {
        // Defer the state transition until the background subscription starts so
        // POST /v1/turns can faithfully return the accepted status (the Python
        // asyncio task is likewise scheduled after the response is constructed).
        Disposable disposable = Mono.defer(() -> runRecord(record))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        ignored -> { },
                        ignored -> { /* runRecord records the failure */ });
        running.put(record.getTurnId(), disposable);
    }

    private Mono<Void> runRecord(TurnRecord record) {
        TurnRequest request = record.getRequest();
        record.setStatus(TurnStatus.RUNNING);
        RuntimeState state;
        try {
            state = stateMachine.stateFor(request);
            emit(record, "turn.started", Map.of("runtime_state", beanMap(state)));
        } catch (Throwable error) {
            return fail(record, error);
        }

        List<String> canon;
        try {
            canon = rag.search(request.getMessage(), state, 3);
            if (canon == null) canon = List.of();
        } catch (Throwable error) {
            return fail(record, error);
        }
        List<String> recent = sessions.recent(request.getUserId(), request.getClientId(), request.getSessionId())
                .stream().map(item -> item.role() + ": " + item.content()).toList();

        List<String> finalCanon = canon;
        return memory.recall(request)
                .onErrorReturn(MemoryRecall.unavailable())
                .flatMap(recall -> retrieveWeb(record, request)
                        .flatMap(web -> Mono.defer(() -> llm.respond(request, state, finalCanon,
                                recall.memories(), recent, web.contextLines()))))
                .switchIfEmpty(Mono.error(new IllegalStateException("LLM provider returned no response")))
                .flatMap(response -> completeSemantic(record, state, response))
                .onErrorResume(error -> fail(record, error));
    }

    private Mono<WebSearchRecall> retrieveWeb(TurnRecord record, TurnRequest request) {
        boolean shouldSearch;
        try {
            shouldSearch = webSearch.shouldSearch(request.getMessage());
        } catch (RuntimeException ignored) {
            shouldSearch = false;
        }
        if (!shouldSearch) return Mono.just(WebSearchRecall.disabled());

        String query = WebSearchPolicy.queryFor(request.getMessage());
        emit(record, "tool.started", Map.of(
                "tool_name", "web_search",
                "provider", webSearch.providerName(),
                "query_length", query.length()));
        return webSearch.search(query, 5)
                .onErrorReturn(WebSearchRecall.unavailable(webSearch.providerName()))
                .doOnNext(recall -> emit(record, "tool.completed", Map.of(
                        "tool_name", "web_search",
                        "provider", recall.provider(),
                        "status", recall.status(),
                        "result_count", recall.results().size())));
    }

    private Mono<Void> completeSemantic(TurnRecord record, RuntimeState state, LlmResponse response) {
        TurnRequest request = record.getRequest();
        if (record.isCancelRequested()) return cancelRecord(record, "client_requested");
        sessions.append(request.getUserId(), request.getClientId(), request.getSessionId(),
                new SessionContextStore.Message("user", request.getMessage()));
        ResolvedExpression expression;
        try {
            expression = expressionResolver.resolve(response, state);
        } catch (Throwable ignored) {
            expression = new ResolvedExpression(ExpressionTag.NEUTRAL, Intensity.LOW, state.getOutfitCode());
        }
        Map<String, Object> semantic = new LinkedHashMap<>(beanMap(response));
        // The semantic event carries both the LLM decision and the deterministic
        // renderer cue so an AIRI adapter does not have to recreate Meguri rules.
        semantic.putAll(beanMap(expression));
        emit(record, "semantic.completed", semantic);
        ResolvedExpression finalExpression = expression;
        java.util.concurrent.atomic.AtomicInteger deltaIndex = new java.util.concurrent.atomic.AtomicInteger();
        return Flux.fromIterable(chunks(response.getReply(), TEXT_CHUNK_SIZE))
                .concatMap(delta -> {
                    if (record.isCancelRequested()) return cancelRecord(record, "client_requested");
                    int index = deltaIndex.incrementAndGet();
                    emit(record, "text.delta", Map.of("delta", delta, "index", index));
                    return Mono.delay(streamInterval).then();
                })
                .then(Mono.defer(() -> {
                    if (record.isCancelRequested()) return cancelRecord(record, "client_requested");
                    emit(record, "text.completed", Map.of("text", response.getReply()));
                    if (request.getClientCapabilities().isVoice()) {
                        emit(record, "tts.requested", Map.of(
                                "text", response.getReply(),
                                "voice_style", response.getVoiceStyle().value(),
                                "expression_intensity", response.getExpressionIntensity().value()));
                    }
                    sessions.append(request.getUserId(), request.getClientId(), request.getSessionId(),
                            new SessionContextStore.Message("assistant", response.getReply()));
                    emit(record, "expression.cue", beanMap(finalExpression));
                    emit(record, "sprite.resolved", beanMap(finalExpression));
                    return memory.write(request, response, record.getTurnId(), record.getTraceId())
                            .onErrorReturn(MemoryWriteResult.unavailable())
                            .flatMap(write -> {
                                for (Object event : write.events()) {
                                    emit(record, "memory.candidate.created", asObjectMap(event));
                                }
                                emit(record, "memory.write.completed", Map.of(
                                        "status", write.status(),
                                        "written_ids", write.writtenIds(),
                                        "candidate_ids", write.candidateIds(),
                                        "decisions", write.decisions()));
                                MemoryStatus status = switch (write.status()) {
                                    case "written" -> MemoryStatus.WRITTEN;
                                    case "pending" -> MemoryStatus.PENDING;
                                    default -> MemoryStatus.UNAVAILABLE;
                                };
                                ChatResponse result = new ChatResponse(record.getTurnId(), request.getSessionId(), response,
                                        state, finalExpression, status, buildId);
                                record.setResult(result);
                                record.setStatus(TurnStatus.COMPLETED);
                                emit(record, "turn.completed", Map.of("reply", response.getReply()));
                                record.completeDone();
                                return Mono.empty();
                            });
                }));
    }

    private Mono<Void> cancelRecord(TurnRecord record, String reason) {
        if (!record.isTerminal()) {
            record.setStatus(TurnStatus.CANCELLED);
            emit(record, "turn.cancelled", Map.of("reason", reason));
            record.completeDone();
        }
        return Mono.empty();
    }

    private Mono<Void> fail(TurnRecord record, Throwable error) {
        if (record.isTerminal()) return Mono.empty();
        record.setStatus(TurnStatus.FAILED);
        String message = error == null || error.getMessage() == null ? "turn failed" : error.getMessage();
        record.setError(message);
        emit(record, "turn.failed", Map.of("error", message));
        record.completeDone();
        return Mono.empty();
    }

    private EventEnvelope emit(TurnRecord record, String type, Map<String, Object> data) {
        if (!TurnEventTypes.isSupported(type)) {
            throw new IllegalArgumentException("unsupported turn event type: " + type);
        }
        String sessionId = record.getRequest().getSessionId();
        CopyOnWriteArrayList<EventEnvelope> stream = events.computeIfAbsent(
                sessionId, ignored -> new CopyOnWriteArrayList<>());
        AtomicLong counter = sequence.computeIfAbsent(sessionId, ignored -> new AtomicLong());
        // Increment and append under the same per-session lock. Without this, two
        // concurrent turns could reserve sequence 1/2 and append in reverse order.
        synchronized (stream) {
            long next = counter.incrementAndGet();
            EventEnvelope event = new EventEnvelope(type, record.getTurnId(), sessionId, next,
                    data == null ? Map.of() : data,
                    new EventMetadata(record.getTraceId(), "meguri-core", Instant.now(), buildId));
            stream.add(event);
            return event;
        }
    }

    private static boolean hasAfter(List<EventEnvelope> available, long cursor) {
        return available.stream().anyMatch(event -> event.getSequence() > cursor);
    }

    private static List<String> chunks(String value, int size) {
        if (value == null || value.isEmpty()) return List.of();
        List<String> output = new ArrayList<>();
        int offset = 0;
        while (offset < value.length()) {
            int end = Math.min(value.length(), offset + size);
            // Do not split a UTF-16 surrogate pair.
            if (end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))) end--;
            output.add(value.substring(offset, end));
            offset = end;
        }
        return output;
    }

    private Map<String, Object> beanMap(Object value) {
        if (value == null) return Map.of();
        try {
            return objectMapper.convertValue(value, Map.class);
        } catch (IllegalArgumentException ignored) {
            return Map.of("value", value);
        }
    }

    private Map<String, Object> asObjectMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        return beanMap(value);
    }

    private static String newId(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private static LlmProvider fallbackLlm() {
        return (request, state, canon, memories, recent) -> {
            String message = request.getMessage().trim();
            String reply = "收到，我听到了：" + message;
            return Mono.just(new LlmResponse(reply, ExpressionTag.HAPPY, Intensity.MEDIUM,
                    com.meguri.core.dto.VoiceStyle.SOFT, List.of()));
        };
    }

    private static RagProvider fallbackRag() {
        return (query, state, limit) -> List.of();
    }

    private static LlmProvider defaultLlm(ObjectMapper mapper) {
        try {
            return LlmProviderFactory.createFromEnvironment(mapper);
        } catch (RuntimeException ignored) {
            // Local startup must remain available when an optional remote provider
            // is incompletely configured; the deterministic fallback is explicit in
            // health output and never claims hosted readiness.
            return fallbackLlm();
        }
    }

    private static RagProvider defaultRag(String buildId) {
        try {
            Path root = resolveDataRoot();
            return new MockRagProvider(root, new ObjectMapper(), buildId);
        } catch (RuntimeException ignored) {
            return fallbackRag();
        }
    }

    private static Path resolveDataRoot() {
        String configured = System.getenv("MEGURI_DATA_ROOT");
        if (configured == null || configured.isBlank()) configured = System.getProperty("meguri.data-root");
        if (configured != null && !configured.isBlank()) return Path.of(configured);
        Path cwd = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        Path cursor = cwd;
        for (int depth = 0; depth < 8 && cursor != null; depth++, cursor = cursor.getParent()) {
            Path candidate = cursor.resolve("datasets").resolve("meguri");
            if (java.nio.file.Files.exists(candidate)) return candidate;
        }
        return cwd.resolve("datasets").resolve("meguri");
    }

    private record IdempotencyKey(String userId, String clientId, String sessionId, String key) { }
}

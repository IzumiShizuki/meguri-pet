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
import com.meguri.core.agent.AgentLifecycleEvent;
import com.meguri.core.agent.CancellationToken;
import com.meguri.core.capability.ApprovalService;
import com.meguri.core.capability.CapabilityDescriptor;
import com.meguri.core.capability.CapabilityImplementation;
import com.meguri.core.capability.CapabilityResult;
import com.meguri.core.capability.CapabilityRuntimeFacade;
import com.meguri.core.capability.ExposurePlanner;
import com.meguri.core.capability.ToolProposal;
import com.meguri.core.harness.EventCursor;
import com.meguri.core.harness.HarnessControlPlane;
import com.meguri.core.harness.HarnessManifest;
import com.meguri.core.harness.SessionReplayWindow;
import com.meguri.core.harness.SessionSnapshot;
import com.meguri.core.harness.TurnCommand;
import com.meguri.core.harness.TurnRuntime;
import com.meguri.core.harness.TurnSnapshot;
import com.meguri.core.harness.capability.CapabilityRegistry;
import com.meguri.core.harness.capability.CapabilityExecutor;
import com.meguri.core.harness.capability.CapabilityInputSchema;
import com.meguri.core.harness.capability.CapabilityPolicy;
import com.meguri.core.harness.capability.DefaultCapabilityPolicy;
import com.meguri.core.harness.capability.EffectLedger;
import com.meguri.core.harness.capability.InMemoryEffectLedger;
import com.meguri.core.harness.persona.DeterministicPersonaRuntime;
import com.meguri.core.harness.persona.PersonaRuntime;
import com.meguri.core.harness.retrieval.RetrievalBundle;
import com.meguri.core.harness.retrieval.RetrievalBundle.Lane;
import com.meguri.core.harness.retrieval.RetrievalBundle.LaneResult;
import com.meguri.core.harness.retrieval.RetrievalMode;
import com.meguri.core.llm.LlmProvider;
import com.meguri.core.llm.LlmProviderFactory;
import com.meguri.core.rag.CanonicalRagRetriever;
import com.meguri.core.rag.MockRagProvider;
import com.meguri.core.rag.RagProvider;
import com.meguri.core.resources.LocalResourcePromptContext;
import com.meguri.core.memory.MemoryGateway;
import com.meguri.core.memory.MemoryRecall;
import com.meguri.core.memory.MemoryWriteResult;
import com.meguri.core.retrieval.KnowledgeTurnRetrievalRuntime;
import com.meguri.core.retrieval.RetrievalLaneResult;
import com.meguri.core.retrieval.SourceType;
import com.meguri.core.training.TrainingFeedbackRequest;
import com.meguri.core.training.TrainingFeedbackService;
import com.meguri.core.websearch.NoopWebSearchGateway;
import com.meguri.core.websearch.WebSearchGateway;
import com.meguri.core.websearch.WebSearchPolicy;
import com.meguri.core.websearch.WebSearchRecall;
import com.meguri.core.weather.WeatherConversationService;
import com.meguri.core.weather.WeatherConversationService.TurnWeatherContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.BufferOverflowStrategy;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Coordinates one turn from acceptance through semantic/text/expression/memory
 * events. The event log is session-scoped and append-only for reconnect replay.
 */
@Service
public class TurnOrchestrator implements TurnRuntime, HarnessControlPlane {
    private static final int SUBSCRIBER_EVENT_BUFFER = 256;

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
    private final TrainingFeedbackService trainingFeedback;
    private final WeatherConversationService weatherConversation;
    private final TurnJournal journal;
    private final PersonaRuntime personaRuntime;
    private final CapabilityRegistry capabilities;
    private final CapabilityExecutor capabilityExecutor;
    private final CapabilityRuntimeFacade capabilityRuntime;
    private volatile KnowledgeTurnRetrievalRuntime knowledgeRetrieval;

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
                            WebSearchGateway webSearch, ObjectMapper objectMapper,
                            TrainingFeedbackService trainingFeedback,
                            WeatherConversationService weatherConversation,
                            TurnJournal journal,
                            SessionContextPersistence contextPersistence,
                            CapabilityRuntimeFacade capabilityRuntime) {
        this(llm, rag, new RuntimeStateMachine(), new ExpressionResolver(), Duration.ofMillis(10), objectMapper,
                memory, webSearch, trainingFeedback, weatherConversation, journal,
                contextPersistence, capabilityRuntime);
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
        this(llm, rag, stateMachine, expressionResolver, streamInterval, objectMapper,
                memory, webSearch, TrainingFeedbackService.disabled(objectMapper));
    }

    public TurnOrchestrator(LlmProvider llm, RagProvider rag,
                            RuntimeStateMachine stateMachine,
                            ExpressionResolver expressionResolver,
                            Duration streamInterval,
                            ObjectMapper objectMapper,
                            MemoryGateway memory,
                            WebSearchGateway webSearch,
                            TrainingFeedbackService trainingFeedback) {
        this(llm, rag, stateMachine, expressionResolver, streamInterval, objectMapper, memory, webSearch,
                trainingFeedback, WeatherConversationService.disabled());
    }

    public TurnOrchestrator(LlmProvider llm, RagProvider rag,
                            RuntimeStateMachine stateMachine,
                            ExpressionResolver expressionResolver,
                            Duration streamInterval,
                            ObjectMapper objectMapper,
                            MemoryGateway memory,
                            WebSearchGateway webSearch,
                            TrainingFeedbackService trainingFeedback,
                            WeatherConversationService weatherConversation) {
        this(llm, rag, stateMachine, expressionResolver, streamInterval, objectMapper,
                memory, webSearch, trainingFeedback, weatherConversation, null);
    }

    public TurnOrchestrator(LlmProvider llm, RagProvider rag,
                            RuntimeStateMachine stateMachine,
                            ExpressionResolver expressionResolver,
                            Duration streamInterval,
                            ObjectMapper objectMapper,
                            MemoryGateway memory,
                            WebSearchGateway webSearch,
                            TrainingFeedbackService trainingFeedback,
                            WeatherConversationService weatherConversation,
                            TurnJournal journal) {
        this(llm, rag, stateMachine, expressionResolver, streamInterval, objectMapper,
                memory, webSearch, trainingFeedback, weatherConversation, journal,
                new NoopSessionContextPersistence());
    }

    public TurnOrchestrator(LlmProvider llm, RagProvider rag,
                            RuntimeStateMachine stateMachine,
                            ExpressionResolver expressionResolver,
                            Duration streamInterval,
                            ObjectMapper objectMapper,
                            MemoryGateway memory,
                            WebSearchGateway webSearch,
                            TrainingFeedbackService trainingFeedback,
                            WeatherConversationService weatherConversation,
                            TurnJournal journal,
                            SessionContextPersistence contextPersistence) {
        this(llm, rag, stateMachine, expressionResolver, streamInterval, objectMapper,
                memory, webSearch, trainingFeedback, weatherConversation, journal,
                contextPersistence, null);
    }

    public TurnOrchestrator(LlmProvider llm, RagProvider rag,
                            RuntimeStateMachine stateMachine,
                            ExpressionResolver expressionResolver,
                            Duration streamInterval,
                            ObjectMapper objectMapper,
                            MemoryGateway memory,
                            WebSearchGateway webSearch,
                            TrainingFeedbackService trainingFeedback,
                            WeatherConversationService weatherConversation,
                            TurnJournal journal,
                            SessionContextPersistence contextPersistence,
                            CapabilityRuntimeFacade capabilityRuntime) {
        this.stateMachine = Objects.requireNonNull(stateMachine, "stateMachine");
        this.expressionResolver = Objects.requireNonNull(expressionResolver, "expressionResolver");
        this.buildId = expressionResolver.buildId();
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.llm = llm == null ? defaultLlm(this.objectMapper) : llm;
        this.rag = rag == null ? defaultRag(this.buildId) : rag;
        this.streamInterval = streamInterval == null ? Duration.ofMillis(10) : streamInterval;
        this.sessions = new SessionContextStore(20,
                contextPersistence == null ? new NoopSessionContextPersistence() : contextPersistence);
        this.memory = memory == null ? new com.meguri.core.memory.NoopMemoryGateway() : memory;
        this.webSearch = webSearch == null ? new NoopWebSearchGateway() : webSearch;
        this.trainingFeedback = trainingFeedback == null
                ? TrainingFeedbackService.disabled(this.objectMapper) : trainingFeedback;
        this.weatherConversation = weatherConversation == null
                ? WeatherConversationService.disabled() : weatherConversation;
        this.journal = journal == null ? new InMemoryTurnJournal(this.objectMapper) : journal;
        this.personaRuntime = new DeterministicPersonaRuntime(this.stateMachine, this.objectMapper);
        this.capabilities = defaultCapabilities();
        this.capabilityExecutor = defaultCapabilityExecutor();
        this.capabilityRuntime = capabilityRuntime == null
                ? new CapabilityRuntimeFacade(32) : capabilityRuntime;
        registerRuntimeCapabilities();
    }

    @Autowired(required = false)
    public void configureKnowledgeRetrieval(KnowledgeTurnRetrievalRuntime runtime) {
        this.knowledgeRetrieval = runtime;
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
        Map<String, CopyOnWriteArrayList<EventEnvelope>> snapshot = new LinkedHashMap<>();
        journal.turns().values().stream()
                .map(record -> record.getRequest().getSessionId())
                .distinct()
                .forEach(sessionId -> snapshot.put(sessionId,
                        new CopyOnWriteArrayList<>(journal.events(sessionId))));
        return Collections.unmodifiableMap(snapshot);
    }

    public Map<String, CopyOnWriteArrayList<EventEnvelope>> getEvents() {
        return events();
    }

    public Map<String, TurnRecord> turns() {
        return journal.turns();
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

    public String memoryProviderName() {
        return memory.getClass().getSimpleName();
    }

    public List<EffectLedger.Receipt> effectReceipts(String turnId) {
        return capabilityExecutor.ledger().receipts(turnId);
    }

    public List<com.meguri.core.capability.CapabilityAudit.Event> capabilityAuditEvents() {
        return capabilityRuntime.auditEvents();
    }

    public List<CapabilityDescriptor> availableRuntimeCapabilities() {
        return capabilityRuntime.availableDescriptors();
    }

    /**
     * Executes a remote Agent submission through the owning Turn's frozen
     * Capability snapshot. The explicit HTTP request is the approval action;
     * identity and exposure are still enforced by the runtime.
     */
    public <T> Mono<T> executeAgentCapability(
            String turnId,
            String tenantId,
            String userId,
            String clientId,
            String sessionId,
            Map<String, Object> input,
            Supplier<Mono<T>> operation) {
        Objects.requireNonNull(operation, "operation");
        return executeAgentCapability(
                turnId, tenantId, userId, clientId, sessionId, input,
                ignored -> operation.get());
    }

    public <T> Mono<T> executeAgentCapability(
            String turnId,
            String tenantId,
            String userId,
            String clientId,
            String sessionId,
            Map<String, Object> input,
            Function<AgentExecutionScope, Mono<T>> operation) {
        Objects.requireNonNull(operation, "operation");
        return Mono.defer(() -> {
            TurnRecord record = journal.turn(required(turnId, "turnId"));
            if (record == null) {
                return Mono.error(new IllegalArgumentException(
                        "owning turn was not found"));
            }
            TurnRequest request = record.getRequest();
            if (!request.tenantId().equals(required(tenantId, "tenantId"))
                    || !request.getUserId().equals(required(userId, "userId"))
                    || !request.getClientId().equals(required(clientId, "clientId"))
                    || !request.getSessionId().equals(required(sessionId, "sessionId"))) {
                return Mono.error(new SecurityException(
                        "agent invocation does not own the turn"));
            }
            if (record.isTerminal() || record.isCancelRequested()) {
                return Mono.error(new IllegalStateException(
                        "agent invocation requires an active turn"));
            }
            CapabilityRuntimeFacade.TurnCapabilities frozen =
                    record.getRuntimeCapabilities();
            if (frozen == null || !frozen.exposes("agent.invoke")) {
                return Mono.error(new SecurityException(
                        "agent.invoke is not exposed for this turn"));
            }
            AgentExecutionScope scope = new AgentExecutionScope(
                    record.getDeadlineAt(),
                    record.getTraceId(),
                    frozen.snapshotId(),
                    record.agentCancellation().child());
            return executeCapability(
                    record, "agent.invoke", Map.copyOf(input), true,
                    () -> operation.apply(scope));
        });
    }

    public record AgentExecutionScope(
            Instant deadlineAt,
            String traceId,
            String capabilitySnapshotVersion,
            CancellationToken cancellation) {
        public AgentExecutionScope {
            Objects.requireNonNull(deadlineAt, "deadlineAt");
            traceId = required(traceId, "traceId");
            capabilitySnapshotVersion = required(
                    capabilitySnapshotVersion, "capabilitySnapshotVersion");
            Objects.requireNonNull(cancellation, "cancellation");
        }
    }

    public void recordAgentLifecycle(AgentLifecycleEvent event) {
        if (event == null || event.turnId() == null) return;
        TurnRecord record = journal.turn(event.turnId());
        if (record == null) return;
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("lifecycle_sequence", event.sequence());
        data.put("lifecycle_type", event.type().name().toLowerCase(
                java.util.Locale.ROOT));
        putIfPresent(data, "execution_id", event.executionId());
        putIfPresent(data, "step_execution_id", event.stepExecutionId());
        putIfPresent(data, "task_id", event.taskId());
        putIfPresent(data, "remote_task_id", event.remoteTaskId());
        putIfPresent(data, "from_status", event.fromStatus());
        putIfPresent(data, "to_status", event.toStatus());
        putIfPresent(data, "detail", event.detail());
        emit(record, agentEventType(event), Map.copyOf(data));
    }

    @Override
    public HarnessDescription describe() {
        return new HarnessDescription(
                EventEnvelope.CURRENT_PROTOCOL_VERSION,
                buildId,
                llmProviderName(),
                ragProviderName(),
                memoryProviderName(),
                webSearchProviderName(),
                ragSize());
    }

    @Override
    public RuntimeState resolvePersona(TurnRequest request) {
        return personaRuntime.reduce(request).state();
    }

    @Override
    public void setRuntimeOverride(String scope, com.meguri.core.dto.RuntimeOverride override) {
        stateMachine.setOverride(scope, override);
    }

    @Override
    public void clearRuntimeOverride(String scope) {
        stateMachine.clearOverride(scope);
    }

    public TurnRecord turn(String turnId) {
        return journal.turn(turnId);
    }

    public TurnRecord getTurn(String turnId) {
        return turn(turnId);
    }

    public List<EventEnvelope> eventsFor(String sessionId) {
        return journal.events(sessionId);
    }

    public List<EventEnvelope> getEventsFor(String sessionId) {
        return eventsFor(sessionId);
    }

    /** Bounded, identity-isolated snapshots available to the sleep-memory service. */
    public List<SessionContextStore.Snapshot> sessionSnapshots() {
        return sessions.snapshots();
    }

    public List<SessionContextStore.Message> sessionMessages(String userId, String clientId, String sessionId) {
        return sessions.recent(userId, clientId, sessionId);
    }

    public void mergeCandidateSession(String userId, String clientId, String parentSessionId, String candidateSessionId) {
        sessions.mergeInto(userId, clientId, parentSessionId, candidateSessionId);
    }

    /**
     * Accept a turn and schedule it in the background. Idempotency is scoped to
     * user/client/session exactly as the Python runtime contract specifies.
     */
    public synchronized TurnRecord start(TurnRequest request, String idempotencyKey) {
        Objects.requireNonNull(request, "request");
        TurnJournal.Acceptance acceptance = journal.accept(
                request, idempotencyKey, Instant.now().plus(TurnCommand.DEFAULT_DEADLINE));
        if (acceptance.created()) {
            prepareRecord(acceptance.record());
            schedule(acceptance.record());
        }
        return acceptance.record();
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
        TurnRecord record = createRecord(request, Instant.now().plus(TurnCommand.DEFAULT_DEADLINE));
        return runWithDeadline(record)
                .then(Mono.defer(() -> {
                    if (record.getResult() != null) return Mono.just(record.getResult());
                    return Mono.error(new IllegalStateException(record.getError() == null
                            ? "turn ended with status " + record.getStatus() : record.getError()));
                }));
    }

    public TurnRecord cancel(String turnId) {
        TurnRecord record = journal.turn(turnId);
        if (record == null) return null;
        if (!record.isTerminal()) {
            record.requestCancel();
            Disposable active = running.remove(turnId);
            if (active != null) active.dispose();
            cancelRecord(record, "client_requested");
        }
        return record;
    }

    public TurnRecord cancelTurn(String turnId) {
        return cancel(turnId);
    }

    public boolean sessionIsActive(String sessionId) {
        return sessionIsActive(sessionId, null, null);
    }

    private boolean sessionIsActive(String sessionId, String userId, String clientId) {
        return journal.turns().values().stream().anyMatch(record ->
                sessionId.equals(record.getRequest().getSessionId())
                        && (userId == null || userId.equals(record.getRequest().getUserId()))
                        && (clientId == null || clientId.equals(record.getRequest().getClientId()))
                        && !record.isTerminal());
    }

    @Override
    public Mono<TurnSnapshot> submit(TurnCommand command) {
        return Mono.fromSupplier(() -> {
            if (command instanceof TurnCommand.Start start) {
                TurnJournal.Acceptance acceptance = journal.accept(
                        start.request(), start.idempotencyKey(), start.deadlineAt());
                if (acceptance.created()) {
                    prepareRecord(acceptance.record());
                    schedule(acceptance.record());
                }
                return snapshotOf(acceptance.record());
            }
            TurnCommand.Cancel cancel = (TurnCommand.Cancel) command;
            TurnRecord record = cancel(cancel.turnId());
            return record == null ? null : snapshotOf(record);
        }).flatMap(snapshot -> snapshot == null ? Mono.empty() : Mono.just(snapshot));
    }

    @Override
    public Flux<EventEnvelope> events(EventCursor cursor) {
        Flux<EventEnvelope> stream = sessionEvents(
                cursor.sessionId(), cursor.afterSequence(), cursor.userId(), cursor.clientId());
        return cursor.turnId() == null
                ? stream
                : stream.filter(event -> cursor.turnId().equals(event.getTurnId()));
    }

    @Override
    public Mono<TurnSnapshot> snapshot(String turnId) {
        return Mono.justOrEmpty(journal.turn(turnId)).map(this::snapshotOf);
    }

    @Override
    public Mono<SessionSnapshot> sessionSnapshot(
            String sessionId, String userId, String clientId) {
        return Mono.fromSupplier(() -> {
            List<TurnSnapshot> snapshots = journal.turns().values().stream()
                    .filter(record -> sessionId.equals(record.getRequest().getSessionId()))
                    .filter(record -> userId == null || userId.equals(record.getRequest().getUserId()))
                    .filter(record -> clientId == null || clientId.equals(record.getRequest().getClientId()))
                    .sorted(java.util.Comparator.comparing(TurnRecord::getAcceptedAt))
                    .map(this::snapshotOf)
                    .toList();
            if (snapshots.isEmpty()) return null;

            List<EventEnvelope> visibleEvents = journal.events(sessionId).stream()
                    .filter(event -> eventBelongsTo(event, userId, clientId))
                    .sorted(java.util.Comparator.comparingLong(EventEnvelope::getSequence))
                    .toList();
            Map<String, EventEnvelope> latestState = new LinkedHashMap<>();
            visibleEvents.stream()
                    .filter(event -> event.getReplayPolicy()
                            == com.meguri.core.adapter.domain.ReplayPolicy.STATE)
                    .forEach(event -> latestState.put(
                            event.getTurnId() + "\u0000" + event.getType(), event));
            List<EventEnvelope> stateEvents = latestState.values().stream()
                    .sorted(java.util.Comparator.comparingLong(EventEnvelope::getSequence))
                    .toList();
            return new SessionSnapshot(
                    sessionId,
                    visibleEvents.stream().mapToLong(EventEnvelope::getSequence)
                            .max().orElse(0L),
                    snapshots,
                    stateEvents,
                    visibleEvents.stream()
                            .map(EventEnvelope::getEventId)
                            .distinct()
                            .toList(),
                    visibleEvents.stream()
                            .filter(event -> event.getReplayPolicy()
                                    == com.meguri.core.adapter.domain.ReplayPolicy.ONCE)
                            .map(EventEnvelope::getEventId)
                            .distinct()
                            .toList(),
                    Instant.now());
        }).flatMap(value -> value == null ? Mono.empty() : Mono.just(value));
    }

    @Override
    public Mono<SessionReplayWindow> replayWindow(
            String sessionId, String userId, String clientId) {
        return Mono.fromSupplier(() -> {
            List<EventEnvelope> visible = journal.events(sessionId).stream()
                    .filter(event -> eventBelongsTo(event, userId, clientId))
                    .toList();
            long oldest = visible.stream().mapToLong(EventEnvelope::getSequence)
                    .min().orElse(0L);
            long latest = visible.stream().mapToLong(EventEnvelope::getSequence)
                    .max().orElse(0L);
            return new SessionReplayWindow(sessionId, oldest, latest);
        });
    }

    /**
     * A cold, polling event stream. Polling avoids a replay/live race between the
     * initial snapshot and a newly appended event, and also gives us a deterministic
     * heartbeat for clients behind buffering proxies.
     */
    public Flux<EventEnvelope> sessionEvents(String sessionId, long afterSequence) {
        return sessionEvents(sessionId, afterSequence, null, null);
    }

    private Flux<EventEnvelope> sessionEvents(
            String sessionId, long afterSequence, String userId, String clientId) {
        long cursor = Math.max(0L, afterSequence);
        return Flux.defer(() -> Flux.<EventEnvelope>create(sink -> {
            final AtomicLong current = new AtomicLong(cursor);
            final AtomicLong lastEmissionNanos = new AtomicLong(System.nanoTime());
            Disposable ticker = Flux.interval(Duration.ofMillis(20))
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe(ignored -> {
                        if (sink.isCancelled()) return;
                        List<EventEnvelope> available = eventsFor(sessionId).stream()
                                .filter(event -> eventBelongsTo(event, userId, clientId))
                                .sorted(java.util.Comparator.comparingLong(EventEnvelope::getSequence))
                                .toList();
                        boolean emitted = false;
                        for (EventEnvelope event : available) {
                            if (event.getSequence() <= current.get()) continue;
                            current.set(event.getSequence());
                            sink.next(event);
                            emitted = true;
                            lastEmissionNanos.set(System.nanoTime());
                        }
                        if (!sessionIsActive(sessionId, userId, clientId)
                                && !hasAfter(available, current.get())) {
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
        }, FluxSink.OverflowStrategy.ERROR).onBackpressureBuffer(
                SUBSCRIBER_EVENT_BUFFER,
                ignored -> { },
                BufferOverflowStrategy.ERROR));
    }

    private boolean eventBelongsTo(EventEnvelope event, String userId, String clientId) {
        if (userId == null && clientId == null) return true;
        TurnRecord owner = journal.turn(event.getTurnId());
        return owner != null
                && (userId == null || userId.equals(owner.getRequest().getUserId()))
                && (clientId == null || clientId.equals(owner.getRequest().getClientId()));
    }

    /** Used by tests and local lifecycle shutdown. */
    public synchronized void reset() {
        journal.turns().values().forEach(record -> {
            capabilityRuntime.release(record.getRuntimeCapabilities());
            if (record.tryCancel()) {
                record.completeDone();
            }
        });
        running.values().forEach(Disposable::dispose);
        running.clear();
        journal.clear();
        sessions.clear();
        stateMachine.clear();
    }

    private TurnRecord createRecord(TurnRequest request, Instant deadlineAt) {
        TurnRecord record = journal.create(request, deadlineAt);
        prepareRecord(record);
        return record;
    }

    private void prepareRecord(TurnRecord record) {
        if (record.getManifest() != null) return;
        TurnRequest request = record.getRequest();
        Instant frozenAt = Instant.now();
        PersonaRuntime.PersonaSnapshot persona = personaRuntime.reduce(request);
        CapabilityRegistry.Snapshot capabilitySnapshot = capabilities.freeze();
        CapabilityRuntimeFacade.TurnCapabilities runtimeCapabilities =
                capabilityRuntime.freeze(exposureContext(record));
        KnowledgeTurnRetrievalRuntime.Snapshot knowledgeSnapshot = null;
        if (knowledgeRetrieval != null) {
            try {
                knowledgeSnapshot = knowledgeRetrieval.freeze(
                        request.tenantId(), frozenAt);
            } catch (RuntimeException ignored) {
                // Knowledge is optional for the companion response, but a failed
                // authority read must never expose a partially loaded snapshot.
            }
        }
        record.freezePersonaSnapshot(persona);
        record.freezeCapabilitySnapshot(capabilitySnapshot);
        record.freezeRuntimeCapabilities(runtimeCapabilities);
        record.freezeManifest(new HarnessManifest(
                EventEnvelope.CURRENT_PROTOCOL_VERSION,
                buildId,
                "ctx-" + sessions.revision(request.getUserId(), request.getClientId(), request.getSessionId()),
                persona.revision(),
                runtimeCapabilities.snapshotId(),
                runtimeCapabilities.exposed().stream()
                        .map(CapabilityRuntimeFacade.CapabilityRef::id)
                        .toList(),
                knowledgeSnapshot == null
                        ? "knowledge:unavailable" : knowledgeSnapshot.id(),
                knowledgeSnapshot == null ? 0L : knowledgeSnapshot.revision(),
                frozenAt));
        journal.persist(record);
    }

    private TurnSnapshot snapshotOf(TurnRecord record) {
        return TurnSnapshot.from(record, journal.lastSequence(record.getRequest().getSessionId()));
    }

    private void schedule(TurnRecord record) {
        // Defer the state transition until the background subscription starts so
        // POST /v1/turns can faithfully return the accepted status (the Python
        // asyncio task is likewise scheduled after the response is constructed).
        Disposable disposable = Mono.defer(() -> runWithDeadline(record))
                .subscribeOn(Schedulers.boundedElastic())
                .doFinally(ignored -> running.remove(record.getTurnId()))
                .subscribe(
                        ignored -> { },
                        ignored -> { /* runRecord records the failure */ });
        running.put(record.getTurnId(), disposable);
        if (disposable.isDisposed()) running.remove(record.getTurnId(), disposable);
    }

    private Mono<Void> runWithDeadline(TurnRecord record) {
        return runRecord(record)
                .timeout(record.remaining())
                .onErrorResume(TimeoutException.class,
                        error -> fail(record, new IllegalStateException("turn deadline exceeded")));
    }

    private Mono<Void> runRecord(TurnRecord record) {
        TurnRequest request = record.getRequest();
        record.setStatus(TurnStatus.RUNNING);
        record.transitionTo(TurnStage.PLANNING);
        journal.persist(record);
        RuntimeState state;
        try {
            state = record.getPersonaSnapshot().state();
            emit(record, "turn.started", Map.of(
                    "stage", record.getStage().wireValue(),
                    "runtime_state", beanMap(state),
                    "manifest", beanMap(record.getManifest()),
                    "persona_provenance", beanMap(record.getPersonaSnapshot().provenance())));
        } catch (Throwable error) {
            return fail(record, error);
        }

        transition(record, TurnStage.RETRIEVING);
        List<String> recent = recentContext(request);
        List<String> promptRecent = appendContext(recent,
                LocalResourcePromptContext.from(request.attachments()));
        RetrievalMode retrievalMode = request.retrievalMode();

        Mono<LaneResult> lore = retrievalMode.permitsLocalRetrieval()
                ? executeCapability(
                        record,
                        "lore.read",
                        Map.of("query", request.getMessage()),
                        false,
                        () -> Mono.fromCallable(() -> {
                                    List<String> found = rag.search(request.getMessage(), state, 3);
                                    return found == null ? List.<String>of() : List.copyOf(found);
                                })
                                .subscribeOn(Schedulers.boundedElastic()))
                        .timeout(laneTimeout(record,
                                capabilityTimeout(record, "lore.read", Duration.ofSeconds(8))))
                        .map(items -> new LaneResult(
                                Lane.LORE,
                                items.isEmpty() ? "empty" : "ok",
                                ragProviderName(),
                                items))
                        .onErrorReturn(new LaneResult(
                                Lane.LORE,
                                "unavailable",
                                ragProviderName(),
                                List.of()))
                : Mono.just(new LaneResult(
                        Lane.LORE,
                        "disabled",
                        ragProviderName(),
                        List.of()));
        Mono<MemoryRecall> recalledMemory = retrievalMode.permitsLocalRetrieval()
                ? executeCapability(
                        record,
                        "memory.read",
                        Map.of("query", request.getMessage()),
                        false,
                        () -> memory.recall(request))
                        .timeout(laneTimeout(record,
                                capabilityTimeout(record, "memory.read", Duration.ofSeconds(5))))
                        .onErrorReturn(MemoryRecall.unavailable())
                : Mono.just(MemoryRecall.unavailable());
        Mono<TurnWeatherContext> weather = retrievalMode.permitsOpenRetrieval()
                ? executeCapability(
                        record,
                        "weather.read",
                        Map.of("message", request.getMessage()),
                        false,
                        () -> retrieveWeather(record, request))
                        .timeout(laneTimeout(record,
                                capabilityTimeout(record, "weather.read", Duration.ofSeconds(5))))
                        .onErrorResume(error -> weatherConversation.contextFor(""))
                : weatherConversation.contextFor("");
        Mono<WebSearchRecall> web = retrievalMode.permitsOpenRetrieval()
                ? executeCapability(
                        record,
                        "web.read",
                        Map.of("message", request.getMessage()),
                        false,
                        () -> retrieveWeb(record, request))
                        .timeout(laneTimeout(record,
                                capabilityTimeout(record, "web.read", Duration.ofSeconds(8))))
                        .onErrorReturn(WebSearchRecall.unavailable(webSearch.providerName()))
                : Mono.just(WebSearchRecall.disabled());
        Mono<KnowledgeRecall> knowledge = retrieveKnowledge(
                record, request, retrievalMode);

        return Mono.zip(lore, recalledMemory, weather, web, knowledge)
                .flatMap(retrieval -> {
                    RetrievalBundle bundle = new RetrievalBundle(
                            record.getTraceId(),
                            Instant.now(),
                            List.of(
                                    retrieval.getT1(),
                                    new LaneResult(
                                            Lane.MEMORY,
                                            retrievalMode == RetrievalMode.NONE
                                                    ? "disabled"
                                                    : retrieval.getT2().available() ? "ok" : "unavailable",
                                            memoryProviderName(),
                                            retrieval.getT2().memories()),
                                    new LaneResult(
                                            Lane.KNOWLEDGE_BASE,
                                            retrieval.getT5().status(),
                                            retrieval.getT5().provider(),
                                            retrieval.getT5().contextLines()),
                                    new LaneResult(
                                            Lane.WEB,
                                            retrieval.getT4().status(),
                                            retrieval.getT4().provider(),
                                            retrieval.getT4().contextLines())));
                    Map<String, Object> retrievalTrace = new LinkedHashMap<>(bundle.traceData());
                    retrievalTrace.put("retrieval_mode", retrievalMode.wireValue());
                    if (retrieval.getT5().typedBundle() != null) {
                        retrievalTrace.put(
                                "knowledge_trace",
                                beanMap(retrieval.getT5().typedBundle()));
                    }
                    emit(record, "retrieval.completed", Map.copyOf(retrievalTrace));
                    transition(record, TurnStage.GENERATING);
                    List<String> contextualRecent = appendContext(promptRecent, retrieval.getT3().promptContext());
                    List<String> canon = appendContext(
                            bundle.items(Lane.LORE),
                            bundle.items(Lane.KNOWLEDGE_BASE));
                    return llm.respond(request, state, canon, bundle.items(Lane.MEMORY),
                                    contextualRecent, bundle.items(Lane.WEB))
                            .map(retrieval.getT3()::augment)
                            .flatMap(first -> sampleAlternative(record, request, state, canon,
                                    bundle.items(Lane.MEMORY), contextualRecent,
                                    bundle.items(Lane.WEB), first, retrieval.getT3(), recent));
                })
                .switchIfEmpty(Mono.error(new IllegalStateException("LLM provider returned no response")))
                .flatMap(response -> {
                    transition(record, TurnStage.FINALIZING);
                    return completeSemantic(record, state, response);
                })
                .onErrorResume(error -> fail(record, error));
    }

    private Duration laneTimeout(TurnRecord record, Duration maximum) {
        Duration remaining = record.remaining();
        return remaining.compareTo(maximum) < 0 ? remaining : maximum;
    }

    private Duration capabilityTimeout(TurnRecord record, String capabilityId, Duration fallback) {
        CapabilityRegistry.Snapshot snapshot = record.getCapabilitySnapshot();
        if (snapshot == null) return fallback;
        return snapshot.grants().stream()
                .filter(descriptor -> capabilityId.equals(descriptor.id()))
                .map(CapabilityRegistry.Descriptor::timeout)
                .findFirst()
                .orElse(fallback);
    }

    private void transition(TurnRecord record, TurnStage stage) {
        record.transitionTo(stage);
        journal.persist(record);
        emit(record, "turn.stage.changed", Map.of("stage", stage.wireValue()));
    }

    private Mono<LlmResponse> sampleAlternative(
            TurnRecord record,
            TurnRequest request,
            RuntimeState state,
            List<String> canon,
            List<String> memories,
            List<String> recent,
            List<String> webResults,
            LlmResponse first,
            TurnWeatherContext weather,
            List<String> durableRecent) {
        if (!trainingFeedback.shouldCompare(request.trainingMode())) {
            if (request.trainingMode()) {
                trainingFeedback.registerSingle(record.getTurnId(), request, durableRecent, first,
                        llm.providerName(), buildId);
            }
            return Mono.just(first);
        }
        return llm.respondAlternative(request, state, canon, memories, recent, webResults)
                .map(weather::augment)
                .doOnNext(second -> {
                    Optional<Map<String, Object>> event = trainingFeedback.registerComparison(
                                record.getTurnId(), request, durableRecent, first, second,
                                llm.providerName(), buildId);
                    event.ifPresent(value -> emit(record, "training.candidates.ready", value));
                    if (event.isEmpty() && request.trainingMode()) {
                        trainingFeedback.registerSingle(record.getTurnId(), request, durableRecent, first,
                                llm.providerName(), buildId);
                    }
                })
                .thenReturn(first)
                .onErrorResume(error -> {
                    if (request.trainingMode()) {
                        trainingFeedback.registerSingle(record.getTurnId(), request, durableRecent, first,
                                llm.providerName(), buildId);
                    }
                    return Mono.just(first);
                });
    }

    private List<String> recentContext(TurnRequest request) {
        java.util.stream.Stream<SessionContextStore.Message> parent = request.parentSessionId() == null
                ? java.util.stream.Stream.empty()
                : sessions.recent(request.getUserId(), request.getClientId(), request.parentSessionId()).stream();
        return java.util.stream.Stream.concat(parent,
                        sessions.recent(request.getUserId(), request.getClientId(), request.getSessionId()).stream())
                .map(item -> item.role() + ": " + item.content()).toList();
    }

    private Mono<KnowledgeRecall> retrieveKnowledge(
            TurnRecord record, TurnRequest request, RetrievalMode mode) {
        if (mode == RetrievalMode.NONE) {
            return Mono.just(KnowledgeRecall.disabled());
        }
        KnowledgeTurnRetrievalRuntime runtime = knowledgeRetrieval;
        if (runtime == null) {
            return Mono.just(KnowledgeRecall.notConfigured());
        }
        HarnessManifest manifest = record.getManifest();
        if (manifest == null
                || !manifest.knowledgeSnapshotId().startsWith("ks1.")) {
            return Mono.just(KnowledgeRecall.unavailable());
        }
        com.meguri.core.retrieval.RetrievalMode typedMode = switch (mode) {
            case NONE -> com.meguri.core.retrieval.RetrievalMode.NONE;
            case FAST -> com.meguri.core.retrieval.RetrievalMode.FAST;
            case SLOW -> com.meguri.core.retrieval.RetrievalMode.SLOW;
        };
        return executeCapability(
                record,
                "knowledge.read",
                Map.of("query", request.getMessage()),
                false,
                () -> Mono.fromCallable(() -> runtime.retrieve(
                                request.getMessage(),
                                typedMode,
                                request.tenantId(),
                                request.getUserId(),
                                Set.of(),
                                manifest.knowledgeSnapshotId(),
                                manifest.knowledgeRevision(),
                                manifest.frozenAt(),
                                record.getDeadlineAt(),
                                record.getTraceId()))
                        .subscribeOn(Schedulers.boundedElastic()))
                .timeout(laneTimeout(record,
                        capabilityTimeout(record, "knowledge.read", Duration.ofSeconds(5))))
                .map(KnowledgeRecall::from)
                .onErrorReturn(KnowledgeRecall.unavailable());
    }

    private Mono<TurnWeatherContext> retrieveWeather(TurnRecord record, TurnRequest request) {
        WeatherConversationService.Intent intent = weatherConversation.classify(request.getMessage());
        if (intent == WeatherConversationService.Intent.NONE) {
            return weatherConversation.contextFor(request.getMessage());
        }
        emit(record, "tool.started", Map.of(
                "tool_name", "weather",
                "intent", intent.name().toLowerCase()));
        return weatherConversation.contextFor(request.getMessage())
                .doOnNext(context -> emit(record, "tool.completed", Map.of(
                        "tool_name", "weather",
                        "intent", intent.name().toLowerCase(),
                        "status", context.available() ? "ok" : "unavailable")));
    }

    private static List<String> appendContext(List<String> recent, List<String> additional) {
        if (additional == null || additional.isEmpty()) return recent;
        List<String> combined = new ArrayList<>(recent.size() + additional.size());
        combined.addAll(recent);
        combined.addAll(additional);
        return List.copyOf(combined);
    }

    private static String agentEventType(AgentLifecycleEvent event) {
        return switch (event.type()) {
            case SKILL_CREATED, STEP_CREATED -> "skill.started";
            case SKILL_STATUS_CHANGED, STEP_STATUS_CHANGED ->
                    lifecycleStatusType("skill", event.toStatus());
            case DURABLE_WAITING -> "agent.waiting";
            case TASK_STATUS_CHANGED ->
                    lifecycleStatusType("agent", event.toStatus());
            case CAPACITY_RELEASED -> "agent.completed";
            case TASK_CREATED, CAPACITY_ACQUIRED, SUBMIT_PERMIT_RELEASED,
                    REMOTE_SUBMITTED, RESULT_VALIDATED, IDEMPOTENT_REPLAY ->
                    lifecycleStatusType("agent", event.toStatus());
        };
    }

    private static String lifecycleStatusType(String prefix, String status) {
        String normalized = status == null
                ? "" : status.toUpperCase(java.util.Locale.ROOT);
        if (normalized.contains("WAITING")) return prefix + ".waiting";
        if ("SUCCEEDED".equals(normalized)) return prefix + ".completed";
        if ("FAILED".equals(normalized)
                || "CANCELLED".equals(normalized)
                || "TIMED_OUT".equals(normalized)) {
            return prefix + ".failed";
        }
        return prefix + ".started";
    }

    private static void putIfPresent(
            Map<String, Object> target, String key, Object value) {
        if (value != null) target.put(key, value);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private record KnowledgeRecall(
            String status,
            String provider,
            List<String> contextLines,
            com.meguri.core.retrieval.RetrievalBundle typedBundle) {
        private KnowledgeRecall {
            status = status == null || status.isBlank() ? "unavailable" : status;
            provider = provider == null || provider.isBlank() ? "none" : provider;
            contextLines = contextLines == null ? List.of() : List.copyOf(contextLines);
        }

        private static KnowledgeRecall from(
                com.meguri.core.retrieval.RetrievalBundle bundle) {
            RetrievalLaneResult lane = bundle.lanes().get(SourceType.KNOWLEDGE);
            String status = lane == null
                    ? "unavailable"
                    : lane.status() == RetrievalLaneResult.Status.OK
                            && lane.items().isEmpty()
                                    ? "empty"
                                    : lane.status().name().toLowerCase(java.util.Locale.ROOT);
            String provider = lane == null ? "none" : lane.provider();
            List<String> context = bundle.items().stream()
                    .filter(item -> item.sourceType() == SourceType.KNOWLEDGE)
                    .map(item -> item.citation().isEmpty()
                            ? item.content()
                            : item.content() + "\n[Knowledge source: "
                                    + item.citation().displayReferences() + "]")
                    .toList();
            return new KnowledgeRecall(status, provider, context, bundle);
        }

        private static KnowledgeRecall disabled() {
            return new KnowledgeRecall("disabled", "none", List.of(), null);
        }

        private static KnowledgeRecall notConfigured() {
            return new KnowledgeRecall("not_configured", "none", List.of(), null);
        }

        private static KnowledgeRecall unavailable() {
            return new KnowledgeRecall("unavailable", "none", List.of(), null);
        }
    }

    private Mono<WebSearchRecall> retrieveWeb(TurnRecord record, TurnRequest request) {
        if (!request.retrievalMode().permitsOpenRetrieval()) {
            return Mono.just(WebSearchRecall.disabled());
        }
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
        return Mono.delay(streamInterval)
                .then(Mono.defer(() -> {
                    if (record.isCancelRequested()) return cancelRecord(record, "client_requested");
                    // Structured-only providers emit one complete delta. They must
                    // not pretend to provide token streaming by slicing text locally.
                    emit(record, "text.delta", Map.of(
                            "delta", response.getReply(),
                            "index", 1,
                            "native", false));
                    return Mono.<Void>empty();
                }))
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
                    Mono<MemoryWriteResult> memoryWrite = !request.formalMemoryAllowed()
                            || trainingFeedback.hasComparison(record.getTurnId())
                            || LocalResourcePromptContext.hasAcceptedReference(request.attachments())
                            ? Mono.just(MemoryWriteResult.unavailable())
                            : materializeMemoryCandidates(request, response)
                                    .flatMap(candidates -> {
                                        LlmResponse writeResponse = withMemoryCandidates(response, candidates);
                                        return executeCapability(
                                                record,
                                                "memory.write",
                                                memoryWriteInput(request, record.getTurnId(), candidates),
                                                request.formalMemoryAllowed(),
                                                () -> memory.write(
                                                        request, writeResponse,
                                                        record.getTurnId(), record.getTraceId()));
                                    })
                                    .timeout(laneTimeout(record,
                                            capabilityTimeout(record, "memory.write", Duration.ofSeconds(5))));
                    return memoryWrite
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
                                if (record.isCancelRequested()) {
                                    return cancelRecord(record, "client_requested");
                                }
                                ChatResponse result = new ChatResponse(record.getTurnId(), request.getSessionId(), response,
                                        state, finalExpression, status, buildId);
                                if (!appendTerminal(record, () -> record.tryComplete(result),
                                        "turn.completed", Map.of("reply", response.getReply()))) {
                                    return record.isCancelRequested()
                                            ? cancelRecord(record, "client_requested")
                                            : Mono.empty();
                                }
                                return Mono.empty();
                            });
                }));
    }

    public Map<String, Object> submitTrainingFeedback(TrainingFeedbackRequest request) {
        TrainingFeedbackService.Submission submission = trainingFeedback.submit(request);
        TrainingFeedbackService.Candidate selected = submission.selected();
        if (selected != null) {
            String original = submission.comparison().candidates().getFirst().response().reply();
            sessions.replaceLastAssistant(
                    submission.comparison().request().userId(),
                    submission.comparison().request().clientId(),
                    submission.comparison().request().sessionId(),
                    original,
                    selected.response().reply());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "training_candidate_saved");
        result.put("feedback_id", submission.feedbackId());
        result.put("selected_candidate_id", selected == null ? null : selected.candidateId());
        result.put("feedback_file", trainingFeedback.feedbackFileDisplay());
        return result;
    }

    private Mono<Void> cancelRecord(TurnRecord record, String reason) {
        appendTerminal(record, record::tryCancel, "turn.cancelled", Map.of("reason", reason));
        return Mono.empty();
    }

    private Mono<Void> fail(TurnRecord record, Throwable error) {
        if (record.isCancelRequested()) return cancelRecord(record, "client_requested");
        String message = error == null || error.getMessage() == null ? "turn failed" : error.getMessage();
        appendTerminal(record, () -> record.tryFail(message), "turn.failed", Map.of("error", message));
        return Mono.empty();
    }

    private boolean appendTerminal(TurnRecord record, BooleanSupplier transition,
                                   String eventType, Map<String, Object> data) {
        LifecycleState before = LifecycleState.capture(record);
        if (!transition.getAsBoolean()) return false;
        try {
            emit(record, eventType, data);
        } catch (Throwable persistenceError) {
            before.restore(record);
            throw persistenceError;
        }
        record.completeDone();
        capabilityRuntime.release(record.getRuntimeCapabilities());
        return true;
    }

    private EventEnvelope emit(TurnRecord record, String type, Map<String, Object> data) {
        if (!TurnEventTypes.isSupported(type)) {
            throw new IllegalArgumentException("unsupported turn event type: " + type);
        }
        return journal.append(record, type, data == null ? Map.of() : data,
                new EventMetadata(record.getTraceId(), "meguri-core", Instant.now(), buildId));
    }

    private <T> Mono<T> executeCapability(
            TurnRecord record,
            String capabilityId,
            Map<String, Object> input,
            boolean approvalGranted,
            Supplier<Mono<T>> operation) {
        return Mono.defer(() -> {
            CompletableFuture<T> completion = new CompletableFuture<>();
            AtomicBoolean abandoned = new AtomicBoolean();
            Disposable task = Schedulers.boundedElastic().schedule(() -> {
                try {
                    T value = executeCapabilityBlocking(
                            record, capabilityId, input, approvalGranted, operation);
                    if (!abandoned.get()) completion.complete(value);
                } catch (Throwable error) {
                    if (!abandoned.get()) completion.completeExceptionally(error);
                }
            });
            return Mono.fromFuture(completion, true)
                    .doOnCancel(() -> {
                        abandoned.set(true);
                        task.dispose();
                    });
        });
    }

    private <T> T executeCapabilityBlocking(
            TurnRecord record,
            String capabilityId,
            Map<String, Object> input,
            boolean approvalGranted,
            Supplier<Mono<T>> operation) {
        String idempotencyKey = capabilityIdempotencyKey(
                record, capabilityId, input);
        String operationId = idempotencyKey == null
                ? null
                : isWriteCapability(capabilityId)
                        ? idempotencyKey
                        : record.getTurnId() + ":" + capabilityId + ":" + idempotencyKey;
        ToolProposal proposal = toolProposal(
                record, capabilityId, input, operationId, idempotencyKey, null);
        if (requiresApproval(capabilityId)) {
            ApprovalService.Approval approval =
                    capabilityRuntime.requestApproval(
                            record.getRuntimeCapabilities(), proposal);
            LinkedHashMap<String, Object> requiredEvent =
                    new LinkedHashMap<>();
            requiredEvent.put("approval_id", approval.approvalId());
            requiredEvent.put("capability_id", capabilityId);
            putIfPresent(requiredEvent, "operation_id", operationId);
            emit(record, "approval.required", Map.copyOf(requiredEvent));
            if (approvalGranted) {
                ApprovalService.Approval resolved = capabilityRuntime.resolveApproval(
                        approval.approvalId(),
                        ApprovalService.Decision.ACCEPT,
                        "agent.invoke".equals(capabilityId)
                                ? "explicit_agent_request"
                                : "adapter_permission");
                LinkedHashMap<String, Object> resolvedEvent =
                        new LinkedHashMap<>();
                resolvedEvent.put("approval_id", resolved.approvalId());
                resolvedEvent.put("capability_id", capabilityId);
                putIfPresent(resolvedEvent, "operation_id", operationId);
                resolvedEvent.put(
                        "decision",
                        resolved.decision().name().toLowerCase());
                emit(record, "approval.resolved", Map.copyOf(resolvedEvent));
            }
            proposal = toolProposal(
                    record, capabilityId, input, operationId, idempotencyKey,
                    approval.approvalId());
        }

        CapabilityPolicy.ExecutionRequest legacyExecution =
                new CapabilityPolicy.ExecutionRequest(
                        record.getTurnId(),
                        record.getRequest().getUserId(),
                        record.getTurnId() + ":" + capabilityId,
                        approvalGranted,
                        input,
                        legacySandbox(record, capabilityId),
                        record.getRequest().retrievalMode());
        AtomicReference<T> output = new AtomicReference<>();
        ToolProposal frozenProposal = proposal;
        CapabilityResult result = capabilityRuntime.execute(
                record.getRuntimeCapabilities(),
                frozenProposal,
                (ignoredInput, ignoredContext) -> {
                    T value = capabilityExecutor.execute(
                                    record.getCapabilitySnapshot(),
                                    capabilityId,
                                    legacyExecution,
                                    operation)
                            .block();
                    if (value == null) {
                        throw new IllegalStateException(
                                "capability operation completed without a value");
                    }
                    output.set(value);
                    return Map.of("executed", true);
                });
        if (result.status() != CapabilityResult.Status.SUCCESS) {
            throw new IllegalStateException(
                    "capability rejected: " + result.errorCode());
        }
        T value = output.get();
        if (value == null) {
            throw new IllegalStateException(
                    "capability completed without an operation result");
        }
        return value;
    }

    private ToolProposal toolProposal(
            TurnRecord record,
            String capabilityId,
            Map<String, Object> input,
            String operationId,
            String idempotencyKey,
            String approvalId) {
        TurnRequest request = record.getRequest();
        boolean networkAllowed = request.retrievalMode() == RetrievalMode.SLOW
                && ("web.read".equals(capabilityId)
                || "weather.read".equals(capabilityId)
                || "agent.invoke".equals(capabilityId));
        return new ToolProposal(
                record.getTurnId(),
                record.getTraceId(),
                request.tenantId(),
                request.getUserId(),
                request.getClientId(),
                capabilityId,
                input,
                Set.of(),
                operationId,
                idempotencyKey,
                approvalId,
                networkAllowed,
                1000);
    }

    private static boolean isWriteCapability(String capabilityId) {
        return "memory.write".equals(capabilityId);
    }

    private static String capabilityIdempotencyKey(
            TurnRecord record,
            String capabilityId,
            Map<String, Object> input) {
        if (isWriteCapability(capabilityId)) {
            return record.getTurnId() + ":" + capabilityId;
        }
        if (!"agent.invoke".equals(capabilityId)) return null;
        Object supplied = input.get("idempotency_key");
        if (supplied instanceof String value && !value.isBlank()) {
            return value.trim();
        }
        return "invoke";
    }

    private static boolean requiresApproval(String capabilityId) {
        return isWriteCapability(capabilityId)
                || "agent.invoke".equals(capabilityId);
    }

    private static CapabilityPolicy.SandboxBudget legacySandbox(
            TurnRecord record, String capabilityId) {
        if (!"agent.invoke".equals(capabilityId)) return null;
        Duration remaining = record.remaining();
        Duration wallTime = remaining.compareTo(Duration.ofSeconds(30)) < 0
                ? remaining : Duration.ofSeconds(30);
        return new CapabilityPolicy.SandboxBudget(
                wallTime,
                1,
                1,
                1000,
                Set.of("agent.invoke"),
                Set.of(),
                Set.of("configured-provider"),
                false);
    }

    private Mono<List<com.meguri.core.dto.MemoryCandidate>> materializeMemoryCandidates(
            TurnRequest request, LlmResponse response) {
        if (!response.getMemoryCandidates().isEmpty()) {
            return Mono.just(response.getMemoryCandidates());
        }
        return memory.extract(request).map(List::copyOf);
    }

    private static LlmResponse withMemoryCandidates(
            LlmResponse response, List<com.meguri.core.dto.MemoryCandidate> candidates) {
        return new LlmResponse(
                response.getReply(), response.getExpressionTag(), response.getExpressionIntensity(),
                response.getVoiceStyle(), candidates);
    }

    static Map<String, Object> memoryWriteInput(
            TurnRequest request,
            String turnId,
            List<com.meguri.core.dto.MemoryCandidate> candidates) {
        List<Map<String, Object>> values = candidates.stream()
                .map(candidate -> Map.<String, Object>of(
                        "type", candidate.getType().value(),
                        "summary", candidate.getSummary(),
                        "confidence", candidate.getConfidence(),
                        "sensitivity", candidate.getSensitivity().value(),
                        "source_scope", candidate.getSourceScope().value()))
                .toList();
        return Map.of(
                "turn_id", turnId,
                "user_id", request.getUserId(),
                "client_id", request.getClientId(),
                "session_id", request.getSessionId(),
                "candidate_count", values.size(),
                "candidates", values);
    }

    private static boolean hasAfter(List<EventEnvelope> available, long cursor) {
        return available.stream().anyMatch(event -> event.getSequence() > cursor);
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

    private ExposurePlanner.ExposureContext exposureContext(TurnRecord record) {
        TurnRequest request = record.getRequest();
        CapabilityDescriptor.Mode mode = switch (request.retrievalMode()) {
            case NONE, FAST -> CapabilityDescriptor.Mode.FAST;
            case SLOW -> CapabilityDescriptor.Mode.DEEP;
        };
        return new ExposurePlanner.ExposureContext(
                record.getTurnId(),
                request.tenantId(),
                request.getUserId(),
                request.getClientId(),
                Set.of(),
                mode,
                Set.of(),
                1,
                request.retrievalMode() == RetrievalMode.SLOW,
                request.formalMemoryAllowed()
                        ? CapabilityDescriptor.DataClassification.CONFIDENTIAL
                        : CapabilityDescriptor.DataClassification.INTERNAL);
    }

    private void registerRuntimeCapabilities() {
        registerRuntimeCapability(runtimeDescriptor(
                "lore.read",
                CapabilityDescriptor.Kind.READ_TOOL,
                CapabilityDescriptor.SideEffect.READ,
                CapabilityDescriptor.ApprovalRequirement.NONE,
                Duration.ofSeconds(8),
                Set.of(
                        CapabilityDescriptor.Mode.FAST,
                        CapabilityDescriptor.Mode.BALANCED,
                        CapabilityDescriptor.Mode.DEEP),
                CapabilityDescriptor.DataClassification.INTERNAL,
                false,
                false,
                objectSchema(
                        Map.of("query", CapabilityDescriptor.ValueType.STRING),
                        Set.of("query"))));
        registerRuntimeCapability(runtimeDescriptor(
                "memory.read",
                CapabilityDescriptor.Kind.READ_TOOL,
                CapabilityDescriptor.SideEffect.READ,
                CapabilityDescriptor.ApprovalRequirement.NONE,
                Duration.ofSeconds(5),
                Set.of(
                        CapabilityDescriptor.Mode.FAST,
                        CapabilityDescriptor.Mode.BALANCED,
                        CapabilityDescriptor.Mode.DEEP),
                CapabilityDescriptor.DataClassification.INTERNAL,
                false,
                false,
                objectSchema(
                        Map.of("query", CapabilityDescriptor.ValueType.STRING),
                        Set.of("query"))));
        registerRuntimeCapability(runtimeDescriptor(
                "knowledge.read",
                CapabilityDescriptor.Kind.READ_TOOL,
                CapabilityDescriptor.SideEffect.READ,
                CapabilityDescriptor.ApprovalRequirement.NONE,
                Duration.ofSeconds(5),
                Set.of(
                        CapabilityDescriptor.Mode.FAST,
                        CapabilityDescriptor.Mode.BALANCED,
                        CapabilityDescriptor.Mode.DEEP),
                CapabilityDescriptor.DataClassification.INTERNAL,
                false,
                false,
                objectSchema(
                        Map.of("query", CapabilityDescriptor.ValueType.STRING),
                        Set.of("query"))));
        registerRuntimeCapability(runtimeDescriptor(
                "web.read",
                CapabilityDescriptor.Kind.READ_TOOL,
                CapabilityDescriptor.SideEffect.EXTERNAL,
                CapabilityDescriptor.ApprovalRequirement.NONE,
                Duration.ofSeconds(8),
                Set.of(CapabilityDescriptor.Mode.DEEP),
                CapabilityDescriptor.DataClassification.PUBLIC,
                true,
                false,
                objectSchema(
                        Map.of("message", CapabilityDescriptor.ValueType.STRING),
                        Set.of("message"))));
        registerRuntimeCapability(runtimeDescriptor(
                "weather.read",
                CapabilityDescriptor.Kind.READ_TOOL,
                CapabilityDescriptor.SideEffect.EXTERNAL,
                CapabilityDescriptor.ApprovalRequirement.NONE,
                Duration.ofSeconds(5),
                Set.of(CapabilityDescriptor.Mode.DEEP),
                CapabilityDescriptor.DataClassification.PUBLIC,
                true,
                false,
                objectSchema(
                        Map.of("message", CapabilityDescriptor.ValueType.STRING),
                        Set.of("message"))));
        registerRuntimeCapability(runtimeDescriptor(
                "memory.write",
                CapabilityDescriptor.Kind.WRITE_TOOL,
                CapabilityDescriptor.SideEffect.WRITE,
                CapabilityDescriptor.ApprovalRequirement.RISK_BASED,
                Duration.ofSeconds(5),
                Set.of(
                        CapabilityDescriptor.Mode.FAST,
                        CapabilityDescriptor.Mode.BALANCED,
                        CapabilityDescriptor.Mode.DEEP),
                CapabilityDescriptor.DataClassification.CONFIDENTIAL,
                false,
                true,
                objectSchema(
                        Map.of(
                                "turn_id", CapabilityDescriptor.ValueType.STRING,
                                "user_id", CapabilityDescriptor.ValueType.STRING,
                                "client_id", CapabilityDescriptor.ValueType.STRING,
                                "session_id", CapabilityDescriptor.ValueType.STRING,
                                "candidate_count", CapabilityDescriptor.ValueType.NUMBER,
                                "candidates", CapabilityDescriptor.ValueType.ARRAY),
                        Set.of(
                                "turn_id", "user_id", "client_id", "session_id",
                                "candidate_count", "candidates"))));
        registerRuntimeCapability(runtimeDescriptor(
                "agent.invoke",
                CapabilityDescriptor.Kind.REMOTE_AGENT,
                CapabilityDescriptor.SideEffect.EXTERNAL,
                CapabilityDescriptor.ApprovalRequirement.RISK_BASED,
                Duration.ofSeconds(30),
                Set.of(CapabilityDescriptor.Mode.DEEP),
                CapabilityDescriptor.DataClassification.INTERNAL,
                true,
                false,
                new CapabilityDescriptor.Schema(Map.of(), Set.of(), true, Set.of())));
    }

    private void registerRuntimeCapability(CapabilityDescriptor descriptor) {
        capabilityRuntime.register(
                descriptor,
                (input, context) -> Map.of("authorized", true));
    }

    private static CapabilityDescriptor runtimeDescriptor(
            String id,
            CapabilityDescriptor.Kind kind,
            CapabilityDescriptor.SideEffect sideEffect,
            CapabilityDescriptor.ApprovalRequirement approval,
            Duration timeout,
            Set<CapabilityDescriptor.Mode> modes,
            CapabilityDescriptor.DataClassification classification,
            boolean network,
            boolean idempotentWrite,
            CapabilityDescriptor.Schema inputSchema) {
        return new CapabilityDescriptor(
                id,
                "1",
                kind,
                "meguri-core",
                inputSchema,
                new CapabilityDescriptor.Schema(Map.of(), Set.of(), true, Set.of()),
                Set.of(),
                sideEffect,
                approval,
                timeout,
                CapabilityDescriptor.RetryPolicy.none(),
                new CapabilityDescriptor.ConcurrencyPolicy(4),
                modes,
                classification,
                kind == CapabilityDescriptor.Kind.REMOTE_AGENT
                        ? CapabilityDescriptor.ResultTrust.UNTRUSTED_EXTERNAL
                        : CapabilityDescriptor.ResultTrust.TRUSTED_LOCAL,
                "turn-operation://" + id,
                CapabilityDescriptor.Health.HEALTHY,
                false,
                1,
                network
                        ? new CapabilityDescriptor.NetworkPolicy(
                                true, Set.of("configured-provider"))
                        : CapabilityDescriptor.NetworkPolicy.denied(),
                Set.of(),
                new CapabilityDescriptor.CostPolicy(0, 1000),
                CapabilityDescriptor.CachePolicy.disabled(),
                idempotentWrite
                        ? new CapabilityDescriptor.IdempotencyPolicy(true, true)
                        : CapabilityDescriptor.IdempotencyPolicy.none());
    }

    private static CapabilityDescriptor.Schema objectSchema(
            Map<String, CapabilityDescriptor.ValueType> properties,
            Set<String> required) {
        return new CapabilityDescriptor.Schema(
                properties, required, false, Set.of());
    }

    private CapabilityRegistry defaultCapabilities() {
        CapabilityRegistry registry = new CapabilityRegistry();
        registry.register(new CapabilityRegistry.Descriptor(
                "lore.read", CapabilityRegistry.Kind.READ_TOOL, CapabilityRegistry.Effect.READ,
                CapabilityRegistry.Approval.NONE, Duration.ofSeconds(8), 4, ragProviderName()));
        registry.register(new CapabilityRegistry.Descriptor(
                "memory.read", CapabilityRegistry.Kind.READ_TOOL, CapabilityRegistry.Effect.READ,
                CapabilityRegistry.Approval.NONE, Duration.ofSeconds(5), 4, memoryProviderName()));
        registry.register(new CapabilityRegistry.Descriptor(
                "knowledge.read", CapabilityRegistry.Kind.READ_TOOL, CapabilityRegistry.Effect.READ,
                CapabilityRegistry.Approval.NONE, Duration.ofSeconds(5), 4, "KnowledgeRuntime"));
        registry.register(new CapabilityRegistry.Descriptor(
                "memory.write", CapabilityRegistry.Kind.WRITE_TOOL, CapabilityRegistry.Effect.WRITE,
                CapabilityRegistry.Approval.RISK_BASED, Duration.ofSeconds(5), 2, memoryProviderName()));
        registry.register(new CapabilityRegistry.Descriptor(
                "web.read", CapabilityRegistry.Kind.READ_TOOL, CapabilityRegistry.Effect.EXTERNAL,
                CapabilityRegistry.Approval.NONE, Duration.ofSeconds(8), 2, webSearchProviderName()));
        registry.register(new CapabilityRegistry.Descriptor(
                "weather.read", CapabilityRegistry.Kind.READ_TOOL, CapabilityRegistry.Effect.EXTERNAL,
                CapabilityRegistry.Approval.NONE, Duration.ofSeconds(5), 2,
                weatherConversation.getClass().getSimpleName()));
        registry.register(new CapabilityRegistry.Descriptor(
                "agent.invoke", CapabilityRegistry.Kind.REMOTE_AGENT, CapabilityRegistry.Effect.EXTERNAL,
                CapabilityRegistry.Approval.RISK_BASED, Duration.ofSeconds(30), 2,
                "AgentRuntime"));
        return registry;
    }

    private CapabilityExecutor defaultCapabilityExecutor() {
        CapabilityExecutor executor = new CapabilityExecutor(
                new DefaultCapabilityPolicy(), new InMemoryEffectLedger());
        executor.registerSchema("lore.read", new CapabilityInputSchema(
                Map.of("query", CapabilityInputSchema.ValueKind.STRING), Set.of("query"), false));
        executor.registerSchema("memory.read", new CapabilityInputSchema(
                Map.of("query", CapabilityInputSchema.ValueKind.STRING), Set.of("query"), false));
        executor.registerSchema("knowledge.read", new CapabilityInputSchema(
                Map.of("query", CapabilityInputSchema.ValueKind.STRING), Set.of("query"), false));
        executor.registerSchema("web.read", new CapabilityInputSchema(
                Map.of("message", CapabilityInputSchema.ValueKind.STRING), Set.of("message"), false));
        executor.registerSchema("weather.read", new CapabilityInputSchema(
                Map.of("message", CapabilityInputSchema.ValueKind.STRING), Set.of("message"), false));
        executor.registerSchema("memory.write", new CapabilityInputSchema(
                Map.of(
                        "turn_id", CapabilityInputSchema.ValueKind.STRING,
                        "user_id", CapabilityInputSchema.ValueKind.STRING,
                        "client_id", CapabilityInputSchema.ValueKind.STRING,
                        "session_id", CapabilityInputSchema.ValueKind.STRING,
                        "candidate_count", CapabilityInputSchema.ValueKind.NUMBER,
                        "candidates", CapabilityInputSchema.ValueKind.ARRAY),
                Set.of("turn_id", "user_id", "client_id", "session_id", "candidate_count", "candidates"),
                false));
        executor.registerSchema("agent.invoke", new CapabilityInputSchema(
                Map.of(), Set.of(), true));
        return executor;
    }

    private record LifecycleState(
            TurnStatus status,
            TurnStage stage,
            ChatResponse result,
            String error) {

        private static LifecycleState capture(TurnRecord record) {
            return new LifecycleState(
                    record.getStatus(), record.getStage(), record.getResult(), record.getError());
        }

        private void restore(TurnRecord record) {
            record.restore(status, stage, record.getManifest(), result, error);
        }
    }
}

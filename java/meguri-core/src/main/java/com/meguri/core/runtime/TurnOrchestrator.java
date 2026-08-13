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
import com.meguri.core.execution.CurrentTurnSignals;
import com.meguri.core.execution.DeterministicExecutionModeResolver;
import com.meguri.core.execution.ExecutionBudget;
import com.meguri.core.execution.ExecutionBudgetPolicy;
import com.meguri.core.execution.ExecutionModeAvailability;
import com.meguri.core.execution.ExecutionModeDecision;
import com.meguri.core.execution.ExecutionModeResolver;
import com.meguri.core.execution.ExecutionModeResolutionContext;
import com.meguri.core.execution.TurnExecutionMode;
import com.meguri.core.agent.AgentLifecycleEvent;
import com.meguri.core.agent.AgentInvocation;
import com.meguri.core.agent.AgentResult;
import com.meguri.core.agent.AgentRuntime;
import com.meguri.core.agent.AgentRuntimeFactory;
import com.meguri.core.agent.AgentTaskContext;
import com.meguri.core.agent.CancellationToken;
import com.meguri.core.agent.InvokeAgentProposal;
import com.meguri.core.agent.StepDispatcher;
import com.meguri.core.capability.ApprovalService;
import com.meguri.core.capability.CapabilityDescriptor;
import com.meguri.core.capability.CapabilityResult;
import com.meguri.core.capability.CapabilityRuntimeFacade;
import com.meguri.core.capability.ExposurePlanner;
import com.meguri.core.capability.McpContentResolver;
import com.meguri.core.capability.ToolProposal;
import com.meguri.core.harness.EventCursor;
import com.meguri.core.harness.HarnessControlPlane;
import com.meguri.core.harness.HarnessManifest;
import com.meguri.core.harness.SessionReplayWindow;
import com.meguri.core.harness.SessionSnapshot;
import com.meguri.core.harness.TurnCommand;
import com.meguri.core.harness.TurnRuntime;
import com.meguri.core.harness.TurnSnapshot;
import com.meguri.core.harness.capability.EffectLedger;
import com.meguri.core.harness.persona.DeterministicPersonaRuntime;
import com.meguri.core.harness.persona.PersonaRuntime;
import com.meguri.core.harness.retrieval.RetrievalMode;
import com.meguri.core.llm.LlmProvider;
import com.meguri.core.llm.LlmProviderFactory;
import com.meguri.core.llm.NativeTextDeltaAggregator;
import com.meguri.core.llm.UserVisibleReplyStream;
import com.meguri.core.llm.AgentPlanningRequest;
import com.meguri.core.llm.AgentPlannerException;
import com.meguri.core.llm.ProviderRequest;
import com.meguri.core.context.CompanionContextRuntime;
import com.meguri.core.context.ContextBuildRequest;
import com.meguri.core.context.ContextBundle;
import com.meguri.core.context.ContextProfile;
import com.meguri.core.context.ContextRuntimePersistence;
import com.meguri.core.context.InMemoryContextRuntimePersistence;
import com.meguri.core.memory.job.InMemoryPostReplyMemoryJobStore;
import com.meguri.core.memory.job.PostReplyMemoryJob;
import com.meguri.core.memory.job.PostReplyMemoryJobEnqueuer;
import com.meguri.core.observability.TurnLatencyMissingReason;
import com.meguri.core.observability.TurnLatencyPoint;
import com.meguri.core.observability.TurnLatencyTraceMetadata;
import com.meguri.core.observability.TurnLatencyTraceRecorder;
import com.meguri.core.persona.PersonaRuntimeDefaults;
import com.meguri.core.persona.PersonaRuntimeFacade;
import com.meguri.core.persona.presentation.PresentationIntent;
import com.meguri.core.persona.presentation.PresentationResolver;
import com.meguri.core.persona.prompt.PromptPolicyComposer;
import com.meguri.core.persona.runtime.EffectivePersonaState;
import com.meguri.core.retrieval.FrozenKnowledgeSnapshot;
import com.meguri.core.retrieval.UnifiedRetrievalFacade;
import com.meguri.core.rag.CanonicalRagRetriever;
import com.meguri.core.rag.MockRagProvider;
import com.meguri.core.rag.RagProvider;
import com.meguri.core.resources.LocalResourcePromptContext;
import com.meguri.core.artifact.ModelGeneratedArtifactResolver;
import com.meguri.core.document.DocumentEditPreviewStore;
import com.meguri.core.document.ModelDocumentEditProposalResolver;
import com.meguri.core.memory.MemoryGateway;
import com.meguri.core.retrieval.KnowledgeTurnRetrievalRuntime;
import com.meguri.core.react.ActionProposalValidator;
import com.meguri.core.react.CapabilityRuntimeReactActionExecutor;
import com.meguri.core.react.DefaultObservationNormalizer;
import com.meguri.core.react.InMemoryReactTraceRepository;
import com.meguri.core.react.LimitedReActRuntime;
import com.meguri.core.react.ReactInvocationScope;
import com.meguri.core.react.ReactPlanner;
import com.meguri.core.react.ReactSkillCandidate;
import com.meguri.core.react.ReactRunRequest;
import com.meguri.core.react.TerminationPolicy;
import com.meguri.core.training.TrainingFeedbackRequest;
import com.meguri.core.training.TrainingFeedbackService;
import com.meguri.core.websearch.NoopWebSearchGateway;
import com.meguri.core.websearch.WebSearchGateway;
import com.meguri.core.weather.WeatherConversationService;
import com.meguri.core.weather.WeatherConversationService.TurnWeatherContext;
import com.meguri.core.weather.WeatherConversationService.TurnResolution;
import com.meguri.core.skill.FrozenSkillSnapshot;
import com.meguri.core.skill.SkillDisclosureService;
import com.meguri.core.skill.SkillSelectionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.BufferOverflowStrategy;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.time.Clock;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;
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
    private volatile SessionContextStore sessions;
    private final ObjectMapper objectMapper;
    private final ModelDocumentEditProposalResolver documentEditProposals;
    private final String buildId;
    private final Duration streamInterval;
    private final MemoryGateway memory;
    private final WebSearchGateway webSearch;
    private final TrainingFeedbackService trainingFeedback;
    private final WeatherConversationService weatherConversation;
    private final TurnJournal journal;
    private final PersonaRuntime personaRuntime;
    private final CapabilityRuntimeFacade capabilityRuntime;
    private volatile AgentRuntime agentRuntime;
    private volatile AgentRuntimeFactory.Config agentRuntimeConfig;
    private volatile CanonicalTurnPipeline canonicalPipeline;
    private volatile PresentationResolver presentationResolver;
    private volatile PostReplyMemoryJobEnqueuer postReplyMemoryJobs;
    private final Clock clock = Clock.systemUTC();
    private final ExecutionModeResolver executionModeResolver;
    private volatile boolean executionModeEnabled;
    private volatile boolean fastPathEnabled;
    private volatile boolean limitedReactEnabled;
    private volatile Duration agentPlannerDeadline = Duration.ofSeconds(5);
    private volatile ReactPlanner limitedReactPlanner;
    private volatile SkillSelectionService skillSelection;
    private volatile SkillDisclosureService skillDisclosure;
    private final InMemoryReactTraceRepository limitedReactTraces =
            new InMemoryReactTraceRepository();
    private final String executionOwnerId = "meguri-core-" + UUID.randomUUID();
    private static final Duration EXECUTION_LEASE = Duration.ofSeconds(30);

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
        this.documentEditProposals = new ModelDocumentEditProposalResolver(
                this.objectMapper, DocumentEditPreviewStore.shared());
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
        this.capabilityRuntime = capabilityRuntime == null
                ? new CapabilityRuntimeFacade(32) : capabilityRuntime;
        registerRuntimeCapabilities();
        ContextRuntimePersistence contextRuntimePersistence =
                contextPersistence instanceof ContextRuntimePersistence durable
                        ? durable : new InMemoryContextRuntimePersistence();
        this.canonicalPipeline = new CanonicalTurnPipeline(
                PersonaRuntimeDefaults.facade(Clock.systemUTC()),
                UnifiedRetrievalFacade.compatibility(this.rag, this.memory, this.webSearch),
                new CompanionContextRuntime(this.sessions, contextRuntimePersistence, this.llm.tokenizer()),
                new PromptPolicyComposer(), defaultContextProfile(this.llm),
                this.capabilityRuntime);
        this.presentationResolver = new PresentationResolver();
        this.postReplyMemoryJobs = new PostReplyMemoryJobEnqueuer(
                new InMemoryPostReplyMemoryJobStore(), this.objectMapper, Clock.systemUTC());
        this.executionModeResolver = new DeterministicExecutionModeResolver(
                ExecutionBudgetPolicy.safeBootstrapDefaults(clock),
                Duration.ofMillis(250), Duration.ofSeconds(1), clock);
    }

    @Autowired
    public void configurePerformanceFeatures(
            @Value("${meguri.performance.execution-mode-enabled:false}")
            boolean executionModeEnabled,
            @Value("${meguri.performance.fast-path-enabled:false}")
            boolean fastPathEnabled,
            @Value("${meguri.performance.limited-react-enabled:false}")
            boolean limitedReactEnabled,
            @Value("${meguri.agent.planner.deadline-ms:5000}")
            long agentPlannerDeadlineMs) {
        if (agentPlannerDeadlineMs <= 0) {
            throw new IllegalArgumentException("meguri.agent.planner.deadline-ms must be positive");
        }
        this.executionModeEnabled = executionModeEnabled;
        this.fastPathEnabled = executionModeEnabled && fastPathEnabled;
        this.limitedReactEnabled = executionModeEnabled && limitedReactEnabled;
        this.agentPlannerDeadline = Duration.ofMillis(agentPlannerDeadlineMs);
    }

    /** Compatibility hook for tests and embedders that predate the planner deadline setting. */
    public void configurePerformanceFeatures(
            boolean executionModeEnabled,
            boolean fastPathEnabled,
            boolean limitedReactEnabled) {
        configurePerformanceFeatures(executionModeEnabled, fastPathEnabled,
                limitedReactEnabled, 5_000L);
    }

    @Autowired(required = false)
    public void configureLimitedReactPlanner(ReactPlanner planner) {
        this.limitedReactPlanner = Objects.requireNonNull(planner, "planner");
    }

    @Autowired(required = false)
    public void configureExternalSkills(
            SkillSelectionService selection,
            SkillDisclosureService disclosure) {
        this.skillSelection = Objects.requireNonNull(selection, "selection");
        this.skillDisclosure = Objects.requireNonNull(disclosure, "disclosure");
        journal.turns().values().stream()
                .filter(record -> !record.isTerminal())
                .map(TurnRecord::getSkillSnapshot)
                .filter(Objects::nonNull)
                .forEach(disclosure::freeze);
    }

    @Autowired(required = false)
    public void configureCanonicalPipeline(
            SessionContextStore sessionContextStore,
            PersonaRuntimeFacade personaRuntimeFacade,
            UnifiedRetrievalFacade retrievalFacade,
            CompanionContextRuntime contextRuntime,
            PromptPolicyComposer promptPolicyComposer,
            ContextProfile contextProfile,
            PresentationResolver presentation,
            PostReplyMemoryJobEnqueuer memoryJobEnqueuer) {
        this.sessions = Objects.requireNonNull(sessionContextStore, "sessionContextStore");
        this.canonicalPipeline = new CanonicalTurnPipeline(
                personaRuntimeFacade, retrievalFacade, contextRuntime,
                promptPolicyComposer, contextProfile, capabilityRuntime);
        this.presentationResolver = Objects.requireNonNull(presentation, "presentation");
        this.postReplyMemoryJobs = Objects.requireNonNull(memoryJobEnqueuer, "memoryJobEnqueuer");
    }

    /**
     * Explicit compatibility hook for tests and embedders that only provide the
     * legacy Knowledge runtime. Spring uses the canonical UnifiedRetrievalFacade
     * injected by {@link #configureCanonicalPipeline} so authorization cannot be
     * replaced by a second, order-dependent autowiring pass.
     */
    public void configureKnowledgeRetrieval(KnowledgeTurnRetrievalRuntime runtime) {
        configureUnifiedRetrieval(Objects.requireNonNull(runtime, "runtime")
                .unifiedFacade(rag, memory, webSearch));
    }

    public void configureUnifiedRetrieval(UnifiedRetrievalFacade runtime) {
        this.canonicalPipeline = canonicalPipeline.withRetrieval(
                Objects.requireNonNull(runtime, "runtime"));
    }

    @Autowired(required = false)
    public void configureMcpContentResolver(McpContentResolver resolver) {
        this.canonicalPipeline = canonicalPipeline.withMcpContentResolver(
                Objects.requireNonNull(resolver, "resolver"));
    }

    @Autowired(required = false)
    public void configureAgentRuntime(
            AgentRuntime runtime, AgentRuntimeFactory.Config config) {
        this.agentRuntime = Objects.requireNonNull(runtime, "runtime");
        this.agentRuntimeConfig = Objects.requireNonNull(config, "config");
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
        return capabilityRuntime.auditEvents().stream()
                .filter(event -> Objects.equals(turnId, event.turnId()))
                .filter(event -> Set.of("COMPLETED", "IDEMPOTENT_REPLAY", "CANCELLED")
                        .contains(event.phase()))
                .filter(this::hasObservableEffect)
                .map(this::compatibilityReceipt)
                .sorted(java.util.Comparator.comparing(EffectLedger.Receipt::startedAt))
                .toList();
    }

    private boolean hasObservableEffect(
            com.meguri.core.capability.CapabilityAudit.Event event) {
        return capabilityRuntime.availableDescriptors().stream()
                .filter(candidate -> candidate.id().equals(event.capabilityId()))
                .filter(candidate -> event.capabilityVersion() == null
                        || candidate.version().equals(event.capabilityVersion()))
                .anyMatch(candidate -> candidate.sideEffect()
                        != CapabilityDescriptor.SideEffect.NONE);
    }

    private EffectLedger.Receipt compatibilityReceipt(
            com.meguri.core.capability.CapabilityAudit.Event event) {
        CapabilityDescriptor descriptor = capabilityRuntime.availableDescriptors().stream()
                .filter(candidate -> candidate.id().equals(event.capabilityId()))
                .filter(candidate -> event.capabilityVersion() == null
                        || candidate.version().equals(event.capabilityVersion()))
                .findFirst()
                .orElse(null);
        com.meguri.core.harness.capability.CapabilityRegistry.Effect effect = descriptor == null
                ? com.meguri.core.harness.capability.CapabilityRegistry.Effect.EXTERNAL
                : switch (descriptor.sideEffect()) {
                    case NONE, READ -> com.meguri.core.harness.capability.CapabilityRegistry.Effect.READ;
                    case WRITE -> com.meguri.core.harness.capability.CapabilityRegistry.Effect.WRITE;
                    case EXTERNAL -> com.meguri.core.harness.capability.CapabilityRegistry.Effect.EXTERNAL;
                };
        EffectLedger.Status status = "SUCCESS".equals(event.status())
                ? EffectLedger.Status.COMPLETED : EffectLedger.Status.FAILED;
        Instant finishedAt = event.occurredAt();
        Instant startedAt = finishedAt.minusMillis(Math.max(0L, event.durationMs()));
        return new EffectLedger.Receipt(
                event.eventId(), event.turnId(), event.userId(), event.capabilityId(),
                event.capabilityVersion() == null ? "unknown" : event.capabilityVersion(),
                event.idempotencyKey(), effect,
                event.approvalDecision() == ApprovalService.Decision.ACCEPT,
                event.requestDigest(), event.resultDigest(), status, event.errorCode(),
                startedAt, finishedAt);
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
        return executeAgentCapabilityInternal(
                turnId, tenantId, userId, clientId, sessionId, input,
                true, this::decodeScalarCapabilityReplay,
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
        return executeAgentCapabilityInternal(
                turnId, tenantId, userId, clientId, sessionId,
                input, true, this::decodeScalarCapabilityReplay, operation);
    }

    public <T> Mono<T> executeAgentCapability(
            String turnId,
            String tenantId,
            String userId,
            String clientId,
            String sessionId,
            Map<String, Object> input,
            Class<T> resultType,
            Function<AgentExecutionScope, Mono<T>> operation) {
        Objects.requireNonNull(resultType, "resultType");
        Objects.requireNonNull(operation, "operation");
        return executeAgentCapabilityInternal(
                turnId, tenantId, userId, clientId, sessionId,
                input, true,
                result -> objectMapper.convertValue(result.data(), resultType),
                operation);
    }

    private <T> Mono<T> executeAgentCapabilityInternal(
            String turnId,
            String tenantId,
            String userId,
            String clientId,
            String sessionId,
            Map<String, Object> input,
            boolean approvalGranted,
            Function<CapabilityResult, T> replayDecoder,
            Function<AgentExecutionScope, Mono<T>> operation) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(replayDecoder, "replayDecoder");
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
                    record, "agent.invoke", Map.copyOf(input), approvalGranted,
                    replayDecoder,
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

    /** Creates a new Turn for a failed/interrupted predecessor; the old Turn is immutable. */
    public synchronized TurnRecord retry(TurnRequest request, String idempotencyKey,
                                         String retryOfTurnId) {
        Objects.requireNonNull(request, "request");
        TurnRecord predecessor = journal.turn(retryOfTurnId);
        if (predecessor == null || !predecessor.isTerminal()
                || predecessor.getStatus() != TurnStatus.FAILED) {
            throw new IllegalArgumentException("retry_of_turn_id must reference a failed Turn");
        }
        TurnJournal.Acceptance acceptance = journal.acceptRetry(
                request, idempotencyKey, Instant.now().plus(TurnCommand.DEFAULT_DEADLINE),
                predecessor.getTurnId());
        if (acceptance.created()) {
            prepareRecord(acceptance.record());
            schedule(acceptance.record());
        }
        return acceptance.record();
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
            journal.requestCancellation(record);
            Disposable active = running.remove(turnId);
            if (active != null) {
                cancelRecord(record, "client_requested");
                active.dispose();
            }
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
            if (skillDisclosure != null) skillDisclosure.release(record.getTurnId());
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
        ExecutionModeDecision executionDecision = executionModeEnabled
                ? resolveExecutionMode(record) : null;
        if (executionDecision != null) {
            record.freezeExecutionModeDecision(executionDecision);
        }
        String traceExecutionMode = executionDecision == null
                ? (request.retrievalMode() == RetrievalMode.SLOW ? "THINK" : "FAST")
                : executionDecision.mode().name();
        TurnLatencyTraceRecorder latency = new TurnLatencyTraceRecorder(
                new TurnLatencyTraceMetadata(
                        record.getTraceId(), record.getTurnId(), buildId,
                        System.getenv("MEGURI_GIT_COMMIT"),
                        System.getenv("MEGURI_IMAGE_DIGEST"),
                        EventEnvelope.CURRENT_PROTOCOL_VERSION,
                        "runtime", llm.modelId(), llm.providerName(),
                        traceExecutionMode, observabilityRetrievalMode(record),
                        request.getClientId()));
        latency.markAt(TurnLatencyPoint.TURN_RECEIVED, record.getAcceptedAt());
        latency.mark(TurnLatencyPoint.PERSONA_STARTED);
        record.freezeLatencyTraceRecorder(latency);
        sessions.appendNode(
                request.getUserId(), request.getClientId(), request.getSessionId(),
                userMessageId(record), null,
                new SessionContextStore.Message("user", request.getMessage()));
        RuntimeState temporalState = stateMachine.stateFor(request);
        EffectivePersonaState effectivePersona = canonicalPipeline.resolvePersona(
                record, temporalState);
        RuntimeState state = canonicalPipeline.applyPersona(temporalState, effectivePersona);
        PersonaRuntime.PersonaSnapshot persona = personaSnapshot(state, effectivePersona);
        latency.mark(TurnLatencyPoint.PERSONA_READY);
        boolean explicitWeather = request.retrievalMode() != RetrievalMode.NONE
                && weatherResolution(record).intent() != WeatherConversationService.Intent.NONE;
        boolean omitToolSchemas = fastPathEnabled
                && executionDecision != null
                && executionDecision.mode() != TurnExecutionMode.AGENT
                && !explicitWeather;
        latency.mark(TurnLatencyPoint.CAPABILITY_EXPOSURE_STARTED);
        CapabilityRuntimeFacade.TurnCapabilities runtimeCapabilities = omitToolSchemas
                ? capabilityRuntime.freezeEmpty(record.getTurnId())
                : capabilityRuntime.freeze(exposureContext(record));
        if (skillSelection != null && skillDisclosure != null && executionDecision != null) {
            boolean skillCapabilitiesExposed = runtimeCapabilities.exposes("meguri.skill.search")
                    && runtimeCapabilities.exposes("meguri.skill.view");
            FrozenSkillSnapshot skills = skillSelection.freeze(
                    record.getTurnId(), runtimeCapabilities.snapshotId(), request.getMessage(),
                    executionDecision.mode() == TurnExecutionMode.AGENT && skillCapabilitiesExposed);
            record.freezeSkillSnapshot(skills);
            skillDisclosure.freeze(skills);
        }
        latency.mark(TurnLatencyPoint.CAPABILITY_EXPOSURE_READY);
        FrozenKnowledgeSnapshot knowledgeSnapshot;
        try {
            knowledgeSnapshot = canonicalPipeline.freezeKnowledge(request, frozenAt);
        } catch (RuntimeException unavailable) {
            knowledgeSnapshot = FrozenKnowledgeSnapshot.empty(frozenAt);
        }
        record.freezePersonaSnapshot(persona);
        record.freezeEffectivePersonaState(effectivePersona);
        record.freezeRuntimeCapabilities(runtimeCapabilities);
        long knowledgeRevision = knowledgeSnapshot.versions().stream()
                .map(FrozenKnowledgeSnapshot.VersionRef::publishedAt)
                .mapToLong(Instant::toEpochMilli).max().orElse(0L);
        record.freezeManifest(new HarnessManifest(
                EventEnvelope.CURRENT_PROTOCOL_VERSION,
                buildId,
                "ctx-" + sessions.revision(request.getUserId(), request.getClientId(), request.getSessionId()),
                persona.revision(),
                runtimeCapabilities.snapshotId(),
                runtimeCapabilities.exposed().stream()
                        .map(CapabilityRuntimeFacade.CapabilityRef::id)
                        .toList(),
                knowledgeSnapshot.snapshotId(),
                knowledgeRevision,
                Long.toString(effectivePersona.relationship().version()),
                effectivePersona.scene() == null
                        ? "scene:none" : Long.toString(effectivePersona.scene().version()),
                effectivePersona.policyRevision(),
                effectivePersona.resolutionTraceId(),
                record.getTraceId(),
                llm.providerName(),
                llm.modelId(),
                frozenAt));
        journal.persist(record);
        latency.mark(TurnLatencyPoint.TURN_PERSISTED);
    }

    private ExecutionModeDecision resolveExecutionMode(TurnRecord record) {
        TurnRequest request = record.getRequest();
        String normalized = request.getMessage() == null
                ? "" : request.getMessage().trim().toLowerCase(java.util.Locale.ROOT);
        java.util.LinkedHashSet<String> intents = new java.util.LinkedHashSet<>();
        if (isSimpleGreeting(normalized)) intents.add("greeting");
        if (containsAny(normalized, "architecture", "架构", "设计", "compare", "比较", "对比",
                "analysis", "分析", "reason", "推理", "计划")) {
            intents.add("architecture");
        }
        boolean explicitAgent = request.agentProposal() != null
                || containsAny(normalized, "agent", "仓库分析", "repository analysis", "多步骤执行");
        if (explicitAgent) intents.add("repository_analysis");
        boolean externalObservation = request.retrievalMode() != RetrievalMode.NONE
                && weatherResolution(record).intent() != WeatherConversationService.Intent.NONE;
        if (externalObservation) intents.add("tool_required");

        // Intent may request AGENT, but it must never manufacture capability
        // authority. Only authenticated scopes (or the existing explicit
        // weather lane) establish local read-tool availability.
        boolean toolCapable = externalObservation
                || !request.authorizedCapabilityScopes().isEmpty();
        ExecutionBudget parentBudget = new ExecutionBudget(
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
                Integer.MAX_VALUE, Integer.MAX_VALUE,
                Long.MAX_VALUE, Long.MAX_VALUE, record.getDeadlineAt());
        CurrentTurnSignals signals = new CurrentTurnSignals(
                request.requestedExecutionMode(), null, null, List.copyOf(intents),
                externalObservation, explicitAgent, toolCapable, false,
                record.remaining().toMillis());
        boolean limitedReactAvailable = limitedReactEnabled && limitedReactPlanner != null;
        boolean plannerAvailable = agentRuntime != null && agentRuntimeConfig != null;
        ExecutionModeAvailability availability = new ExecutionModeAvailability(
                true,
                limitedReactAvailable || plannerAvailable,
                toolCapable,
                false);
        return executionModeResolver.resolve(signals,
                new ExecutionModeResolutionContext(null, parentBudget, availability));
    }

    private static boolean isSimpleGreeting(String message) {
        if (message == null) return true;
        String compact = message.replaceAll("[\\p{Punct}\\s。！？～~]+", "");
        return Set.of("", "hi", "hello", "hey", "thanks", "thankyou", "goodmorning",
                "goodnight", "你好", "您好", "嗨", "哈喽", "谢谢", "早安", "晚安",
                "おはよう", "こんにちは", "こんばんは", "ありがとう", "おやすみ")
                .contains(compact);
    }

    private static boolean containsAny(String value, String... candidates) {
        if (value == null || value.isBlank()) return false;
        for (String candidate : candidates) {
            if (value.contains(candidate)) return true;
        }
        return false;
    }

    private static String observabilityRetrievalMode(TurnRecord record) {
        TurnRequest request = record.getRequest();
        ExecutionModeDecision decision = record.getExecutionModeDecision();
        if (decision == null || decision.mode() != TurnExecutionMode.FAST) {
            return request.retrievalMode().name();
        }
        com.meguri.core.retrieval.RetrievalMode requested = switch (request.retrievalMode()) {
            case NONE -> com.meguri.core.retrieval.RetrievalMode.NONE;
            case FAST, SLOW -> com.meguri.core.retrieval.RetrievalMode.FAST;
        };
        return new com.meguri.core.retrieval.RetrievalGate()
                .classify(request.getMessage(), requested).name();
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
        if (!journal.claimExecution(record, executionOwnerId, EXECUTION_LEASE)) {
            return Mono.error(new IllegalStateException("turn execution lease is owned by another runtime"));
        }
        Mono<Void> execution = runRecord(record)
                .timeout(record.remaining())
                .onErrorResume(TimeoutException.class,
                        error -> fail(record, "TURN_DEADLINE_EXCEEDED", "turn deadline exceeded"));
        Mono<Void> cancellation = Flux.interval(Duration.ofMillis(250))
                .filter(ignored -> journal.refreshCancellation(record))
                .next()
                .flatMap(ignored -> cancelRecord(record, "client_requested"));
        Mono<Void> leaseGuard = Flux.interval(Duration.ofSeconds(10))
                .concatMap(ignored -> Mono.fromCallable(() -> journal.heartbeatExecution(
                        record, executionOwnerId, EXECUTION_LEASE)))
                .filter(owned -> !owned)
                .next()
                .flatMap(ignored -> journal.refreshCancellation(record)
                        ? cancelRecord(record, "client_requested")
                        : Mono.error(new IllegalStateException("turn execution lease was lost")));
        return Mono.firstWithSignal(execution, cancellation, leaseGuard)
                .doFinally(ignored -> {
                    journal.releaseExecution(record, executionOwnerId);
                });
    }

    private Mono<Void> runRecord(TurnRecord record) {
        TurnRequest request = record.getRequest();
        record.setStatus(TurnStatus.RUNNING);
        record.transitionTo(TurnStage.PLANNING);
        RuntimeState state;
        try {
            state = record.getPersonaSnapshot().state();
            LinkedHashMap<String, Object> started = new LinkedHashMap<>();
            started.put("stage", record.getStage().wireValue());
            started.put("runtime_state", beanMap(state));
            started.put("manifest", beanMap(record.getManifest()));
            started.put("persona_provenance", beanMap(record.getPersonaSnapshot().provenance()));
            ExecutionModeDecision executionDecision = record.getExecutionModeDecision();
            if (executionDecision != null) {
                started.put("execution_mode", executionDecision.mode().name());
                started.put("execution_decision", beanMap(executionDecision));
            }
            emit(record, "turn.started", Map.copyOf(started));
        } catch (Throwable error) {
            return fail(record, error);
        }

        WeatherConversationService.Intent weatherIntent = weatherResolution(record).intent();
        if (weatherIntent == WeatherConversationService.Intent.WEATHER
                && request.retrievalMode() != RetrievalMode.NONE) {
            return runDirectWeatherRecord(record, state);
        }

        transition(record, TurnStage.RETRIEVING);
        return Mono.fromCallable(() -> canonicalPipeline.prepare(
                        record, state, record.getEffectivePersonaState(),
                        record.getRuntimeCapabilities(), attachmentContext(request)))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(initial -> canonicalAgentContext(record, initial)
                        .flatMap(agentBlocks -> Mono.fromCallable(() -> {
                            CanonicalTurnPipeline.Prepared prepared =
                                    agentBlocks.isEmpty()
                                            ? initial
                                            : canonicalPipeline.augment(
                                                    record, state,
                                                    record.getEffectivePersonaState(),
                                                    record.getRuntimeCapabilities(),
                                                    initial, agentBlocks);
                            canonicalPipeline.freeze(record, prepared);
                            return prepared;
                        }).subscribeOn(Schedulers.boundedElastic())))
                .flatMap(prepared -> {
                    Map<String, Object> retrievalTrace = new LinkedHashMap<>();
                    retrievalTrace.put("trace_id", prepared.retrieval().traceId());
                    retrievalTrace.put("retrieval_mode",
                            prepared.retrieval().plan().mode().name().toLowerCase(
                                    java.util.Locale.ROOT));
                    if (record.getExecutionModeDecision() != null) {
                        retrievalTrace.put("execution_mode",
                                record.getExecutionModeDecision().mode().name());
                    }
                    retrievalTrace.put("lanes", prepared.retrieval().lanes().entrySet().stream()
                            .sorted(Map.Entry.comparingByKey())
                            .map(entry -> Map.<String, Object>of(
                                    "lane", entry.getKey().name().toLowerCase(java.util.Locale.ROOT),
                                    "status", entry.getValue().status().name().toLowerCase(java.util.Locale.ROOT),
                                    "provider", entry.getValue().provider(),
                                    "result_count", entry.getValue().items().size(),
                                    "degradations", entry.getValue().degradations()))
                            .toList());
                    retrievalTrace.put("degradations", prepared.retrieval().degradations());
                    retrievalTrace.put("graph_enabled", prepared.retrieval().plan().graphEnabled());
                    retrievalTrace.put("knowledge_trace", Map.of(
                            "trace_id", prepared.retrieval().traceId(),
                            "snapshot_id", record.getManifest().knowledgeSnapshotId(),
                            "revision", record.getManifest().knowledgeRevision(),
                            "result_count", prepared.retrieval().items().stream()
                                    .filter(item -> item.sourceType()
                                            == com.meguri.core.retrieval.SourceType.KNOWLEDGE)
                                    .count()));
                    retrievalTrace.put("context_trace_id", prepared.context().traceId());
                    retrievalTrace.put("context_build_revision",
                            prepared.context().bundle().buildRevision());
                    retrievalTrace.put("provider_prompt_digest",
                            prepared.providerRequest().canonicalPromptDigest());
                    retrievalTrace.put("context_budget",
                            beanMap(prepared.context().bundle().budget()));
                    retrievalTrace.put("mcp_content", prepared.mcpContent().stream()
                            .map(item -> Map.<String, Object>of(
                                    "kind", item.kind().name().toLowerCase(
                                            java.util.Locale.ROOT),
                                    "source_id", item.sourceId(),
                                    "identifier", item.identifier(),
                                    "status", item.succeeded() ? "succeeded" : "failed",
                                    "item_count", item.itemCount(),
                                    "failure_code", item.failureCode() == null
                                            ? "" : item.failureCode()))
                            .toList());
                    emit(record, "retrieval.completed", Map.copyOf(retrievalTrace));
                    for (CanonicalTurnPipeline.PromptSkillExecution skill
                            : prepared.promptSkills()) {
                        LinkedHashMap<String, Object> skillEvent = new LinkedHashMap<>();
                        skillEvent.put("capability_id", skill.capabilityId());
                        skillEvent.put("capability_version", skill.capabilityVersion());
                        skillEvent.put("token_estimate", skill.tokenEstimate());
                        putIfPresent(skillEvent, "failure_code", skill.failureCode());
                        emit(record, skill.succeeded() ? "skill.completed" : "skill.failed",
                                Map.copyOf(skillEvent));
                    }

                    WeatherConversationService.Intent intent = weatherResolution(record).intent();
                    Mono<TurnWeatherContext> weather =
                            intent != WeatherConversationService.Intent.NONE
                                    && request.retrievalMode() != RetrievalMode.NONE
                            ? executeCapability(
                                    record,
                                    "weather.read",
                                    Map.of("message", request.getMessage()),
                                    false,
                                    result -> objectMapper.convertValue(
                                            result.data(), TurnWeatherContext.class),
                                    () -> retrieveWeather(record, request))
                                    .timeout(laneTimeout(record,
                                            capabilityTimeout(record, "weather.read", Duration.ofSeconds(5))))
                                    .onErrorResume(error -> weatherConversation.contextFor(""))
                            : weatherConversation.contextFor("");
                    transition(record, TurnStage.GENERATING);
                    return weather.flatMap(weatherContext ->
                            generatePrimary(record, prepared.providerRequest())
                                    .map(generated -> new GeneratedResponse(
                                            weatherContext.augment(generated.response()),
                                            generated.nativeStream()))
                                    .flatMap(generated -> sampleAlternative(
                                            record, prepared.providerRequest(), generated.response(),
                                            weatherContext,
                                            prepared.providerRequest().legacyRecentContext())
                                            .map(response -> new GeneratedResponse(
                                                    response, generated.nativeStream()))));
                })
                .switchIfEmpty(Mono.error(new IllegalStateException("LLM provider returned no response")))
                .flatMap(generated -> {
                    transition(record, TurnStage.FINALIZING);
                    return completeSemantic(record, state, generated.response(), generated.nativeStream());
                })
                .onErrorResume(error -> fail(record, error));
    }

    private Mono<List<ContextBuildRequest.ExternalBlock>> canonicalAgentContext(
            TurnRecord record,
            CanonicalTurnPipeline.Prepared planningContext) {
        TurnRequest request = record.getRequest();
        ExecutionModeDecision executionDecision = record.getExecutionModeDecision();
        if (executionDecision != null
                && executionDecision.mode() != TurnExecutionMode.AGENT) {
            return Mono.just(List.of());
        }
        if (executionDecision != null && executionDecision.reactEligible()
                && limitedReactEnabled && limitedReactPlanner != null) {
            return limitedReactContext(record);
        }
        TurnRequest.AgentProposal requested = request.agentProposal();
        if (requested != null) {
            return invokeAgentContext(
                    record, planningContext, requested, true);
        }
        if (executionDecision == null) {
            return Mono.just(List.of());
        }
        AgentRuntime runtime = agentRuntime;
        AgentRuntimeFactory.Config config = agentRuntimeConfig;
        CapabilityRuntimeFacade.TurnCapabilities frozen =
                record.getRuntimeCapabilities();
        if (runtime == null || config == null || frozen == null
                || !frozen.exposes("agent.invoke")) {
            return Mono.just(List.of());
        }

        AgentPlanningRequest planningRequest = new AgentPlanningRequest(
                planningContext.providerRequest(),
                List.of(new AgentPlanningRequest.AgentCandidate(
                        config.agentId(), config.allowedCapabilities(),
                        config.resultSchemaId())),
                request.retrievalMode().wireValue());
        return llm.planAgent(planningRequest)
                .timeout(laneTimeout(record, agentPlannerDeadline))
                .onErrorResume(error -> {
                    emitAgentOutcome(record, "agent.failed", null, false,
                            plannerFailureCode(error));
                    return Mono.empty();
                })
                .flatMap(decision -> invokeAgentContext(
                        record, planningContext,
                        new TurnRequest.AgentProposal(
                                decision.agentId(), decision.taskBrief(), false,
                                decision.executionPreference().name(),
                                "planner-" + decision.agentId()),
                        false))
                .switchIfEmpty(Mono.just(List.of()));
    }

    private Mono<List<ContextBuildRequest.ExternalBlock>> limitedReactContext(
            TurnRecord record) {
        CapabilityRuntimeFacade.TurnCapabilities frozen = record.getRuntimeCapabilities();
        ExecutionModeDecision decision = record.getExecutionModeDecision();
        if (frozen == null || decision == null || limitedReactPlanner == null) {
            return Mono.just(List.of());
        }
        StepDispatcher dispatcher = new StepDispatcher(2, 1, 1, 1);
        LimitedReActRuntime runtime = new LimitedReActRuntime(
                limitedReactPlanner,
                new ActionProposalValidator(),
                new CapabilityRuntimeReactActionExecutor(
                        capabilityRuntime, frozen, dispatcher, clock),
                new DefaultObservationNormalizer(12_288),
                new TerminationPolicy(), limitedReactTraces, clock);
        ReactInvocationScope scope = new ReactInvocationScope(
                record.getTurnId(), record.getTraceId(),
                record.getRequest().tenantId(), record.getRequest().getUserId(),
                record.getRequest().getClientId(),
                record.getRequest().authorizedCapabilityScopes(),
                frozen.snapshotId(), false);
        List<ReactSkillCandidate> skillCandidates = frozen.exposes("meguri.skill.view")
                && record.getSkillSnapshot() != null
                ? record.getSkillSnapshot().candidates().stream()
                        .map(candidate -> new ReactSkillCandidate(
                                candidate.skillId(), candidate.name(),
                                candidate.description(), candidate.tags()))
                        .toList()
                : List.of();
        ReactRunRequest request = new ReactRunRequest(
                scope, record.getRequest().getMessage(), TurnExecutionMode.AGENT,
                decision.budget(), capabilityRuntime.exposedDescriptors(frozen),
                skillCandidates, record.agentCancellation(), "limited-react-v1");
        return runtime.run(request)
                .map(result -> {
                    emitLimitedReactSkillEvents(record);
                    List<ContextBuildRequest.ExternalBlock> blocks = new java.util.ArrayList<>();
                    result.observations().stream()
                            .filter(observation -> !observation.summary().isBlank())
                            .forEach(observation -> blocks.add(
                                    new ContextBuildRequest.ExternalBlock(
                                            ContextBundle.BlockType.TOOL_RESULT,
                                            List.of("LIMITED_REACT",
                                                    "trace:" + record.getTraceId(),
                                                    "observation:" + observation.informationDigest()),
                                            ContextBundle.Trust.UNTRUSTED_EXTERNAL,
                                            observation.summary(), false)));
                    if (result.finalAnswer() != null && !result.finalAnswer().isBlank()) {
                        blocks.add(new ContextBuildRequest.ExternalBlock(
                                ContextBundle.BlockType.TOOL_RESULT,
                                List.of("LIMITED_REACT", "trace:" + record.getTraceId()),
                                ContextBundle.Trust.UNTRUSTED_EXTERNAL,
                                result.finalAnswer(), false));
                    }
                    return List.copyOf(blocks);
                })
                .onErrorResume(error -> {
                    emitAgentOutcome(record, "agent.failed", null, false,
                            "LIMITED_REACT_FAILED");
                    return Mono.just(List.of());
                })
                .doFinally(ignored -> dispatcher.close());
    }

    private void emitLimitedReactSkillEvents(TurnRecord record) {
        limitedReactTraces.traces(record.getTurnId()).stream()
                .filter(trace -> trace.actionSuccessful() != null)
                .filter(trace -> trace.capabilityId() != null)
                .filter(trace -> trace.capabilityId().startsWith("meguri.skill.")
                        || trace.capabilityId().startsWith("meguri.prompt."))
                .forEach(trace -> {
                    LinkedHashMap<String, Object> data = new LinkedHashMap<>();
                    data.put("capability_id", trace.capabilityId());
                    data.put("round_index", trace.roundIndex());
                    data.put("action_digest", trace.actionDigest());
                    putIfPresent(data, "observation_digest", trace.observationDigest());
                    data.put("reused", trace.reusedObservation());
                    emit(record, trace.actionSuccessful()
                            ? "skill.completed" : "skill.failed", Map.copyOf(data));
                });
    }

    private Mono<List<ContextBuildRequest.ExternalBlock>> invokeAgentContext(
            TurnRecord record,
            CanonicalTurnPipeline.Prepared planningContext,
            TurnRequest.AgentProposal requested,
            boolean approvalGranted) {
        boolean required = requested.required();
        AgentRuntime runtime = agentRuntime;
        AgentRuntimeFactory.Config config = agentRuntimeConfig;
        if (runtime == null || config == null) {
            emitAgentOutcome(record, "agent.failed", null, required,
                    "AGENT_RUNTIME_UNAVAILABLE");
            return required
                    ? Mono.error(new IllegalStateException(
                            "AGENT_RUNTIME_UNAVAILABLE"))
                    : Mono.just(List.of());
        }
        if (!config.agentId().equals(requested.agentId())) {
            emitAgentOutcome(record, "agent.failed", null, required,
                    "AGENT_NOT_ALLOWED");
            return required
                    ? Mono.error(new SecurityException("AGENT_NOT_ALLOWED"))
                    : Mono.just(List.of());
        }

        InvokeAgentProposal proposal = canonicalAgentProposal(
                record, planningContext, config, requested, required);
        Map<String, Object> capabilityInput = Map.of(
                "agent_id", proposal.agentId(),
                "task_brief", proposal.taskBrief(),
                "required", proposal.required(),
                "idempotency_key", proposal.idempotencySuffix());
        TurnRequest request = record.getRequest();
        return executeAgentCapabilityInternal(
                        record.getTurnId(), request.tenantId(), request.getUserId(),
                        request.getClientId(), request.getSessionId(), capabilityInput,
                        approvalGranted,
                        result -> objectMapper.convertValue(
                                result.data(), AgentInvocation.class),
                        scope -> runtime.invokeAgent(
                                record.getTurnId(), "turn:" + record.getTurnId(),
                                canonicalAgentParent(
                                        record, planningContext, config, scope),
                                proposal))
                .flatMap(invocation -> {
                    if (invocation.status() != AgentInvocation.Status.SUCCEEDED
                            && invocation.status()
                            != AgentInvocation.Status.ACCEPTED_DURABLE) {
                        String code = "AGENT_" + invocation.status().name();
                        emitAgentOutcome(record, "agent.failed", invocation,
                                required, code);
                        return required
                                ? Mono.error(new IllegalStateException(code))
                                : Mono.just(List.<ContextBuildRequest.ExternalBlock>of());
                    }
                    ContextBuildRequest.ExternalBlock block =
                            agentContextBlock(record, proposal, invocation);
                    emitAgentOutcome(record,
                            invocation.status() == AgentInvocation.Status.ACCEPTED_DURABLE
                                    ? "agent.waiting" : "agent.completed",
                            invocation,
                            required, null);
                    return Mono.just(List.of(block));
                })
                .onErrorResume(error -> {
                    emitAgentOutcome(record, "agent.failed", null, required,
                            agentFailureCode(error));
                    return required ? Mono.error(error) : Mono.just(List.of());
                });
    }

    private InvokeAgentProposal canonicalAgentProposal(
            TurnRecord record,
            CanonicalTurnPipeline.Prepared planningContext,
            AgentRuntimeFactory.Config config,
            TurnRequest.AgentProposal requested,
            boolean required) {
        AgentTaskContext.Budget budget = new AgentTaskContext.Budget(
                2_048, 4, BigDecimal.ZERO, 1, 1);
        return new InvokeAgentProposal(
                requested.agentId(),
                requested.taskBrief(),
                Map.of(
                        "turn_id", record.getTurnId(),
                        "trace_id", record.getTraceId(),
                        "retrieval_trace_id", planningContext.retrieval().traceId(),
                        "context_trace_id", planningContext.context().traceId(),
                        "knowledge_snapshot_id",
                        record.getManifest().knowledgeSnapshotId()),
                "DURABLE_ASYNC".equals(requested.mode())
                        ? InvokeAgentProposal.InvocationMode.DURABLE_ASYNC
                        : InvokeAgentProposal.InvocationMode.AWAIT,
                required,
                requested.idempotencySuffix(),
                record.getDeadlineAt(),
                budget,
                config.allowedCapabilities(),
                new InvokeAgentProposal.ResultSchema(
                        config.resultSchemaId(),
                        Map.of("summary", InvokeAgentProposal.ValueType.STRING)),
                false,
                config.pollInterval(),
                config.maxPollAttempts());
    }

    private AgentTaskContext canonicalAgentParent(
            TurnRecord record,
            CanonicalTurnPipeline.Prepared planningContext,
            AgentRuntimeFactory.Config config,
            AgentExecutionScope scope) {
        AgentTaskContext.Budget budget = new AgentTaskContext.Budget(
                2_048, 4, BigDecimal.ZERO, 1, 1);
        return new AgentTaskContext(
                record.getRequest().tenantId(), record.getRequest().getUserId(),
                null, scope.traceId(), "turn:" + record.getTurnId(),
                "turn:" + record.getTurnId(), scope.deadlineAt(),
                scope.capabilitySnapshotVersion(), scope.cancellation(), budget, 0,
                config.allowedCapabilities(), record.getRequest().getMessage(),
                Map.of(
                        "turn_id", record.getTurnId(),
                        "retrieval_trace_id", planningContext.retrieval().traceId(),
                        "context_trace_id", planningContext.context().traceId(),
                        "knowledge_snapshot_id",
                        record.getManifest().knowledgeSnapshotId(),
                        "context_items", boundedAgentContextItems(
                                planningContext.context().bundle())));
    }

    private String boundedAgentContextItems(ContextBundle bundle) {
        List<Map<String, Object>> items = bundle.blocks().stream()
                .filter(block -> block.blockType() == ContextBundle.BlockType.RETRIEVAL
                        || block.blockType() == ContextBundle.BlockType.TOOL_RESULT)
                .limit(8)
                .map(block -> Map.<String, Object>of(
                        "block_type", block.blockType().name(),
                        "source_ids", block.sourceIds().stream().limit(8).toList(),
                        "trust", block.trust().name(),
                        "content", block.content().length() <= 500
                                ? block.content()
                                : block.content().substring(0, 500)))
                .toList();
        try {
            return objectMapper.writeValueAsString(items);
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalStateException(
                    "failed to serialize bounded Agent context", error);
        }
    }

    private ContextBuildRequest.ExternalBlock agentContextBlock(
            TurnRecord record,
            InvokeAgentProposal proposal,
            AgentInvocation invocation) {
        LinkedHashMap<String, Object> content = new LinkedHashMap<>();
        content.put("status", invocation.status().name());
        content.put("source_agent_id", proposal.agentId());
        if (invocation.result() != null) {
            AgentResult result = invocation.result().asUntrusted();
            content.put("schema_id", result.schemaId());
            content.put("payload", result.payload());
            content.put("trust", result.trustLabel().name());
        } else {
            content.put("durable_reference", Map.of(
                    "task_id", invocation.taskId(),
                    "remote_task_id", invocation.remoteTaskId() == null
                            ? "" : invocation.remoteTaskId()));
        }
        List<String> sources = new java.util.ArrayList<>(List.of(
                "REMOTE_AGENT", "agent:" + proposal.agentId(),
                "trace:" + record.getTraceId(),
                "execution:" + invocation.executionId(),
                "task:" + invocation.taskId()));
        if (invocation.remoteTaskId() != null) {
            sources.add("remote-task:" + invocation.remoteTaskId());
        }
        try {
            return new ContextBuildRequest.ExternalBlock(
                    ContextBundle.BlockType.TOOL_RESULT, List.copyOf(sources),
                    ContextBundle.Trust.UNTRUSTED_EXTERNAL,
                    objectMapper.writeValueAsString(content), proposal.required());
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalStateException("failed to serialize remote agent result", error);
        }
    }

    private void emitAgentOutcome(
            TurnRecord record,
            String type,
            AgentInvocation invocation,
            boolean required,
            String errorCode) {
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("required", required);
        data.put("trust", ContextBundle.Trust.UNTRUSTED_EXTERNAL.name());
        if (invocation != null) {
            putIfPresent(data, "execution_id", invocation.executionId());
            putIfPresent(data, "task_id", invocation.taskId());
            putIfPresent(data, "remote_task_id", invocation.remoteTaskId());
            data.put("status", invocation.status().name().toLowerCase(
                    java.util.Locale.ROOT));
        }
        putIfPresent(data, "error_code", errorCode);
        emit(record, type, Map.copyOf(data));
    }

    private static String agentFailureCode(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current instanceof SecurityException
                ? "AGENT_CAPABILITY_NOT_AUTHORIZED"
                : current.getClass().getSimpleName();
    }

    private static String plannerFailureCode(Throwable error) {
        for (Throwable current = error; current != null && current.getCause() != current;
                current = current.getCause()) {
            if (current instanceof AgentPlannerException planner) {
                return switch (planner.reason()) {
                    case QUEUE_SATURATED -> "AGENT_PLANNER_SATURATED";
                    case PROVIDER_TIMEOUT, INTERRUPTED -> "AGENT_PLANNER_TIMEOUT";
                    case INVALID_RESPONSE -> "AGENT_PLANNER_INVALID_RESPONSE";
                    case UPSTREAM_FAILURE -> "AGENT_PLANNER_UPSTREAM_FAILED";
                };
            }
            if (current instanceof TimeoutException) return "AGENT_PLANNER_TIMEOUT";
        }
        return "AGENT_PLANNER_UNAVAILABLE";
    }

    private Mono<GeneratedResponse> generatePrimary(
            TurnRecord record, ProviderRequest request) {
        if (!llm.supportsNativeStreaming()) {
            markLatency(record, TurnLatencyPoint.PROVIDER_REQUEST_SENT);
            return llm.respond(request)
                    .doOnNext(ignored -> {
                        markLatency(record, TurnLatencyPoint.PROVIDER_FIRST_BYTE);
                        markLatencyMissing(record, TurnLatencyPoint.PROVIDER_FIRST_TOKEN,
                                TurnLatencyMissingReason.PROVIDER_UNSUPPORTED);
                    })
                    .map(response -> new GeneratedResponse(response, false));
        }
        AtomicLong index = new AtomicLong();
        StringBuilder reply = new StringBuilder();
        markLatency(record, TurnLatencyPoint.PROVIDER_REQUEST_SENT);
        Flux<String> providerStream = llm.stream(request)
                .doOnNext(delta -> {
                    if (delta != null && !delta.isEmpty()) {
                        markLatency(record, TurnLatencyPoint.PROVIDER_FIRST_BYTE);
                        markLatency(record, TurnLatencyPoint.PROVIDER_FIRST_TOKEN);
                    }
                });
        Flux<String> visibleStream = UserVisibleReplyStream.sanitize(providerStream);
        return NativeTextDeltaAggregator.aggregate(visibleStream)
                .doOnNext(delta -> {
                    if (record.isCancelRequested()) {
                        throw new java.util.concurrent.CancellationException("turn cancelled during provider stream");
                    }
                    emit(record, "text.delta", Map.of(
                            "delta", delta,
                            "index", index.incrementAndGet(),
                            "native", true));
                    reply.append(delta);
                })
                .then(Mono.defer(() -> {
                    if (reply.isEmpty()) {
                        return Mono.error(new IllegalStateException("LLM provider returned an empty stream"));
                    }
                    return llm.finalizeStream(reply.toString(), request)
                            .onErrorReturn(new LlmResponse(reply.toString()))
                            .map(response -> new GeneratedResponse(response, true));
                }));
    }

    private Duration laneTimeout(TurnRecord record, Duration maximum) {
        Duration remaining = record.remaining();
        return remaining.compareTo(maximum) < 0 ? remaining : maximum;
    }

    private Duration capabilityTimeout(TurnRecord record, String capabilityId, Duration fallback) {
        CapabilityRuntimeFacade.TurnCapabilities snapshot = record.getRuntimeCapabilities();
        if (snapshot == null) return fallback;
        return capabilityRuntime.exposedDescriptors(snapshot).stream()
                .filter(descriptor -> capabilityId.equals(descriptor.id()))
                .map(CapabilityDescriptor::timeout)
                .findFirst()
                .orElse(fallback);
    }

    private void transition(TurnRecord record, TurnStage stage) {
        LifecycleState before = LifecycleState.capture(record);
        record.transitionTo(stage);
        try {
            emit(record, "turn.stage.changed", Map.of("stage", stage.wireValue()));
        } catch (Throwable persistenceError) {
            before.restore(record);
            throw persistenceError;
        }
    }

    private Mono<LlmResponse> sampleAlternative(
            TurnRecord record,
            ProviderRequest providerRequest,
            LlmResponse first,
            TurnWeatherContext weather,
            List<String> durableRecent) {
        TurnRequest request = providerRequest.turn();
        if (!trainingFeedback.shouldCompare(request.trainingMode())) {
            if (request.trainingMode()) {
                trainingFeedback.registerSingle(record.getTurnId(), request, durableRecent, first,
                        llm.providerName(), buildId);
            }
            return Mono.just(first);
        }
        return llm.respondAlternative(providerRequest)
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

    private static String userMessageId(TurnRecord record) {
        return "turn:" + record.getTurnId() + ":user";
    }

    private static String assistantMessageId(TurnRecord record) {
        return "turn:" + record.getTurnId() + ":assistant";
    }

    private static PersonaRuntime.PersonaSnapshot personaSnapshot(
            RuntimeState state, EffectivePersonaState effective) {
        Map<String, PersonaRuntime.Provenance> provenance = new LinkedHashMap<>();
        provenance.put("persona", new PersonaRuntime.Provenance(
                "persona_profile:" + effective.persona().revision(), "user", 100));
        provenance.put("relationship", new PersonaRuntime.Provenance(
                "relationship_state:" + effective.relationship().version(), "user", 100));
        provenance.put("scene", new PersonaRuntime.Provenance(
                effective.scene() == null ? "scene:none"
                        : "scene_state:" + effective.scene().version(), "session", 80));
        provenance.put("policy", new PersonaRuntime.Provenance(
                effective.policyRevision(), "turn", 100));
        String revision = effective.persona().revision()
                + ":r" + effective.relationship().version()
                + ":s" + (effective.scene() == null ? "none" : effective.scene().version())
                + ":" + effective.policyRevision();
        return new PersonaRuntime.PersonaSnapshot(state, revision, provenance);
    }

    private static List<ContextBuildRequest.ExternalBlock> attachmentContext(
            TurnRequest request) {
        List<String> accepted = LocalResourcePromptContext.from(request.attachments());
        return java.util.stream.IntStream.range(0, accepted.size())
                .mapToObj(index -> new ContextBuildRequest.ExternalBlock(
                        ContextBundle.BlockType.TOOL_RESULT,
                        List.of("ATTACHMENT", "attachment:" + index),
                        ContextBundle.Trust.USER,
                        accepted.get(index), true))
                .toList();
    }

    private static ContextProfile defaultContextProfile(LlmProvider provider) {
        java.util.EnumMap<ContextBundle.BlockType, ContextProfile.SourceBudget> budgets =
                new java.util.EnumMap<>(ContextBundle.BlockType.class);
        budgets.put(ContextBundle.BlockType.RECENT_RAW,
                new ContextProfile.SourceBudget(1_200, 7_000));
        budgets.put(ContextBundle.BlockType.SUMMARY,
                new ContextProfile.SourceBudget(256, 2_500));
        budgets.put(ContextBundle.BlockType.REHYDRATED,
                new ContextProfile.SourceBudget(256, 2_000));
        budgets.put(ContextBundle.BlockType.MEMORY,
                new ContextProfile.SourceBudget(256, 2_000));
        budgets.put(ContextBundle.BlockType.RETRIEVAL,
                new ContextProfile.SourceBudget(512, 3_500));
        budgets.put(ContextBundle.BlockType.TOOL_RESULT,
                new ContextProfile.SourceBudget(0, 1_500));
        return new ContextProfile(
                provider.modelId(), 16_000, 1_600, 700,
                0.70, 0.90, budgets);
    }

    private boolean shouldEnqueuePostReplyMemory(
            TurnRecord record, TurnRequest request) {
        return request.formalMemoryAllowed()
                && !trainingFeedback.hasComparison(record.getTurnId())
                && !LocalResourcePromptContext.hasAcceptedReference(request.attachments());
    }

    private static double intensityScore(Intensity intensity) {
        return switch (intensity) {
            case LOW -> 0.25;
            case MEDIUM -> 0.60;
            case HIGH -> 0.90;
        };
    }

    private Mono<TurnWeatherContext> retrieveWeather(TurnRecord record, TurnRequest request) {
        TurnResolution resolution = weatherResolution(record);
        WeatherConversationService.Intent intent = resolution.intent();
        if (intent == WeatherConversationService.Intent.NONE) {
            return weatherConversation.contextFor(resolution);
        }
        emit(record, "tool.started", Map.of(
                "tool_name", "weather",
                "intent", intent.name().toLowerCase()));
        return weatherConversation.contextFor(resolution)
                .doOnNext(context -> emit(record, "tool.completed", Map.of(
                        "tool_name", "weather",
                        "intent", intent.name().toLowerCase(),
                        "status", context.available() ? "ok" : "unavailable")));
    }

    private TurnResolution weatherResolution(TurnRecord record) {
        TurnRequest request = record.getRequest();
        return weatherConversation.resolveTurn(
                request.getMessage(),
                sessions.recent(request.getUserId(), request.getClientId(), request.getSessionId()));
    }

    private Mono<Void> runDirectWeatherRecord(TurnRecord record, RuntimeState state) {
        TurnRequest request = record.getRequest();
        transition(record, TurnStage.RETRIEVING);
        return executeCapability(
                        record,
                        "weather.read",
                        Map.of("message", request.getMessage()),
                        false,
                        result -> objectMapper.convertValue(
                                result.data(), TurnWeatherContext.class),
                        () -> retrieveWeather(record, request))
                .timeout(laneTimeout(record,
                        capabilityTimeout(record, "weather.read", Duration.ofSeconds(5))))
                .onErrorResume(error -> weatherConversation.contextFor(weatherResolution(record)))
                .flatMap(weatherContext -> {
                    transition(record, TurnStage.GENERATING);
                    transition(record, TurnStage.FINALIZING);
                    return completeSemantic(
                            record, state,
                            weatherContext.directResponse(request.replyFormat()),
                            false);
                })
                .onErrorResume(error -> fail(record, error));
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

    private Mono<Void> completeSemantic(TurnRecord record, RuntimeState state,
                                        LlmResponse response, boolean nativeStream) {
        TurnRequest request = record.getRequest();
        if (record.isCancelRequested()) return cancelRecord(record, "client_requested");
        ResolvedExpression expression;
        PresentationResolver.ResolvedPresentation presentation;
        try {
            presentation = presentationResolver.resolve(
                    new PresentationIntent(
                            response.getExpressionTag().value(),
                            response.getVoiceStyle().value(),
                            "none",
                            intensityScore(response.getExpressionIntensity())),
                    record.getEffectivePersonaState());
            expression = new ResolvedExpression(
                    ExpressionTag.fromValue(presentation.expressionTag()),
                    response.getExpressionIntensity(), state.getOutfitCode());
        } catch (Throwable ignored) {
            presentation = new PresentationResolver.ResolvedPresentation(
                    "neutral", null, null, 0.25);
            expression = new ResolvedExpression(ExpressionTag.NEUTRAL, Intensity.LOW, state.getOutfitCode());
        }
        Map<String, Object> semantic = new LinkedHashMap<>(beanMap(response));
        semantic.putAll(beanMap(expression));
        semantic.put("presentation", beanMap(presentation));
        emit(record, "semantic.completed", semantic);
        List<Map<String, Object>> artifacts = new ModelGeneratedArtifactResolver()
                .resolve(response.getReply()).stream()
                .map(artifact -> Map.<String, Object>of(
                        "label", artifact.label(),
                        "href", artifact.href(),
                        "local_path", artifact.localPath()))
                .toList();
        List<Map<String, Object>> documentEdits = documentEditProposals
                .resolve(response.getReply(), request.attachments());
        ResolvedExpression finalExpression = expression;
        PresentationResolver.ResolvedPresentation finalPresentation = presentation;
        Mono<Void> textDelivery = nativeStream
                ? Mono.empty()
                : Mono.delay(streamInterval)
                .then(Mono.defer(() -> {
                    if (record.isCancelRequested()) return cancelRecord(record, "client_requested");
                    // Structured-only providers emit one complete delta. They must
                    // not pretend to provide token streaming by slicing text locally.
                    emit(record, "text.delta", Map.of(
                            "delta", response.getReply(),
                            "index", 1,
                            "native", false));
                    return Mono.<Void>empty();
                }));
        return textDelivery.then(Mono.defer(() -> {
                    if (record.isCancelRequested()) return cancelRecord(record, "client_requested");
                    Map<String, Object> textCompleted = new LinkedHashMap<>();
                    textCompleted.put("text", response.getReply());
                    if (!artifacts.isEmpty()) textCompleted.put("artifacts", artifacts);
                    if (!documentEdits.isEmpty()) textCompleted.put("document_edits", documentEdits);
                    emit(record, "text.completed", Map.copyOf(textCompleted));
                    if (request.getClientCapabilities().isVoice()) {
                        emit(record, "tts.requested", Map.of(
                                "text", response.getReply(),
                                "voice_style", finalPresentation.voiceStyle() == null
                                        ? "neutral" : finalPresentation.voiceStyle(),
                                "expression_intensity", response.getExpressionIntensity().value()));
                    }
                    sessions.appendNode(
                            request.getUserId(), request.getClientId(), request.getSessionId(),
                            assistantMessageId(record), userMessageId(record),
                            new SessionContextStore.Message("assistant", response.getReply()));
                    emit(record, "expression.cue", beanMap(finalExpression));
                    emit(record, "sprite.resolved", beanMap(finalExpression));
                    boolean enqueueMemory = shouldEnqueuePostReplyMemory(record, request);
                    ChatResponse result = new ChatResponse(
                            record.getTurnId(), request.getSessionId(), response,
                            state, finalExpression,
                            enqueueMemory ? MemoryStatus.PENDING : MemoryStatus.UNAVAILABLE,
                            buildId);
                    Map<String, Object> completed = new LinkedHashMap<>();
                    completed.put("reply", response.getReply());
                    if (!artifacts.isEmpty()) completed.put("artifacts", artifacts);
                    if (!documentEdits.isEmpty()) completed.put("document_edits", documentEdits);
                    if (!appendTerminal(record, () -> record.tryComplete(result),
                            "turn.completed", Map.copyOf(completed))) {
                        return record.isCancelRequested()
                                ? cancelRecord(record, "client_requested")
                                : Mono.empty();
                    }
                    if (!enqueueMemory) return Mono.empty();
                    try {
                        postReplyMemoryJobs.enqueue(
                                record.getTurnId(), request, response, record.getTraceId(),
                                false,
                                PostReplyMemoryJob.CancellationPolicy.PROCESS_COMPLETED_REPLY);
                    } catch (RuntimeException ignored) {
                        // The completed Turn is immutable; the queue has its own operational health boundary.
                    }
                    return Mono.empty();
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
        String failureCode = record.getStage() == TurnStage.GENERATING
                ? "PROVIDER_STREAM_INTERRUPTED" : "TURN_EXECUTION_FAILED";
        return fail(record, failureCode, message);
    }

    private Mono<Void> fail(TurnRecord record, String failureCode, String message) {
        if (record.isCancelRequested()) return cancelRecord(record, "client_requested");
        appendTerminal(record, () -> record.tryFail(failureCode, message), "turn.failed",
                Map.of("error", message, "failure_code", failureCode));
        return Mono.empty();
    }

    private boolean appendTerminal(TurnRecord record, BooleanSupplier transition,
                                   String eventType, Map<String, Object> data) {
        LifecycleState before = LifecycleState.capture(record);
        if (!transition.getAsBoolean()) return false;
        try {
            LinkedHashMap<String, Object> terminalData = new LinkedHashMap<>(
                    data == null ? Map.of() : data);
            TurnLatencyTraceRecorder latency = record.getLatencyTraceRecorder();
            if (latency != null) {
                latency.markMissing(TurnLatencyPoint.FIRST_DELTA_SSE_FLUSHED,
                        TurnLatencyMissingReason.NOT_RECORDED);
                latency.markMissing(TurnLatencyPoint.CLIENT_FIRST_DELTA_RECEIVED,
                        TurnLatencyMissingReason.CLIENT_UNSUPPORTED);
                latency.markMissing(TurnLatencyPoint.CLIENT_FIRST_RENDER,
                        TurnLatencyMissingReason.CLIENT_UNSUPPORTED);
                terminalData.put("latency_trace", beanMap(latency.snapshot()));
            }
            emit(record, eventType, Map.copyOf(terminalData));
        } catch (Throwable persistenceError) {
            before.restore(record);
            throw persistenceError;
        }
        record.completeDone();
        capabilityRuntime.release(record.getRuntimeCapabilities());
        if (skillDisclosure != null) skillDisclosure.release(record.getTurnId());
        return true;
    }

    private EventEnvelope emit(TurnRecord record, String type, Map<String, Object> data) {
        if (!TurnEventTypes.isSupported(type)) {
            throw new IllegalArgumentException("unsupported turn event type: " + type);
        }
        EventEnvelope event = journal.appendExecution(record, executionOwnerId, type,
                data == null ? Map.of() : data,
                new EventMetadata(record.getTraceId(), "meguri-core", Instant.now(), buildId));
        if ("text.delta".equals(type)) {
            markLatency(record, TurnLatencyPoint.FIRST_DELTA_PERSISTED);
        }
        return event;
    }

    private static void markLatency(TurnRecord record, TurnLatencyPoint point) {
        TurnLatencyTraceRecorder recorder = record.getLatencyTraceRecorder();
        if (recorder != null) recorder.mark(point);
    }

    private static void markLatencyMissing(
            TurnRecord record,
            TurnLatencyPoint point,
            TurnLatencyMissingReason reason) {
        TurnLatencyTraceRecorder recorder = record.getLatencyTraceRecorder();
        if (recorder != null) recorder.markMissing(point, reason);
    }

    private <T> Mono<T> executeCapability(
            TurnRecord record,
            String capabilityId,
            Map<String, Object> input,
            boolean approvalGranted,
            Function<CapabilityResult, T> replayDecoder,
            Supplier<Mono<T>> operation) {
        return Mono.fromCallable(() -> executeCapabilityBlocking(
                        record, capabilityId, input, approvalGranted,
                        replayDecoder, operation))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private <T> T executeCapabilityBlocking(
            TurnRecord record,
            String capabilityId,
            Map<String, Object> input,
            boolean approvalGranted,
            Function<CapabilityResult, T> replayDecoder,
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
            ApprovalService.Approval resolved;
            if (approvalGranted) {
                resolved = capabilityRuntime.resolveApproval(
                        approval.approvalId(),
                        ApprovalService.Decision.ACCEPT,
                        "agent.invoke".equals(capabilityId)
                                ? "explicit_agent_request"
                                : "adapter_permission");
            } else {
                resolved = awaitApproval(record, approval);
            }
            emitApprovalResolved(record, resolved);
            proposal = toolProposal(
                    record, capabilityId, input, operationId, idempotencyKey,
                    approval.approvalId());
        }

        AtomicReference<T> output = new AtomicReference<>();
        ToolProposal frozenProposal = proposal;
        CapabilityResult result = capabilityRuntime.execute(
                record.getRuntimeCapabilities(),
                frozenProposal,
                (ignoredInput, ignoredContext) -> {
                    Mono<T> publisher = Objects.requireNonNull(
                            operation.get(), "capability operation returned null");
                    T value = publisher.block(record.remaining());
                    if (value == null) {
                        throw new IllegalStateException(
                                "capability operation completed without a value");
                    }
                    output.set(value);
                    return asObjectMap(value);
                });
        if (result.status() != CapabilityResult.Status.SUCCESS) {
            throw new IllegalStateException(
                    "capability rejected: " + result.errorCode());
        }
        T value = output.get();
        if (value == null) {
            try {
                value = replayDecoder.apply(result);
            } catch (RuntimeException invalidReplay) {
                throw new IllegalStateException(
                        "capability replay result could not be decoded",
                        invalidReplay);
            }
        }
        if (value == null) {
            throw new IllegalStateException("capability replay result is empty");
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private <T> T decodeScalarCapabilityReplay(CapabilityResult result) {
        if (result.data().size() == 1 && result.data().containsKey("value")) {
            return (T) result.data().get("value");
        }
        throw new IllegalStateException(
                "a typed replay decoder is required for structured capability results");
    }

    private ApprovalService.Approval awaitApproval(
            TurnRecord record,
            ApprovalService.Approval requested) {
        while (true) {
            ApprovalService.Approval current = capabilityRuntime
                    .findApproval(requested.approvalId())
                    .orElseThrow(() -> new IllegalStateException(
                            "approval disappeared while Turn was waiting"));
            if (current.decision() != ApprovalService.Decision.PENDING) {
                return current;
            }
            if (record.isCancelRequested() || record.remaining().isZero()
                    || record.remaining().isNegative()) {
                try {
                    return capabilityRuntime.resolveApproval(
                            requested.approvalId(),
                            ApprovalService.Decision.CANCEL,
                            record.isCancelRequested()
                                    ? "turn_cancelled" : "turn_deadline");
                } catch (IllegalStateException raced) {
                    return capabilityRuntime.findApproval(requested.approvalId())
                            .orElseThrow(() -> raced);
                }
            }
            try {
                Thread.sleep(Math.min(100L,
                        Math.max(1L, record.remaining().toMillis())));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                try {
                    capabilityRuntime.resolveApproval(
                            requested.approvalId(),
                            ApprovalService.Decision.CANCEL,
                            "turn_interrupted");
                } catch (IllegalStateException ignored) {
                    // A concurrent client decision remains authoritative.
                }
                throw new IllegalStateException(
                        "approval wait was interrupted", interrupted);
            }
        }
    }

    private void emitApprovalResolved(
            TurnRecord record, ApprovalService.Approval resolved) {
        LinkedHashMap<String, Object> event = new LinkedHashMap<>();
        event.put("approval_id", resolved.approvalId());
        event.put("capability_id", resolved.capabilityId());
        putIfPresent(event, "operation_id", resolved.operationId());
        event.put("decision", resolved.decision().name().toLowerCase());
        emit(record, "approval.resolved", Map.copyOf(event));
    }

    private ToolProposal toolProposal(
            TurnRecord record,
            String capabilityId,
            Map<String, Object> input,
            String operationId,
            String idempotencyKey,
            String approvalId) {
        TurnRequest request = record.getRequest();
        boolean explicitWeather = request.retrievalMode() != RetrievalMode.NONE
                && weatherResolution(record).intent() != WeatherConversationService.Intent.NONE;
        ExecutionModeDecision executionDecision = record.getExecutionModeDecision();
        boolean executionAllowsNetwork = executionDecision == null
                ? request.retrievalMode() == RetrievalMode.SLOW
                        || request.agentProposal() != null
                : executionDecision.mode() == TurnExecutionMode.AGENT;
        boolean networkAllowed = (executionAllowsNetwork || explicitWeather)
                && ("web.read".equals(capabilityId)
                || "weather.read".equals(capabilityId)
                || "agent.invoke".equals(capabilityId)
                || capabilityId.startsWith("mcp."));
        return new ToolProposal(
                record.getTurnId(),
                record.getTraceId(),
                request.tenantId(),
                request.getUserId(),
                request.getClientId(),
                capabilityId,
                input,
                request.authorizedCapabilityScopes(),
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
        boolean explicitAgent = request.agentProposal() != null;
        boolean explicitWeather = request.retrievalMode() != RetrievalMode.NONE
                && weatherResolution(record).intent() != WeatherConversationService.Intent.NONE;
        ExecutionModeDecision executionDecision = record.getExecutionModeDecision();
        CapabilityDescriptor.Mode mode = executionDecision == null
                ? switch (request.retrievalMode()) {
                    case NONE, FAST -> CapabilityDescriptor.Mode.FAST;
                    case SLOW -> CapabilityDescriptor.Mode.DEEP;
                }
                : switch (executionDecision.mode()) {
                    case FAST -> CapabilityDescriptor.Mode.FAST;
                    case THINK -> CapabilityDescriptor.Mode.BALANCED;
                    case AGENT -> CapabilityDescriptor.Mode.DEEP;
                };
        if (executionDecision == null && explicitAgent) {
            mode = CapabilityDescriptor.Mode.DEEP;
        }
        return new ExposurePlanner.ExposureContext(
                record.getTurnId(),
                request.tenantId(),
                request.getUserId(),
                request.getClientId(),
                request.authorizedCapabilityScopes(),
                mode,
                explicitWeather ? Set.of("weather.read") : Set.of(),
                1,
                executionDecision == null
                        ? request.retrievalMode() == RetrievalMode.SLOW || explicitAgent || explicitWeather
                        : executionDecision.mode() == TurnExecutionMode.AGENT || explicitWeather,
                request.formalMemoryAllowed()
                        ? CapabilityDescriptor.DataClassification.CONFIDENTIAL
                        : CapabilityDescriptor.DataClassification.INTERNAL);
    }

    private void registerRuntimeCapabilities() {
        // Lore, Memory, Knowledge and Web are typed Retrieval providers governed
        // by RetrievalAuthorizationPolicy, not callable Tools. Registering fake
        // Tool descriptors for them would create a second, non-authoritative path.
        registerRuntimeCapability(runtimeDescriptor(
                "weather.read",
                CapabilityDescriptor.Kind.READ_TOOL,
                CapabilityDescriptor.SideEffect.EXTERNAL,
                CapabilityDescriptor.ApprovalRequirement.NONE,
                Duration.ofSeconds(5),
                Set.of(
                        CapabilityDescriptor.Mode.FAST,
                        CapabilityDescriptor.Mode.BALANCED,
                        CapabilityDescriptor.Mode.DEEP),
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
        registerSkillCapabilities();
    }

    private void registerSkillCapabilities() {
        capabilityRuntime.register(skillDescriptor(
                "meguri.skill.search",
                objectSchema(Map.of("query", CapabilityDescriptor.ValueType.STRING), Set.of())),
                (input, context) -> requireSkillDisclosure().search(
                        context.turnId(), String.valueOf(input.getOrDefault("query", ""))));
        capabilityRuntime.register(skillDescriptor(
                "meguri.skill.view",
                objectSchema(Map.of(
                        "skill_id", CapabilityDescriptor.ValueType.STRING,
                        "path", CapabilityDescriptor.ValueType.STRING), Set.of("skill_id"))),
                (input, context) -> {
                    Map<String, Object> result = requireSkillDisclosure().view(
                            context.turnId(), String.valueOf(input.get("skill_id")),
                            input.get("path") == null ? "SKILL.md" : String.valueOf(input.get("path")));
                    persistSkillSnapshot(context.turnId());
                    return result;
                });
    }

    private void persistSkillSnapshot(String turnId) {
        TurnRecord record = journal.turn(turnId);
        if (record == null) throw new IllegalStateException("Skill Turn no longer exists");
        FrozenSkillSnapshot before = record.getSkillSnapshot();
        record.updateSkillSnapshot(requireSkillDisclosure().snapshot(turnId));
        try {
            journal.persistExecution(record, executionOwnerId);
        } catch (RuntimeException failure) {
            record.restoreSkillSnapshot(before);
            throw failure;
        }
    }

    private SkillDisclosureService requireSkillDisclosure() {
        SkillDisclosureService service = skillDisclosure;
        if (service == null) throw new IllegalStateException("external Skill disclosure is unavailable");
        return service;
    }

    private static CapabilityDescriptor skillDescriptor(
            String id, CapabilityDescriptor.Schema inputSchema) {
        return new CapabilityDescriptor(
                id, "1", CapabilityDescriptor.Kind.READ_TOOL, "meguri-core",
                inputSchema, new CapabilityDescriptor.Schema(Map.of(), Set.of(), true, Set.of()),
                Set.of("skill:read"), CapabilityDescriptor.SideEffect.READ,
                CapabilityDescriptor.ApprovalRequirement.NONE, Duration.ofSeconds(2),
                CapabilityDescriptor.RetryPolicy.none(), new CapabilityDescriptor.ConcurrencyPolicy(4),
                Set.of(CapabilityDescriptor.Mode.DEEP), CapabilityDescriptor.DataClassification.INTERNAL,
                CapabilityDescriptor.ResultTrust.UNTRUSTED_EXTERNAL,
                "turn-operation://" + id, CapabilityDescriptor.Health.HEALTHY,
                false, 1, CapabilityDescriptor.NetworkPolicy.denied(), Set.of(),
                CapabilityDescriptor.CostPolicy.free(), CapabilityDescriptor.CachePolicy.disabled(),
                CapabilityDescriptor.IdempotencyPolicy.none());
    }

    private void registerRuntimeCapability(CapabilityDescriptor descriptor) {
        capabilityRuntime.register(
                descriptor,
                (input, context) -> {
                    throw new IllegalStateException(
                            "turn runtime callback is required for " + descriptor.id());
                });
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

    private record LifecycleState(
            TurnStatus status,
            TurnStage stage,
            ChatResponse result,
            String failureCode,
            String error,
            String retryOfTurnId,
            long version) {

        private static LifecycleState capture(TurnRecord record) {
            return new LifecycleState(
                    record.getStatus(), record.getStage(), record.getResult(),
                    record.getFailureCode(), record.getError(),
                    record.getRetryOfTurnId(), record.getVersion());
        }

        private void restore(TurnRecord record) {
            record.restore(status, stage, record.getManifest(), result,
                    failureCode, error, retryOfTurnId, version);
        }
    }

    private record GeneratedResponse(LlmResponse response, boolean nativeStream) { }
}

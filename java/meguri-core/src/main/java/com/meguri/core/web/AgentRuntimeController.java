package com.meguri.core.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.meguri.core.agent.AgentCancelledException;
import com.meguri.core.agent.AgentDeadlineExceededException;
import com.meguri.core.agent.AgentInvocation;
import com.meguri.core.agent.AgentPolicyException;
import com.meguri.core.agent.AgentResult;
import com.meguri.core.agent.AgentRuntimeAssembly;
import com.meguri.core.agent.AgentRuntimeFactory;
import com.meguri.core.agent.AgentRuntimePolicy;
import com.meguri.core.agent.AgentRuntimeState;
import com.meguri.core.agent.AgentTask;
import com.meguri.core.agent.AgentTaskContext;
import com.meguri.core.agent.AgentUnavailableException;
import com.meguri.core.agent.InvokeAgentProposal;
import com.meguri.core.runtime.TurnOrchestrator;
import com.meguri.core.security.CoreIdentityVerifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

@RestController
@RequestMapping("/v1/agent/tasks")
public final class AgentRuntimeController {
    private final AgentRuntimeAssembly assembly;
    private final CoreIdentityVerifier identityVerifier;
    private final AgentRuntimePolicy policy;
    private final Clock clock;
    private final TurnOrchestrator orchestrator;

    @Autowired
    public AgentRuntimeController(
            AgentRuntimeAssembly assembly,
            CoreIdentityVerifier identityVerifier,
            AgentRuntimePolicy policy,
            TurnOrchestrator orchestrator) {
        this(assembly, identityVerifier, policy, Clock.systemUTC(), orchestrator);
    }

    AgentRuntimeController(
            AgentRuntimeAssembly assembly,
            CoreIdentityVerifier identityVerifier,
            AgentRuntimePolicy policy) {
        this(assembly, identityVerifier, policy, Clock.systemUTC(), null);
    }

    AgentRuntimeController(
            AgentRuntimeAssembly assembly,
            CoreIdentityVerifier identityVerifier,
            AgentRuntimePolicy policy,
            Clock clock,
            TurnOrchestrator orchestrator) {
        this.assembly = Objects.requireNonNull(assembly, "assembly");
        this.identityVerifier = Objects.requireNonNull(identityVerifier, "identityVerifier");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.orchestrator = orchestrator;
    }

    @PostMapping
    public Mono<ResponseEntity<InvocationResponse>> invoke(
            @RequestHeader(name = "Idempotency-Key") String idempotencyKey,
            @RequestBody InvokeRequest request,
            ServerWebExchange exchange) {
        return Mono.defer(() -> {
                    RequestIdentity identity = verifyRequestIdentity(request.identity(), exchange);
                    String safeKey = required(idempotencyKey, "Idempotency-Key");
                    Instant now = clock.instant();
                    Instant maximumDeadline = now.plus(policy.maximumDeadline());
                    Instant deadline = Objects.requireNonNull(request.deadline(), "deadline is required");
                    if (!deadline.isAfter(now)) {
                        throw new AgentDeadlineExceededException("deadline must be in the future");
                    }
                    if (deadline.isAfter(maximumDeadline)) {
                        throw new AgentPolicyException("deadline cannot exceed the server maximum");
                    }
                    AgentTaskContext.Budget budget = request.budget().toDomain();
                    policy.maximumBudget().narrow(budget);
                    Set<String> scopes = Set.copyOf(request.allowedCapabilities());
                    if (!policy.allowedCapabilities().containsAll(scopes)) {
                        throw new AgentPolicyException("allowed capabilities cannot expand server policy");
                    }

                    Map<String, String> durableReferences = new LinkedHashMap<>(request.references());
                    if (durableReferences.keySet().stream().anyMatch(key -> key.startsWith("_"))) {
                        throw new AgentPolicyException("reference keys beginning with '_' are reserved");
                    }
                    durableReferences.put("_client_id", identity.clientId());
                    durableReferences.put("_session_id", identity.sessionId());
                    String parentTaskId = scopedParentId(
                            identity, required(request.parentTaskId(), "parent_task_id"));
                    java.util.function.Function<
                            TurnOrchestrator.AgentExecutionScope,
                            Mono<com.meguri.core.agent.AgentInvocation>> operation =
                            scope -> invokeWithinScope(
                                    identity, request, safeKey, deadline, budget, scopes,
                                    durableReferences, parentTaskId, scope);
                    Mono<com.meguri.core.agent.AgentInvocation> invocation =
                            orchestrator == null
                                    ? operation.apply(new TurnOrchestrator.AgentExecutionScope(
                                            maximumDeadline,
                                            "trace-" + required(request.turnId(), "turn_id"),
                                            "capability:test",
                                            new com.meguri.core.agent.CancellationToken()))
                                    : orchestrator.executeAgentCapability(
                                            request.turnId(),
                                            identity.tenantId(),
                                            identity.userId(),
                                            identity.clientId(),
                                            identity.sessionId(),
                                            Map.of(
                                                    "agent_id", assembly.config().agentId(),
                                                    "task_brief", request.taskBrief(),
                                                    "mode", request.mode().name(),
                                                    "required", request.required(),
                                                    "deadline", deadline.toString(),
                                                    "max_tokens", budget.maxTokens(),
                                                    "allowed_capabilities", scopes,
                                                    "idempotency_key", safeKey),
                                            operation);
                    return invocation
                            .map(result -> ResponseEntity
                                    .status(result.status() == AgentInvocation.Status.ACCEPTED_DURABLE
                                            ? HttpStatus.ACCEPTED : HttpStatus.OK)
                                    .body(InvocationResponse.from(result, providerMode())));
                })
                .onErrorMap(this::mapFailure);
    }

    private Mono<com.meguri.core.agent.AgentInvocation> invokeWithinScope(
            RequestIdentity identity,
            InvokeRequest request,
            String idempotencyKey,
            Instant requestedDeadline,
            AgentTaskContext.Budget budget,
            Set<String> scopes,
            Map<String, String> durableReferences,
            String parentTaskId,
            TurnOrchestrator.AgentExecutionScope scope) {
        Instant effectiveDeadline = requestedDeadline.isAfter(scope.deadlineAt())
                ? scope.deadlineAt() : requestedDeadline;
        AgentTaskContext parent = new AgentTaskContext(
                identity.tenantId(),
                identity.userId(),
                null,
                scope.traceId(),
                "span-" + request.turnId().trim(),
                "http-agent/" + request.turnId().trim() + "/" + idempotencyKey,
                scope.deadlineAt(),
                scope.capabilitySnapshotVersion(),
                scope.cancellation(),
                policy.maximumBudget(),
                0,
                policy.allowedCapabilities(),
                request.taskBrief(),
                Map.of());
        InvokeAgentProposal proposal = new InvokeAgentProposal(
                assembly.config().agentId(),
                request.taskBrief(),
                durableReferences,
                request.mode(),
                request.required(),
                "invoke",
                effectiveDeadline,
                budget,
                scopes,
                request.resultSchema().toDomain(assembly.config().resultSchemaId()),
                false,
                assembly.config().pollInterval(),
                assembly.config().maxPollAttempts());
        return assembly.runtime().invokeAgent(
                request.turnId().trim(), parentTaskId, parent, proposal);
    }

    @GetMapping("/{taskId}")
    public Mono<TaskStatusResponse> status(
            @PathVariable String taskId,
            ServerWebExchange exchange) {
        return Mono.fromCallable(() -> {
                    AgentTask task = ownedTask(taskId, exchange);
                    return TaskStatusResponse.from(task, providerMode());
                })
                .onErrorMap(this::mapFailure);
    }

    @PostMapping("/{taskId}/callback")
    public Mono<InvocationResponse> resume(
            @PathVariable String taskId,
            @RequestBody CallbackRequest request,
            ServerWebExchange exchange) {
        return Mono.defer(() -> {
                    AgentTask task = ownedTask(taskId, exchange);
                    if (task.status() != AgentRuntimeState.AgentStatus.WAITING_EXTERNAL
                            || task.remoteTaskId() == null) {
                        throw new IllegalStateException("task is not waiting for an external callback");
                    }
                    if (request.remoteTaskId() != null
                            && !task.remoteTaskId().equals(request.remoteTaskId().trim())) {
                        throw new AgentPolicyException("remote_task_id does not match the task");
                    }
                    AgentResult result = request.result().toDomain(
                            assembly.config().resultSchemaId(), assembly.config().agentId());
                    return assembly.runtime().resume(task.remoteTaskId(), result)
                            .map(value -> InvocationResponse.from(value, providerMode()));
                })
                .onErrorMap(this::mapFailure);
    }

    @PostMapping("/{taskId}/cancel")
    public Mono<TaskStatusResponse> cancel(
            @PathVariable String taskId,
            ServerWebExchange exchange) {
        return Mono.defer(() -> {
                    AgentTask task = ownedTask(taskId, exchange);
                    return assembly.runtime().cancelTask(task.taskId())
                            .then(Mono.fromCallable(() -> TaskStatusResponse.from(
                                    assembly.store().findTask(task.taskId()).orElseThrow(),
                                    providerMode())));
                })
                .onErrorMap(this::mapFailure);
    }

    @ExceptionHandler(AgentApiException.class)
    ResponseEntity<ErrorResponse> agentApiError(AgentApiException error) {
        return ResponseEntity.status(error.status())
                .body(new ErrorResponse(new ErrorBody(
                        error.code(), error.getMessage(), error.retryable())));
    }

    private AgentTask ownedTask(String taskId, ServerWebExchange exchange) {
        AgentTask task = assembly.store().findTask(required(taskId, "task_id"))
                .orElseThrow(() -> new AgentApiException(
                        HttpStatus.NOT_FOUND, "AGENT_TASK_NOT_FOUND",
                        "agent task was not found", false, null));
        identityVerifier.verifyScope(
                exchange,
                task.userId(),
                task.context().references().get("_client_id"),
                task.context().references().get("_session_id"));
        if (!identityVerifier.tenantId().equals(task.tenantId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "agent task tenant mismatch");
        }
        return task;
    }

    private RequestIdentity verifyRequestIdentity(
            IdentityRequest requested,
            ServerWebExchange exchange) {
        Objects.requireNonNull(requested, "identity is required");
        String userId = required(requested.userId(), "identity.user_id");
        String clientId = required(requested.clientId(), "identity.client_id");
        String sessionId = required(requested.sessionId(), "identity.session_id");
        CoreIdentityVerifier.Identity verified =
                identityVerifier.verifyScope(exchange, userId, clientId, sessionId);
        return new RequestIdentity(
                identityVerifier.tenantId(),
                required(verified.userId(), "verified user_id"),
                required(verified.clientId(), "verified client_id"),
                required(verified.sessionId(), "verified session_id"));
    }

    private Throwable mapFailure(Throwable error) {
        if (error instanceof AgentApiException || error instanceof ResponseStatusException) return error;
        if (error instanceof SecurityException) {
            return new AgentApiException(HttpStatus.FORBIDDEN, "AGENT_CAPABILITY_DENIED",
                    safeMessage(error), false, error);
        }
        if (error instanceof AgentPolicyException) {
            return new AgentApiException(HttpStatus.UNPROCESSABLE_ENTITY, "AGENT_POLICY_DENIED",
                    safeMessage(error), false, error);
        }
        if (error instanceof AgentDeadlineExceededException) {
            return new AgentApiException(HttpStatus.REQUEST_TIMEOUT, "AGENT_DEADLINE_EXCEEDED",
                    safeMessage(error), false, error);
        }
        if (error instanceof AgentCancelledException) {
            return new AgentApiException(HttpStatus.CONFLICT, "AGENT_CANCELLED",
                    safeMessage(error), false, error);
        }
        if (error instanceof AgentUnavailableException) {
            return new AgentApiException(HttpStatus.SERVICE_UNAVAILABLE, "AGENT_UNAVAILABLE",
                    safeMessage(error), true, error);
        }
        if (error instanceof IllegalArgumentException || error instanceof NullPointerException) {
            return new AgentApiException(HttpStatus.BAD_REQUEST, "INVALID_AGENT_REQUEST",
                    safeMessage(error), false, error);
        }
        if (error instanceof IllegalStateException) {
            return new AgentApiException(HttpStatus.CONFLICT, "AGENT_STATE_CONFLICT",
                    safeMessage(error), false, error);
        }
        return new AgentApiException(HttpStatus.INTERNAL_SERVER_ERROR, "AGENT_RUNTIME_ERROR",
                "agent runtime failed closed", false, error);
    }

    private String providerMode() {
        return assembly.usesInMemoryGateway() ? "IN_MEMORY_FALLBACK" : "EXTERNAL_GATEWAY";
    }

    private static String scopedParentId(RequestIdentity identity, String parentTaskId) {
        return "http/" + identity.tenantId() + "/" + identity.userId() + "/" + parentTaskId;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String safeMessage(Throwable error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? "agent request failed" : error.getMessage();
    }

    public record IdentityRequest(
            @JsonProperty("user_id") String userId,
            @JsonProperty("client_id") String clientId,
            @JsonProperty("session_id") String sessionId) {
    }

    public record BudgetRequest(
            @JsonProperty("max_tokens") long maxTokens,
            @JsonProperty("max_tool_calls") int maxToolCalls,
            @JsonProperty("max_cost") BigDecimal maxCost,
            @JsonProperty("max_depth") int maxDepth,
            @JsonProperty("max_children") int maxChildren) {
        AgentTaskContext.Budget toDomain() {
            return new AgentTaskContext.Budget(
                    maxTokens, maxToolCalls, maxCost, maxDepth, maxChildren);
        }
    }

    public record ResultSchemaRequest(
            @JsonProperty("schema_id") String schemaId,
            @JsonProperty("required_fields") Map<String, InvokeAgentProposal.ValueType> requiredFields) {
        InvokeAgentProposal.ResultSchema toDomain(String allowedSchemaId) {
            if (!allowedSchemaId.equals(required(schemaId, "result_schema.schema_id"))) {
                throw new AgentPolicyException("result schema is not registered for this agent");
            }
            Map<String, InvokeAgentProposal.ValueType> fields =
                    Map.copyOf(requiredFields == null ? Map.of() : requiredFields);
            if (fields.isEmpty()) {
                throw new IllegalArgumentException("result_schema.required_fields is required");
            }
            return new InvokeAgentProposal.ResultSchema(allowedSchemaId, fields);
        }
    }

    public record InvokeRequest(
            @JsonProperty("identity") IdentityRequest identity,
            @JsonProperty("turn_id") String turnId,
            @JsonProperty("parent_task_id") String parentTaskId,
            @JsonProperty("task_brief") String taskBrief,
            @JsonProperty("mode") InvokeAgentProposal.InvocationMode mode,
            @JsonProperty("required") boolean required,
            @JsonProperty("deadline") Instant deadline,
            @JsonProperty("budget") BudgetRequest budget,
            @JsonProperty("allowed_capabilities") Set<String> allowedCapabilities,
            @JsonProperty("references") Map<String, String> references,
            @JsonProperty("result_schema") ResultSchemaRequest resultSchema) {
        public InvokeRequest {
            Objects.requireNonNull(mode, "mode is required");
            Objects.requireNonNull(budget, "budget is required");
            Objects.requireNonNull(resultSchema, "result_schema is required");
            allowedCapabilities = Set.copyOf(
                    allowedCapabilities == null ? Set.of() : allowedCapabilities);
            references = Map.copyOf(references == null ? Map.of() : references);
            taskBrief = AgentRuntimeController.required(taskBrief, "task_brief");
        }
    }

    public record ResultRequest(
            @JsonProperty("schema_id") String schemaId,
            @JsonProperty("source_agent_id") String sourceAgentId,
            @JsonProperty("payload") Map<String, Object> payload,
            @JsonProperty("sensitive") boolean sensitive) {
        AgentResult toDomain(String allowedSchemaId, String allowedAgentId) {
            if (!allowedSchemaId.equals(required(schemaId, "result.schema_id"))) {
                throw new AgentPolicyException("callback result schema does not match");
            }
            if (!allowedAgentId.equals(required(sourceAgentId, "result.source_agent_id"))) {
                throw new AgentPolicyException("callback source agent does not match");
            }
            return new AgentResult(
                    allowedSchemaId,
                    allowedAgentId,
                    Map.copyOf(payload == null ? Map.of() : payload),
                    sensitive,
                    null);
        }
    }

    public record CallbackRequest(
            @JsonProperty("remote_task_id") String remoteTaskId,
            @JsonProperty("result") ResultRequest result) {
        public CallbackRequest {
            Objects.requireNonNull(result, "result is required");
        }
    }

    public record InvocationResponse(
            @JsonProperty("execution_id") String executionId,
            @JsonProperty("step_execution_id") String stepExecutionId,
            @JsonProperty("task_id") String taskId,
            @JsonProperty("remote_task_id") String remoteTaskId,
            @JsonProperty("status") AgentInvocation.Status status,
            @JsonProperty("result") AgentResult result,
            @JsonProperty("reason") String reason,
            @JsonProperty("provider_mode") String providerMode) {
        static InvocationResponse from(AgentInvocation value, String providerMode) {
            return new InvocationResponse(
                    value.executionId(), value.stepExecutionId(), value.taskId(),
                    value.remoteTaskId(), value.status(), value.result(), value.reason(), providerMode);
        }
    }

    public record TaskStatusResponse(
            @JsonProperty("task_id") String taskId,
            @JsonProperty("remote_task_id") String remoteTaskId,
            @JsonProperty("tenant_id") String tenantId,
            @JsonProperty("user_id") String userId,
            @JsonProperty("status") AgentRuntimeState.AgentStatus status,
            @JsonProperty("error") String error,
            @JsonProperty("version") long version,
            @JsonProperty("created_at") Instant createdAt,
            @JsonProperty("updated_at") Instant updatedAt,
            @JsonProperty("provider_mode") String providerMode) {
        static TaskStatusResponse from(AgentTask task, String providerMode) {
            return new TaskStatusResponse(
                    task.taskId(), task.remoteTaskId(), task.tenantId(), task.userId(),
                    task.status(), task.error(), task.version(), task.createdAt(),
                    task.updatedAt(), providerMode);
        }
    }

    public record ErrorResponse(@JsonProperty("error") ErrorBody error) {
    }

    public record ErrorBody(
            @JsonProperty("code") String code,
            @JsonProperty("message") String message,
            @JsonProperty("retryable") boolean retryable) {
    }

    private record RequestIdentity(
            String tenantId, String userId, String clientId, String sessionId) {
    }

    private static final class AgentApiException extends RuntimeException {
        private final HttpStatus status;
        private final String code;
        private final boolean retryable;

        private AgentApiException(
                HttpStatus status,
                String code,
                String message,
                boolean retryable,
                Throwable cause) {
            super(message, cause);
            this.status = status;
            this.code = code;
            this.retryable = retryable;
        }

        HttpStatus status() {
            return status;
        }

        String code() {
            return code;
        }

        boolean retryable() {
            return retryable;
        }
    }
}

package com.meguri.core.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.Disposable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import static com.meguri.core.agent.AgentRuntimeState.AgentStatus;
import static com.meguri.core.agent.AgentRuntimeState.SkillStatus;
import static com.meguri.core.agent.AgentRuntimeState.StepStatus;

/**
 * Assembly point for invoke_agent validation, persistence, capacity admission,
 * remote lifecycle, cancellation and durable resumption.
 */
public final class AgentRuntime implements AutoCloseable {
    private final ExecutionResourceRegistry registry;
    private final StepDispatcher dispatcher;
    private final AgentExecutionStores.SkillExecutionStore skills;
    private final AgentExecutionStores.StepExecutionStore steps;
    private final AgentExecutionStores.AgentTaskStore tasks;
    private final RemoteAgentGateway gateway;
    private final AgentResultValidator resultValidator;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final AgentLifecycleListener lifecycleListener;
    private final AtomicLong lifecycleSequence = new AtomicLong();
    private final ConcurrentHashMap<String, ExecutionResourceRegistry.CapacityLease> leases = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Disposable> cancellationWatchers = new ConcurrentHashMap<>();

    public AgentRuntime(
            ExecutionResourceRegistry registry,
            StepDispatcher dispatcher,
            AgentExecutionStores.SkillExecutionStore skills,
            AgentExecutionStores.StepExecutionStore steps,
            AgentExecutionStores.AgentTaskStore tasks,
            RemoteAgentGateway gateway,
            AgentResultValidator resultValidator,
            ObjectMapper objectMapper,
            Clock clock) {
        this(registry, dispatcher, skills, steps, tasks, gateway, resultValidator,
                objectMapper, clock, AgentLifecycleListener.noop());
    }

    public AgentRuntime(
            ExecutionResourceRegistry registry,
            StepDispatcher dispatcher,
            AgentExecutionStores.SkillExecutionStore skills,
            AgentExecutionStores.StepExecutionStore steps,
            AgentExecutionStores.AgentTaskStore tasks,
            RemoteAgentGateway gateway,
            AgentResultValidator resultValidator,
            ObjectMapper objectMapper,
            Clock clock,
            AgentLifecycleListener lifecycleListener) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.skills = Objects.requireNonNull(skills, "skills");
        this.steps = Objects.requireNonNull(steps, "steps");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.resultValidator = Objects.requireNonNull(resultValidator, "resultValidator");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.lifecycleListener = Objects.requireNonNull(lifecycleListener, "lifecycleListener");
    }

    void restoreDurableCapacity() {
        tasks.findResumable().stream()
                .sorted(java.util.Comparator
                        .comparing(AgentTask::createdAt)
                        .thenComparing(AgentTask::taskId))
                .forEach(task -> leases.put(
                        task.taskId(),
                        registry.restoreInFlight(
                                task.resourceId(),
                                task.tenantId(),
                                task.userId())));
    }

    public Mono<AgentInvocation> invokeAgent(
            String turnId, String parentTaskId, AgentTaskContext parent, InvokeAgentProposal proposal) {
        return Mono.fromCallable(() -> prepare(turnId, parentTaskId, parent, proposal))
                .flatMap(prepared -> {
                    if (!prepared.created()) return Mono.just(duplicate(prepared));
                    return admitAndSubmit(prepared)
                            .onErrorResume(AgentSkippedException.class, error -> skip(prepared, error.getMessage()));
                });
    }

    public Mono<List<AgentInvocation>> parallelAwait(
            String turnId,
            String parentTaskId,
            AgentTaskContext parent,
            List<InvokeAgentProposal> proposals,
            int maxConcurrency) {
        if (maxConcurrency < 1) return Mono.error(new IllegalArgumentException("maxConcurrency must be positive"));
        return Flux.fromIterable(List.copyOf(proposals))
                .flatMap(proposal -> {
                    if (proposal.mode() == InvokeAgentProposal.InvocationMode.DURABLE_ASYNC) {
                        return Mono.error(new AgentPolicyException("parallelAwait does not accept DURABLE_ASYNC"));
                    }
                    return invokeAgent(turnId, parentTaskId, parent, proposal);
                }, maxConcurrency)
                .collectList();
    }

    public Mono<AgentInvocation> resume(String remoteTaskId, AgentResult rawResult) {
        return Mono.defer(() -> {
            AgentTask task = tasks.findByRemoteTaskId(remoteTaskId)
                    .orElseThrow(() -> new AgentUnavailableException("unknown remote task: " + remoteTaskId));
            if (task.status() != AgentStatus.WAITING_EXTERNAL) {
                return Mono.error(new IllegalStateException("task is not waiting for durable result"));
            }
            Prepared prepared = restore(task);
            return complete(prepared, rawResult);
        });
    }

    public Mono<List<AgentInvocation>> resumeDurableTasks(int maxTasks) {
        if (maxTasks < 0) return Mono.error(new IllegalArgumentException("maxTasks must be non-negative"));
        return Flux.fromIterable(tasks.findResumable().stream().limit(maxTasks).toList())
                .concatMap(task -> {
                    Prepared prepared = restore(task);
                    return poll(prepared, 0).onErrorResume(error -> fail(prepared, error));
                })
                .collectList();
    }

    public Mono<Void> cancelTask(String taskId) {
        return Mono.defer(() -> {
            AgentTask task = tasks.findTask(taskId)
                    .orElseThrow(() -> new AgentUnavailableException("unknown task: " + taskId));
            task.context().cancellation().cancel();
            if (AgentRuntimeState.terminal(task.status())) return Mono.empty();
            Prepared prepared = restore(task);
            return cancelRemote(task).then(Mono.fromRunnable(() ->
                    terminatePrepared(prepared, AgentStatus.CANCELLED, StepStatus.CANCELLED,
                            SkillStatus.CANCELLED, "cancelled")));
        });
    }

    private synchronized Prepared prepare(
            String turnId, String parentTaskId, AgentTaskContext parent, InvokeAgentProposal proposal) {
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(proposal, "proposal");
        if (proposal.deadline().isAfter(parent.deadline())) {
            throw new AgentPolicyException("proposal deadline cannot exceed parent deadline");
        }
        if (parent.cancellation().isCancelled()) throw new AgentCancelledException("parent turn is cancelled");
        String payloadHash = hash(write(proposal));
        String scopedIdempotencyKey = parent.idempotencyKey() + "/" + proposal.idempotencySuffix().trim();
        AgentTask replay = tasks.findIdempotent(parent.tenantId(), parent.userId(), scopedIdempotencyKey)
                .orElse(null);
        if (replay != null) {
            if (!replay.payloadHash().equals(payloadHash)) {
                throw new AgentPolicyException("idempotency key reused with a different proposal");
            }
            SkillExecution existingSkill = skills.findSkill(replay.executionId())
                    .orElseThrow(() -> new IllegalStateException("idempotent skill execution is missing"));
            StepExecution existingStep = steps.findStep(replay.stepExecutionId())
                    .orElseThrow(() -> new IllegalStateException("idempotent step execution is missing"));
            return new Prepared(existingSkill, existingStep, replay, proposal, false);
        }
        List<AgentTask> children = tasks.findChildren(parentTaskId);
        if (children.size() >= parent.budget().maxChildren()) {
            throw new AgentPolicyException("maximum agent children exceeded");
        }
        requireAggregateBudget(parent.budget(), children, proposal.budget());
        java.util.Map<String, String> durableReferences = new LinkedHashMap<>(proposal.references());
        durableReferences.put("_invoke_agent_proposal", write(proposal));
        AgentTaskContext context = parent.child(new AgentTaskContext.ChildScope(
                parentTaskId,
                id("span"),
                proposal.idempotencySuffix(),
                proposal.deadline(),
                proposal.budget(),
                proposal.allowedCapabilities(),
                proposal.taskBrief(),
                durableReferences));
        ExecutionResourceRegistry.ResourceSnapshot resource = registry.selectRemoteAgent(
                proposal.agentId(), proposal.allowedCapabilities(), proposal.resultSchema().schemaId());

        Instant now = clock.instant();
        String executionId = id("skill_exec");
        String stepId = id("step_exec");
        SkillExecution skill = skills.create(new SkillExecution(
                executionId, turnId, "invoke_agent",
                context.capabilitySnapshotVersion(), SkillStatus.PENDING,
                proposal.deadline(), null, 0, now, now));
        emit(AgentLifecycleEvent.Type.SKILL_CREATED, skill, null, null, null, SkillStatus.PENDING.name(), null);
        skill = save(skill, SkillStatus.RUNNING, null);
        StepExecution step = steps.create(new StepExecution(
                stepId, executionId, "invoke_agent", ExecutionDomain.REMOTE_AGENT,
                StepStatus.PENDING, null, null, 0, now, now));
        emit(AgentLifecycleEvent.Type.STEP_CREATED, skill, step, null, null, StepStatus.PENDING.name(), null);
        AgentTask candidate = new AgentTask(
                id("agent_task"), executionId, stepId, parentTaskId,
                resource.descriptor().resourceId(), null, context.tenantId(), context.userId(),
                context.idempotencyKey(), payloadHash, AgentStatus.CREATED, context,
                null, null, 0, now, now);
        AgentExecutionStores.CreateResult created = tasks.createIdempotent(candidate);
        if (created.created()) {
            emit(AgentLifecycleEvent.Type.TASK_CREATED, skill, step, created.task(),
                    null, AgentStatus.CREATED.name(), null);
        }
        if (!created.created()) {
            save(step, StepStatus.CANCELLED, null, "concurrent idempotent replay");
            save(skill, SkillStatus.SUCCEEDED, null);
        }
        return new Prepared(skill, step, created.task(), proposal, created.created());
    }

    private static void requireAggregateBudget(
            AgentTaskContext.Budget parent,
            List<AgentTask> existingChildren,
            AgentTaskContext.Budget requested) {
        long tokens = requested.maxTokens();
        int toolCalls = requested.maxToolCalls();
        java.math.BigDecimal cost = requested.maxCost();
        try {
            for (AgentTask child : existingChildren) {
                AgentTaskContext.Budget reserved = child.context().budget();
                tokens = Math.addExact(tokens, reserved.maxTokens());
                toolCalls = Math.addExact(toolCalls, reserved.maxToolCalls());
                cost = cost.add(reserved.maxCost());
            }
        } catch (ArithmeticException overflow) {
            throw new AgentPolicyException("aggregate child budget overflow");
        }
        if (tokens > parent.maxTokens()
                || toolCalls > parent.maxToolCalls()
                || cost.compareTo(parent.maxCost()) > 0) {
            throw new AgentPolicyException(
                    "aggregate child budget cannot exceed the parent budget");
        }
    }

    private Mono<AgentInvocation> admitAndSubmit(Prepared prepared) {
        AgentTask queued = save(prepared.task(), AgentStatus.QUEUED, null, null);
        Prepared current = prepared.withTask(queued);
        ExecutionResourceRegistry.AdmissionRequest request = new ExecutionResourceRegistry.AdmissionRequest(
                queued.tenantId(), queued.userId(), queued.context().deadline(),
                prepared.proposal().required(), queued.context().cancellation());
        return registry.acquire(queued.resourceId(), request)
                .flatMap(lease -> Mono.defer(() -> {
                    leases.put(queued.taskId(), lease);
                    emit(AgentLifecycleEvent.Type.CAPACITY_ACQUIRED,
                            current.skill(), current.step(), queued, null, null, queued.resourceId());
                    try {
                        AgentTask running = save(queued, AgentStatus.RUNNING, null, null);
                        Prepared active = current.withTask(running).withLease(lease);
                        return dispatcher.dispatch(ExecutionDomain.REMOTE_AGENT,
                                        () -> gateway.submit(running, prepared.proposal()))
                                .doOnSuccess(ignored -> releaseSubmit(active))
                                .doOnError(ignored -> releaseSubmit(active))
                                .doOnCancel(() -> releaseSubmit(active))
                                .flatMap(submission -> submitted(active, submission))
                                .onErrorResume(error -> fail(active, error));
                    } catch (RuntimeException error) {
                        release(queued.taskId());
                        return Mono.error(error);
                    }
                }));
    }

    private Mono<AgentInvocation> submitted(
            Prepared prepared, RemoteAgentGateway.RemoteSubmission submission) {
        AgentTask identified = tasks.save(
                prepared.task().withRemoteTask(submission.remoteTaskId(), clock.instant()),
                prepared.task().version());
        emit(AgentLifecycleEvent.Type.REMOTE_SUBMITTED,
                prepared.skill(), prepared.step(), identified, null, null, submission.remoteTaskId());
        AgentTask waiting = save(identified, AgentStatus.WAITING_EXTERNAL, null, null);
        StepExecution step = save(prepared.step(), StepStatus.RUNNING, null, null);
        step = save(step, StepStatus.WAITING, null, null);
        SkillExecution skill = save(prepared.skill(), SkillStatus.WAITING_REMOTE_AGENT, null);
        Prepared waitingPrepared = new Prepared(skill, step, waiting, prepared.proposal(), true, prepared.lease());

        if (prepared.proposal().mode() == InvokeAgentProposal.InvocationMode.DURABLE_ASYNC) {
            watchDurableCancellation(waiting);
            emit(AgentLifecycleEvent.Type.DURABLE_WAITING,
                    skill, step, waiting, null, AgentStatus.WAITING_EXTERNAL.name(), null);
            return Mono.just(new AgentInvocation(
                    skill.executionId(), step.stepExecutionId(), waiting.taskId(), submission.remoteTaskId(),
                    AgentInvocation.Status.ACCEPTED_DURABLE, null, null));
        }
        Duration remaining = Duration.between(clock.instant(), waiting.context().deadline());
        if (remaining.isNegative() || remaining.isZero()) return timeout(waitingPrepared);
        Mono<AgentInvocation> awaited = poll(waitingPrepared, 0)
                .timeout(remaining)
                .onErrorResume(TimeoutException.class, ignored -> timeout(waitingPrepared))
                .onErrorResume(error -> fail(waitingPrepared, error));
        Mono<AgentInvocation> cancellation = waiting.context().cancellation().onCancel()
                .then(cancelRemote(waiting))
                .then(Mono.defer(() -> cancel(waitingPrepared)));
        return Mono.firstWithSignal(awaited, cancellation);
    }

    private Mono<AgentInvocation> poll(Prepared prepared, int attempt) {
        if (attempt >= prepared.proposal().maxPollAttempts()) {
            return Mono.error(new AgentDeadlineExceededException("remote agent poll attempts exhausted"));
        }
        if (!clock.instant().isBefore(prepared.task().context().deadline())) return timeout(prepared);
        return gateway.poll(prepared.task().remoteTaskId()).flatMap(status -> switch (status) {
            case SUCCEEDED -> gateway.result(prepared.task().remoteTaskId())
                    .flatMap(result -> complete(prepared, result));
            case FAILED -> Mono.error(new AgentUnavailableException("remote agent failed"));
            case CANCELLED -> cancel(prepared);
            case TIMED_OUT -> timeout(prepared);
            case QUEUED, RUNNING, WAITING_EXTERNAL -> Mono.delay(prepared.proposal().pollInterval())
                    .then(poll(prepared, attempt + 1));
        });
    }

    private Mono<AgentInvocation> complete(Prepared prepared, AgentResult rawResult) {
        return Mono.fromCallable(() -> {
            AgentResult result = resultValidator.validate(rawResult, prepared.proposal());
            emit(AgentLifecycleEvent.Type.RESULT_VALIDATED,
                    prepared.skill(), prepared.step(), prepared.task(), null, null, result.schemaId());
            SkillExecution skill = prepared.skill();
            if (skill.status() == SkillStatus.WAITING_REMOTE_AGENT) {
                skill = save(skill, SkillStatus.RESUMING, null);
            }
            StepExecution step = prepared.step();
            if (step.status() == StepStatus.WAITING) step = save(step, StepStatus.RUNNING, null, null);
            String resultJson = write(result);
            AgentTask task = save(prepared.task(), AgentStatus.SUCCEEDED, resultJson, null);
            step = save(step, StepStatus.SUCCEEDED, resultJson, null);
            skill = save(skill, SkillStatus.SUCCEEDED, null);
            release(task.taskId());
            return new AgentInvocation(skill.executionId(), step.stepExecutionId(), task.taskId(),
                    task.remoteTaskId(), AgentInvocation.Status.SUCCEEDED, result, null);
        }).onErrorResume(error -> fail(prepared, error));
    }

    private Mono<AgentInvocation> fail(Prepared prepared, Throwable error) {
        return Mono.defer(() -> {
            terminatePrepared(prepared, AgentStatus.FAILED, StepStatus.FAILED, SkillStatus.FAILED, error.getMessage());
            return Mono.error(error);
        });
    }

    private Mono<AgentInvocation> timeout(Prepared prepared) {
        return cancelRemote(prepared.task())
                .then(Mono.defer(() -> {
                    terminatePrepared(prepared, AgentStatus.TIMED_OUT, StepStatus.TIMED_OUT,
                            SkillStatus.TIMED_OUT, "deadline exceeded");
                    return Mono.error(new AgentDeadlineExceededException("agent task deadline exceeded"));
                }));
    }

    private Mono<AgentInvocation> cancel(Prepared prepared) {
        return Mono.defer(() -> {
            terminatePrepared(prepared, AgentStatus.CANCELLED, StepStatus.CANCELLED,
                    SkillStatus.CANCELLED, "cancelled");
            return Mono.error(new AgentCancelledException("agent task cancelled"));
        });
    }

    private Mono<AgentInvocation> skip(Prepared prepared, String reason) {
        return Mono.fromCallable(() -> {
            AgentTask task = tasks.findTask(prepared.task().taskId()).orElse(prepared.task());
            if (task.status() == AgentStatus.CREATED) task = save(task, AgentStatus.QUEUED, null, null);
            if (task.status() == AgentStatus.QUEUED) task = save(task, AgentStatus.CANCELLED, null, reason);
            StepExecution step = steps.findStep(prepared.step().stepExecutionId()).orElse(prepared.step());
            if (step.status() == StepStatus.PENDING) step = save(step, StepStatus.CANCELLED, null, reason);
            SkillExecution skill = skills.findSkill(prepared.skill().executionId()).orElse(prepared.skill());
            if (skill.status() == SkillStatus.RUNNING) skill = save(skill, SkillStatus.SUCCEEDED, null);
            return new AgentInvocation(skill.executionId(), step.stepExecutionId(), task.taskId(), null,
                    AgentInvocation.Status.SKIPPED, null, reason);
        });
    }

    private AgentInvocation duplicate(Prepared prepared) {
        AgentTask existing = prepared.task();
        emit(AgentLifecycleEvent.Type.IDEMPOTENT_REPLAY,
                prepared.skill(), prepared.step(), existing, null, existing.status().name(), null);
        AgentInvocation.Status status = switch (existing.status()) {
            case SUCCEEDED -> AgentInvocation.Status.SUCCEEDED;
            case FAILED -> AgentInvocation.Status.FAILED;
            case CANCELLED -> AgentInvocation.Status.CANCELLED;
            case TIMED_OUT -> AgentInvocation.Status.TIMED_OUT;
            case CREATED, QUEUED, RUNNING, WAITING_EXTERNAL ->
                    AgentInvocation.Status.ACCEPTED_DURABLE;
        };
        AgentResult result = existing.status() == AgentStatus.SUCCEEDED
                ? readResult(existing.resultJson())
                : null;
        return new AgentInvocation(existing.executionId(), existing.stepExecutionId(), existing.taskId(),
                existing.remoteTaskId(), status, result, "idempotent replay");
    }

    private AgentResult readResult(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return objectMapper.readValue(value, AgentResult.class);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("persisted agent result is invalid", error);
        }
    }

    private Prepared restore(AgentTask task) {
        SkillExecution skill = skills.findSkill(task.executionId())
                .orElseThrow(() -> new IllegalStateException("skill execution is missing"));
        StepExecution step = steps.findStep(task.stepExecutionId())
                .orElseThrow(() -> new IllegalStateException("step execution is missing"));
        InvokeAgentProposal proposal = readProposal(task);
        return new Prepared(skill, step, task, proposal, true, leases.get(task.taskId()));
    }

    private InvokeAgentProposal readProposal(AgentTask task) {
        String value = task.context().references().get("_invoke_agent_proposal");
        if (value == null) throw new IllegalStateException("durable proposal snapshot is missing");
        try {
            return objectMapper.readValue(value, InvokeAgentProposal.class);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("durable proposal snapshot is invalid", error);
        }
    }

    private void terminatePrepared(
            Prepared prepared, AgentStatus agentStatus, StepStatus stepStatus,
            SkillStatus skillStatus, String error) {
        AgentTask task = tasks.findTask(prepared.task().taskId()).orElse(prepared.task());
        if (!AgentRuntimeState.terminal(task.status())) save(task, agentStatus, null, error);
        StepExecution step = steps.findStep(prepared.step().stepExecutionId()).orElse(prepared.step());
        if (!AgentRuntimeState.terminal(step.status())) {
            if (step.status() == StepStatus.PENDING && stepStatus == StepStatus.FAILED) {
                step = save(step, StepStatus.RUNNING, null, null);
            }
            save(step, stepStatus, null, error);
        }
        SkillExecution skill = skills.findSkill(prepared.skill().executionId()).orElse(prepared.skill());
        if (!AgentRuntimeState.terminal(skill.status())) save(skill, skillStatus, error);
        release(task.taskId());
    }

    private Mono<Void> cancelRemote(AgentTask task) {
        return task.remoteTaskId() == null ? Mono.empty()
                : gateway.cancel(task.remoteTaskId()).onErrorResume(ignored -> Mono.empty());
    }

    private void release(String taskId) {
        Disposable watcher = cancellationWatchers.remove(taskId);
        if (watcher != null) watcher.dispose();
        ExecutionResourceRegistry.CapacityLease lease = leases.remove(taskId);
        if (lease != null) {
            lease.close();
            AgentTask task = tasks.findTask(taskId).orElse(null);
            emit(AgentLifecycleEvent.Type.CAPACITY_RELEASED,
                    task == null ? null : skills.findSkill(task.executionId()).orElse(null),
                    task == null ? null : steps.findStep(task.stepExecutionId()).orElse(null),
                    task, null, null, task == null ? null : task.resourceId());
        }
    }

    private void watchDurableCancellation(AgentTask task) {
        Disposable watcher = task.context().cancellation().onCancel()
                .then(cancelTask(task.taskId()))
                .onErrorResume(ignored -> Mono.empty())
                .subscribe();
        Disposable previous = cancellationWatchers.put(task.taskId(), watcher);
        if (previous != null) previous.dispose();
        if (AgentRuntimeState.terminal(
                tasks.findTask(task.taskId()).orElse(task).status())) {
            releaseCancellationWatcher(task.taskId());
        }
    }

    private void releaseCancellationWatcher(String taskId) {
        Disposable watcher = cancellationWatchers.remove(taskId);
        if (watcher != null) watcher.dispose();
    }

    @Override
    public void close() {
        cancellationWatchers.forEach((ignored, watcher) -> watcher.dispose());
        cancellationWatchers.clear();
        leases.forEach((ignored, lease) -> lease.close());
        leases.clear();
    }

    private SkillExecution save(SkillExecution current, SkillStatus next, String error) {
        SkillExecution saved = skills.save(current.transition(next, error, clock.instant()), current.version());
        emit(AgentLifecycleEvent.Type.SKILL_STATUS_CHANGED,
                saved, null, null, current.status().name(), next.name(), error);
        return saved;
    }

    private StepExecution save(StepExecution current, StepStatus next, String result, String error) {
        StepExecution saved = steps.save(current.transition(next, result, error, clock.instant()), current.version());
        emit(AgentLifecycleEvent.Type.STEP_STATUS_CHANGED,
                null, saved, null, current.status().name(), next.name(), error);
        return saved;
    }

    private AgentTask save(AgentTask current, AgentStatus next, String result, String error) {
        AgentTask saved = tasks.save(current.transition(next, result, error, clock.instant()), current.version());
        emit(AgentLifecycleEvent.Type.TASK_STATUS_CHANGED,
                null, null, saved, current.status().name(), next.name(), error);
        return saved;
    }

    private void releaseSubmit(Prepared prepared) {
        ExecutionResourceRegistry.CapacityLease lease = prepared.lease();
        if (lease == null || !lease.submitPermitHeld()) return;
        lease.releaseSubmit();
        emit(AgentLifecycleEvent.Type.SUBMIT_PERMIT_RELEASED,
                prepared.skill(), prepared.step(), prepared.task(), null, null, prepared.task().resourceId());
    }

    private synchronized void emit(
            AgentLifecycleEvent.Type type,
            SkillExecution skill,
            StepExecution step,
            AgentTask task,
            String fromStatus,
            String toStatus,
            String detail) {
        String executionId = skill != null ? skill.executionId()
                : step != null ? step.executionId()
                : task != null ? task.executionId() : null;
        String turnId = skill != null
                ? skill.turnId()
                : executionId == null
                        ? null
                        : skills.findSkill(executionId)
                                .map(SkillExecution::turnId)
                                .orElse(null);
        AgentLifecycleEvent event = new AgentLifecycleEvent(
                lifecycleSequence.incrementAndGet(),
                clock.instant(),
                type,
                turnId,
                executionId,
                step != null ? step.stepExecutionId() : task != null ? task.stepExecutionId() : null,
                task == null ? null : task.taskId(),
                task == null ? null : task.remoteTaskId(),
                fromStatus,
                toStatus,
                detail);
        try {
            lifecycleListener.onEvent(event);
        } catch (RuntimeException ignored) {
            // Event projection must not alter the persisted agent lifecycle.
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("failed to serialize agent runtime value", error);
        }
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String id(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private record Prepared(
            SkillExecution skill,
            StepExecution step,
            AgentTask task,
            InvokeAgentProposal proposal,
            boolean created,
            ExecutionResourceRegistry.CapacityLease lease) {
        private Prepared(SkillExecution skill, StepExecution step, AgentTask task,
                         InvokeAgentProposal proposal, boolean created) {
            this(skill, step, task, proposal, created, null);
        }

        private Prepared withTask(AgentTask value) {
            return new Prepared(skill, step, value, proposal, created, lease);
        }

        private Prepared withLease(ExecutionResourceRegistry.CapacityLease value) {
            return new Prepared(skill, step, task, proposal, created, value);
        }
    }
}

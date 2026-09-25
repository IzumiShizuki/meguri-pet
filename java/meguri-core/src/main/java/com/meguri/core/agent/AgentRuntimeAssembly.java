package com.meguri.core.agent;

import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

public final class AgentRuntimeAssembly implements AutoCloseable {
    private final AgentRuntime runtime;
    private final ExecutionResourceRegistry registry;
    private final StepDispatcher dispatcher;
    private final AgentExecutionStores.RuntimeStore store;
    private final RemoteAgentGateway remoteGateway;
    private final AgentRuntimeFactory.Config config;

    AgentRuntimeAssembly(
            AgentRuntime runtime,
            ExecutionResourceRegistry registry,
            StepDispatcher dispatcher,
            AgentExecutionStores.RuntimeStore store,
            RemoteAgentGateway remoteGateway,
            AgentRuntimeFactory.Config config) {
        this.runtime = runtime;
        this.registry = registry;
        this.dispatcher = dispatcher;
        this.store = store;
        this.remoteGateway = remoteGateway;
        this.config = config;
    }

    public AgentRuntime runtime() {
        return runtime;
    }

    public ExecutionResourceRegistry registry() {
        return registry;
    }

    public AgentExecutionStores.RuntimeStore store() {
        return store;
    }

    public InMemoryRemoteAgentGateway gateway() {
        if (remoteGateway instanceof InMemoryRemoteAgentGateway inMemory) return inMemory;
        throw new IllegalStateException("configured remote gateway is not the in-memory fallback");
    }

    public RemoteAgentGateway remoteGateway() {
        return remoteGateway;
    }

    public boolean usesInMemoryGateway() {
        return remoteGateway instanceof InMemoryRemoteAgentGateway;
    }

    public AgentRuntimeFactory.Config config() {
        return config;
    }

    public AgentTaskContext rootContext(
            String tenantId,
            String userId,
            String turnId,
            Instant deadline,
            AgentTaskContext.Budget budget,
            String taskBrief) {
        return new AgentTaskContext(
                tenantId,
                userId,
                null,
                "trace-" + turnId,
                "span-" + turnId,
                turnId,
                deadline,
                "capability:" + turnId,
                new CancellationToken(),
                budget,
                0,
                config.allowedCapabilities(),
                taskBrief,
                Map.of());
    }

    public AgentTaskContext rootContext(
            String tenantId, String userId, String turnId, Instant deadline, String taskBrief) {
        return rootContext(
                tenantId,
                userId,
                turnId,
                deadline,
                new AgentTaskContext.Budget(
                        16_000,
                        16,
                        new BigDecimal("10.00"),
                        4,
                        8),
                taskBrief);
    }

    public InvokeAgentProposal proposal(
            String taskBrief,
            InvokeAgentProposal.InvocationMode mode,
            String idempotencySuffix,
            Instant deadline) {
        return new InvokeAgentProposal(
                config.agentId(),
                taskBrief,
                Map.of(),
                mode,
                true,
                idempotencySuffix,
                deadline,
                new AgentTaskContext.Budget(4_000, 4, new BigDecimal("2.00"), 4, 2),
                config.allowedCapabilities(),
                new InvokeAgentProposal.ResultSchema(
                        config.resultSchemaId(),
                        Map.of("answer", InvokeAgentProposal.ValueType.STRING)),
                false,
                config.pollInterval(),
                config.maxPollAttempts());
    }

    public Mono<AgentInvocation> completeAndResume(String remoteTaskId) {
        InMemoryRemoteAgentGateway inMemory = gateway();
        return Mono.fromRunnable(() -> inMemory.complete(remoteTaskId))
                .then(inMemory.result(remoteTaskId))
                .flatMap(result -> runtime.resume(remoteTaskId, result));
    }

    public Mono<AgentInvocation> completeAndResume(String remoteTaskId, AgentResult result) {
        InMemoryRemoteAgentGateway inMemory = gateway();
        return Mono.fromRunnable(() -> inMemory.complete(remoteTaskId, result))
                .then(runtime.resume(remoteTaskId, result));
    }

    public Mono<Void> cancel(String taskId) {
        return runtime.cancelTask(taskId);
    }

    @Override
    public void close() {
        runtime.close();
        dispatcher.close();
    }
}

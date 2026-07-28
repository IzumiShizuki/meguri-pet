package com.meguri.core.agent;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;

public final class AgentRuntimeFactory {
    public static final String DEFAULT_RESOURCE_ID = "local-remote-agent";
    public static final String DEFAULT_AGENT_ID = "local-agent";
    public static final String DEFAULT_RESULT_SCHEMA_ID = "local-result-v1";

    private AgentRuntimeFactory() {
    }

    public static AgentRuntimeAssembly createDefault() {
        return create(Config.defaults(), AgentLifecycleListener.noop(), Clock.systemUTC());
    }

    public static AgentRuntimeAssembly createDefault(AgentLifecycleListener listener) {
        return create(Config.defaults(), listener, Clock.systemUTC());
    }

    public static AgentRuntimeAssembly create(
            Config config, AgentLifecycleListener listener, Clock clock) {
        InMemoryRemoteAgentGateway gateway = new InMemoryRemoteAgentGateway(
                config.submitDelay(), config.pollsBeforeSuccess());
        return create(config, listener, clock, gateway);
    }

    public static AgentRuntimeAssembly create(
            Config config,
            AgentLifecycleListener listener,
            Clock clock,
            RemoteAgentGateway gateway) {
        return create(
                config,
                listener,
                clock,
                gateway,
                new InMemoryAgentRuntimeStore());
    }

    public static AgentRuntimeAssembly create(
            Config config,
            AgentLifecycleListener listener,
            Clock clock,
            RemoteAgentGateway gateway,
            AgentExecutionStores.RuntimeStore store) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(listener, "listener");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(gateway, "gateway");
        Objects.requireNonNull(store, "store");

        ExecutionResourceRegistry registry = new ExecutionResourceRegistry(clock, config.admissionRetryInterval());
        registry.register(new ExecutionResourceRegistry.ResourceDescriptor(
                config.resourceId(),
                ExecutionDomain.REMOTE_AGENT,
                config.submitMaxConcurrency(),
                config.maxInFlightTasks(),
                config.maxQueueSize(),
                config.maxTenantInFlight(),
                config.maxUserInFlight(),
                config.agentId(),
                config.allowedCapabilities(),
                Set.of(config.resultSchemaId())));

        StepDispatcher dispatcher = new StepDispatcher(
                config.blockingThreads(),
                config.cpuThreads(),
                config.providerThreads(),
                config.backgroundThreads());
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        AgentRuntime runtime = new AgentRuntime(
                registry,
                dispatcher,
                store,
                store,
                store,
                gateway,
                new AgentResultValidator(),
                mapper,
                clock,
                listener);
        runtime.restoreDurableCapacity();
        return new AgentRuntimeAssembly(runtime, registry, dispatcher, store, gateway, config);
    }

    public record Config(
            String resourceId,
            String agentId,
            String resultSchemaId,
            Set<String> allowedCapabilities,
            int submitMaxConcurrency,
            int maxInFlightTasks,
            int maxQueueSize,
            int maxTenantInFlight,
            int maxUserInFlight,
            int blockingThreads,
            int cpuThreads,
            int providerThreads,
            int backgroundThreads,
            Duration submitDelay,
            int pollsBeforeSuccess,
            Duration pollInterval,
            int maxPollAttempts,
            Duration admissionRetryInterval) {

        public Config {
            resourceId = required(resourceId, "resourceId");
            agentId = required(agentId, "agentId");
            resultSchemaId = required(resultSchemaId, "resultSchemaId");
            allowedCapabilities = Set.copyOf(
                    allowedCapabilities == null ? Set.of() : allowedCapabilities);
            if (submitMaxConcurrency < 1 || maxInFlightTasks < 1 || maxQueueSize < 0
                    || maxTenantInFlight < 1 || maxUserInFlight < 1
                    || blockingThreads < 1 || cpuThreads < 1
                    || providerThreads < 1 || backgroundThreads < 1
                    || pollsBeforeSuccess < 0 || maxPollAttempts < 1) {
                throw new IllegalArgumentException("agent runtime factory capacities are invalid");
            }
            submitDelay = nonNegative(submitDelay, "submitDelay");
            pollInterval = positive(pollInterval, "pollInterval");
            admissionRetryInterval = positive(admissionRetryInterval, "admissionRetryInterval");
        }

        public static Config defaults() {
            return new Config(
                    DEFAULT_RESOURCE_ID,
                    DEFAULT_AGENT_ID,
                    DEFAULT_RESULT_SCHEMA_ID,
                    Set.of("local"),
                    2,
                    8,
                    32,
                    8,
                    4,
                    2,
                    Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors())),
                    2,
                    1,
                    Duration.ZERO,
                    1,
                    Duration.ofMillis(10),
                    100,
                    Duration.ofMillis(10));
        }

        private static String required(String value, String field) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
            return value.trim();
        }

        private static Duration nonNegative(Duration value, String field) {
            if (value == null || value.isNegative()) {
                throw new IllegalArgumentException(field + " must be non-negative");
            }
            return value;
        }

        private static Duration positive(Duration value, String field) {
            if (value == null || value.isNegative() || value.isZero()) {
                throw new IllegalArgumentException(field + " must be positive");
            }
            return value;
        }
    }
}

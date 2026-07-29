package com.meguri.core.capability;

import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Migration adapter that invokes one existing Gateway operation exactly once. */
public final class ExistingGatewayAdapter implements CapabilityImplementation {
    private final GatewayInvocation invocation;
    private final String provider;
    private final CapabilityDescriptor.ResultTrust trust;

    public ExistingGatewayAdapter(
            String provider,
            CapabilityDescriptor.ResultTrust trust,
            GatewayInvocation invocation) {
        this.provider = required(provider, "provider");
        this.trust = Objects.requireNonNull(trust, "trust");
        this.invocation = Objects.requireNonNull(invocation, "invocation");
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> input, ExecutionContext context) {
        AtomicBoolean subscribed = new AtomicBoolean();
        Mono<Map<String, Object>> operation = Objects.requireNonNull(
                invocation.invoke(input == null ? Map.of() : input, context),
                "gateway invocation returned null");
        Map<String, Object> value = operation.doOnSubscribe(ignored -> {
            if (!subscribed.compareAndSet(false, true)) {
                throw new IllegalStateException("legacy gateway operation subscribed more than once");
            }
        }).block();
        if (value == null) throw new IllegalStateException("legacy gateway returned no result");
        return Map.of(
                "data", ToolProposal.immutableMap(value),
                "source", Map.of("provider", provider, "trust", trust.name()));
    }

    public static CapabilityDescriptor descriptor(
            String id,
            String version,
            CapabilityDescriptor.Kind kind,
            CapabilityDescriptor.SideEffect sideEffect,
            CapabilityDescriptor.Schema input,
            CapabilityDescriptor.Schema output,
            CapabilityDescriptor.ApprovalRequirement approval,
            CapabilityDescriptor.ResultTrust trust,
            boolean network,
            boolean idempotentWrite) {
        return new CapabilityDescriptor(
                id, version, kind, "legacy-gateway-migration", input, output, java.util.Set.of(),
                sideEffect, approval, Duration.ofSeconds(15), CapabilityDescriptor.RetryPolicy.none(),
                new CapabilityDescriptor.ConcurrencyPolicy(4),
                java.util.Set.of(CapabilityDescriptor.Mode.BALANCED, CapabilityDescriptor.Mode.DEEP),
                CapabilityDescriptor.DataClassification.INTERNAL, trust,
                "existing-gateway:" + id, CapabilityDescriptor.Health.HEALTHY, false, 1,
                network ? new CapabilityDescriptor.NetworkPolicy(true, java.util.Set.of())
                        : CapabilityDescriptor.NetworkPolicy.denied(),
                java.util.Set.of(), CapabilityDescriptor.CostPolicy.free(),
                CapabilityDescriptor.CachePolicy.disabled(),
                idempotentWrite
                        ? new CapabilityDescriptor.IdempotencyPolicy(true, true)
                        : CapabilityDescriptor.IdempotencyPolicy.none());
    }

    @FunctionalInterface
    public interface GatewayInvocation {
        Mono<Map<String, Object>> invoke(Map<String, Object> input, ExecutionContext context);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}

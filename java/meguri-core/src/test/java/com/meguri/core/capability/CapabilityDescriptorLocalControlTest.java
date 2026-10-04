package com.meguri.core.capability;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityDescriptorLocalControlTest {

    @Test
    void acceptsOnlyScopedClosedLoopbackOnlyLocalControls() {
        localControl(
                CapabilityDescriptor.SideEffect.EXTERNAL,
                CapabilityDescriptor.ApprovalRequirement.NONE,
                Set.of("local-app:control"),
                CapabilityDescriptor.NetworkPolicy.denied(),
                CapabilityDescriptor.Schema.empty(),
                Set.of(),
                CapabilityDescriptor.IdempotencyPolicy.none());

        assertThatThrownBy(() -> localControl(
                CapabilityDescriptor.SideEffect.WRITE,
                CapabilityDescriptor.ApprovalRequirement.NONE,
                Set.of("local-app:control"),
                CapabilityDescriptor.NetworkPolicy.denied(),
                CapabilityDescriptor.Schema.empty(),
                Set.of(),
                CapabilityDescriptor.IdempotencyPolicy.none()))
                .hasMessageContaining("EXTERNAL side effect");
        assertThatThrownBy(() -> localControl(
                CapabilityDescriptor.SideEffect.EXTERNAL,
                CapabilityDescriptor.ApprovalRequirement.RISK_BASED,
                Set.of("local-app:control"),
                CapabilityDescriptor.NetworkPolicy.denied(),
                CapabilityDescriptor.Schema.empty(),
                Set.of(),
                CapabilityDescriptor.IdempotencyPolicy.none()))
                .hasMessageContaining("secondary approval");
        assertThatThrownBy(() -> localControl(
                CapabilityDescriptor.SideEffect.EXTERNAL,
                CapabilityDescriptor.ApprovalRequirement.NONE,
                Set.of(),
                CapabilityDescriptor.NetworkPolicy.denied(),
                CapabilityDescriptor.Schema.empty(),
                Set.of(),
                CapabilityDescriptor.IdempotencyPolicy.none()))
                .hasMessageContaining("server-authorized scope");
        assertThatThrownBy(() -> localControl(
                CapabilityDescriptor.SideEffect.EXTERNAL,
                CapabilityDescriptor.ApprovalRequirement.NONE,
                Set.of("local-app:control"),
                new CapabilityDescriptor.NetworkPolicy(true, Set.of("127.0.0.1")),
                CapabilityDescriptor.Schema.empty(),
                Set.of(),
                CapabilityDescriptor.IdempotencyPolicy.none()))
                .hasMessageContaining("network access");
        assertThatThrownBy(() -> localControl(
                CapabilityDescriptor.SideEffect.EXTERNAL,
                CapabilityDescriptor.ApprovalRequirement.NONE,
                Set.of("local-app:control"),
                CapabilityDescriptor.NetworkPolicy.denied(),
                new CapabilityDescriptor.Schema(Map.of(), Set.of(), true, Set.of()),
                Set.of(),
                CapabilityDescriptor.IdempotencyPolicy.none()))
                .hasMessageContaining("closed input schema");
        assertThatThrownBy(() -> localControl(
                CapabilityDescriptor.SideEffect.EXTERNAL,
                CapabilityDescriptor.ApprovalRequirement.NONE,
                Set.of("local-app:control"),
                CapabilityDescriptor.NetworkPolicy.denied(),
                CapabilityDescriptor.Schema.empty(),
                Set.of("token"),
                CapabilityDescriptor.IdempotencyPolicy.none()))
                .hasMessageContaining("configured secrets");
    }

    private static CapabilityDescriptor localControl(
            CapabilityDescriptor.SideEffect sideEffect,
            CapabilityDescriptor.ApprovalRequirement approval,
            Set<String> scopes,
            CapabilityDescriptor.NetworkPolicy network,
            CapabilityDescriptor.Schema input,
            Set<String> secrets,
            CapabilityDescriptor.IdempotencyPolicy idempotency) {
        return new CapabilityDescriptor(
                "local.test.control", "1", CapabilityDescriptor.Kind.LOCAL_CONTROL_TOOL,
                "tests", input, new CapabilityDescriptor.Schema(
                        Map.of(), Set.of(), true, Set.of()), scopes,
                sideEffect, approval, Duration.ofSeconds(2),
                CapabilityDescriptor.RetryPolicy.none(),
                new CapabilityDescriptor.ConcurrencyPolicy(1),
                Set.of(CapabilityDescriptor.Mode.DEEP),
                CapabilityDescriptor.DataClassification.INTERNAL,
                CapabilityDescriptor.ResultTrust.TRUSTED_LOCAL,
                "test://local-control", CapabilityDescriptor.Health.HEALTHY,
                false, 1, network, secrets, CapabilityDescriptor.CostPolicy.free(),
                CapabilityDescriptor.CachePolicy.disabled(), idempotency);
    }
}

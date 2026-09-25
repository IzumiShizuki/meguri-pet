package com.meguri.core.react;

import com.meguri.core.capability.CapabilityDescriptor;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Planner-safe view of a capability exposed to one frozen ReAct turn.
 *
 * <p>It intentionally omits implementation references, required secret names,
 * scopes, and network hosts. The planner only needs enough information to
 * choose a capability and construct schema-valid arguments.</p>
 */
public record ReactCapability(
        String id,
        String version,
        CapabilityDescriptor.Kind kind,
        Map<String, CapabilityDescriptor.ValueType> inputProperties,
        Set<String> requiredInputs,
        boolean additionalInputProperties,
        CapabilityDescriptor.SideEffect sideEffect,
        CapabilityDescriptor.ApprovalRequirement approval,
        CapabilityDescriptor.Health health,
        boolean deprecated,
        boolean idempotencySupported,
        boolean idempotencyRequired,
        long estimatedCostUnits) {

    public ReactCapability {
        id = ReactValues.required(id, "id");
        version = ReactValues.required(version, "version");
        kind = Objects.requireNonNull(kind, "kind");
        inputProperties = inputProperties == null ? Map.of() : Map.copyOf(inputProperties);
        requiredInputs = requiredInputs == null ? Set.of() : Set.copyOf(requiredInputs);
        sideEffect = Objects.requireNonNull(sideEffect, "sideEffect");
        approval = Objects.requireNonNull(approval, "approval");
        health = Objects.requireNonNull(health, "health");
        if (estimatedCostUnits < 0) {
            throw new IllegalArgumentException("estimatedCostUnits must not be negative");
        }
    }

    public static ReactCapability from(CapabilityDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        CapabilityDescriptor.Schema schema = descriptor.inputSchema();
        return new ReactCapability(
                descriptor.id(), descriptor.version(), descriptor.kind(),
                schema.properties(), schema.required(), schema.additionalProperties(),
                descriptor.sideEffect(), descriptor.approval(), descriptor.health(),
                descriptor.deprecated(), descriptor.idempotency().supported(),
                descriptor.idempotency().required(), descriptor.cost().estimatedUnits());
    }

    public boolean promptSkill() {
        return kind == CapabilityDescriptor.Kind.PROMPT_SKILL;
    }
}

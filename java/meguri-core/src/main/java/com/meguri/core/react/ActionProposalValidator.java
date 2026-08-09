package com.meguri.core.react;

import com.meguri.core.capability.CapabilityDescriptor;

import java.util.List;
import java.util.Objects;

/** Fail-closed validator over the descriptors exposed in the frozen snapshot. */
public final class ActionProposalValidator {

    public ValidationResult validate(
            ReactAction action,
            List<CapabilityDescriptor> exposedCapabilities,
            long remainingCostUnits) {
        Objects.requireNonNull(action, "action");
        CapabilityDescriptor descriptor = exposedCapabilities.stream()
                .filter(candidate -> candidate.id().equals(action.capabilityId()))
                .findFirst()
                .orElse(null);
        if (descriptor == null) return ValidationResult.rejected("CAPABILITY_NOT_EXPOSED");
        if (descriptor.deprecated()) return ValidationResult.rejected("CAPABILITY_DEPRECATED");
        if (descriptor.health() == CapabilityDescriptor.Health.UNHEALTHY) {
            return ValidationResult.rejected("CAPABILITY_UNHEALTHY");
        }
        if (descriptor.kind() != CapabilityDescriptor.Kind.READ_TOOL
                && descriptor.kind() != CapabilityDescriptor.Kind.RESOURCE) {
            return ValidationResult.rejected("READ_ONLY_MVP_KIND_REJECTED");
        }
        if (descriptor.sideEffect() != CapabilityDescriptor.SideEffect.NONE
                && descriptor.sideEffect() != CapabilityDescriptor.SideEffect.READ) {
            return ValidationResult.rejected("READ_ONLY_MVP_SIDE_EFFECT_REJECTED");
        }
        if (descriptor.approval() != CapabilityDescriptor.ApprovalRequirement.NONE) {
            return ValidationResult.rejected("READ_ONLY_MVP_APPROVAL_REJECTED");
        }
        if (descriptor.cost().estimatedUnits() > remainingCostUnits) {
            return ValidationResult.rejected("COST_BUDGET_INSUFFICIENT");
        }
        try {
            descriptor.inputSchema().validate(action.arguments(), "react action input");
        } catch (CapabilityDescriptor.SchemaViolation invalid) {
            return ValidationResult.rejected("INPUT_SCHEMA_INVALID");
        }
        return ValidationResult.accepted(
                new ValidatedReactAction(action, descriptor, action.digest()));
    }

    public record ValidationResult(
            boolean accepted,
            String reasonCode,
            ValidatedReactAction action) {

        private static ValidationResult accepted(ValidatedReactAction action) {
            return new ValidationResult(true, "ACTION_VALIDATED", action);
        }

        private static ValidationResult rejected(String reasonCode) {
            return new ValidationResult(false, reasonCode, null);
        }
    }
}

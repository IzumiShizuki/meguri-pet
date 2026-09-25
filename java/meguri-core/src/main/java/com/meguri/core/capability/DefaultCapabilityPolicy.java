package com.meguri.core.capability;

public final class DefaultCapabilityPolicy implements CapabilityPolicy {
    @Override
    public Decision canExpose(CapabilityDescriptor descriptor, ExposurePlanner.ExposureContext context) {
        if (!descriptor.allowedModes().contains(context.mode())) return Decision.deny("MODE_DENIED");
        if (descriptor.health() == CapabilityDescriptor.Health.UNHEALTHY) return Decision.deny("UNHEALTHY");
        if (descriptor.deprecated()) return Decision.deny("DEPRECATED");
        if (descriptor.minimumProtocol() > context.protocolVersion()) return Decision.deny("PROTOCOL_UNSUPPORTED");
        if (!context.scopes().containsAll(descriptor.scopes())) return Decision.deny("SCOPE_DENIED");
        if (descriptor.network().allowed() && !context.networkAllowed()) return Decision.deny("NETWORK_DENIED");
        if (descriptor.dataClassification().ordinal() > context.maximumDataClassification().ordinal()) {
            return Decision.deny("DATA_CLASSIFICATION_DENIED");
        }
        if (!context.intentCapabilities().isEmpty() && !context.intentCapabilities().contains(descriptor.id())) {
            return Decision.deny("INTENT_MISMATCH");
        }
        return Decision.allow();
    }

    @Override
    public Decision canExecute(CapabilityDescriptor descriptor, ToolProposal proposal) {
        if (!proposal.scopes().containsAll(descriptor.scopes())) return Decision.deny("SCOPE_DENIED");
        if (proposal.maximumCostUnits() < descriptor.cost().estimatedUnits()) return Decision.deny("COST_DENIED");
        if (descriptor.network().allowed() && !proposal.networkAllowed()) return Decision.deny("NETWORK_DENIED");
        return Decision.allow();
    }
}

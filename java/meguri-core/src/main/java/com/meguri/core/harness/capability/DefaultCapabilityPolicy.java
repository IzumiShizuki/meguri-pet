package com.meguri.core.harness.capability;

import com.meguri.core.harness.retrieval.RetrievalMode;

/** Fail-closed local policy for approvals and bounded remote-agent execution. */
public final class DefaultCapabilityPolicy implements CapabilityPolicy {
    @Override
    public Decision authorize(CapabilityRegistry.Descriptor descriptor, ExecutionRequest request) {
        if (request.retrievalMode() == RetrievalMode.NONE
                && isRetrievalCapability(descriptor)) {
            return Decision.deny("retrieval mode NONE denies retrieval capabilities");
        }
        if (request.retrievalMode() == RetrievalMode.FAST
                && requiresOpenRetrieval(descriptor)) {
            return Decision.deny("retrieval mode FAST denies Web and remote/open capabilities");
        }
        if (descriptor.kind() == CapabilityRegistry.Kind.PROMPT_SKILL
                || descriptor.kind() == CapabilityRegistry.Kind.RESOURCE) {
            return Decision.deny("capability kind is not executable as a tool");
        }
        if (descriptor.approval() == CapabilityRegistry.Approval.ALWAYS
                && !request.approvalGranted()) {
            return Decision.deny("explicit approval is required");
        }
        if (descriptor.approval() == CapabilityRegistry.Approval.RISK_BASED
                && !request.approvalGranted()) {
            return Decision.deny("risk-based capability approval is required");
        }
        if (descriptor.kind() == CapabilityRegistry.Kind.REMOTE_AGENT) {
            SandboxBudget sandbox = request.sandbox();
            if (!request.approvalGranted()) return Decision.deny("remote agents require explicit approval");
            if (sandbox == null) return Decision.deny("remote agents require a sandbox budget");
            if (sandbox.wallTime().compareTo(descriptor.timeout()) > 0) {
                return Decision.deny("sandbox wall time exceeds the capability timeout");
            }
            if (sandbox.processExecutionAllowed() && sandbox.allowedRoots().isEmpty()) {
                return Decision.deny(
                        "remote agents with process execution require a filesystem allow-list");
            }
        }
        return Decision.allow("descriptor, approval, and sandbox policy passed");
    }

    private static boolean isRetrievalCapability(CapabilityRegistry.Descriptor descriptor) {
        return descriptor.kind() == CapabilityRegistry.Kind.READ_TOOL
                || descriptor.kind() == CapabilityRegistry.Kind.REMOTE_AGENT;
    }

    private static boolean requiresOpenRetrieval(CapabilityRegistry.Descriptor descriptor) {
        return descriptor.kind() == CapabilityRegistry.Kind.REMOTE_AGENT
                || descriptor.effect() == CapabilityRegistry.Effect.EXTERNAL
                || descriptor.id().equals("web.read")
                || descriptor.id().startsWith("web.");
    }
}

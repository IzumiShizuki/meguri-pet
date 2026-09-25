package com.meguri.core.execution;

/** Server-side gates; none may be widened by model or client output. */
public record ExecutionModeAvailability(
        boolean thinkEnabled,
        boolean agentEnabled,
        boolean readOnlyCapabilitiesAvailable,
        boolean remoteAgentsAvailable) {
}

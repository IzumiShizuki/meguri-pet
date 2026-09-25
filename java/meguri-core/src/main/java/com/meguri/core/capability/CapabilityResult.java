package com.meguri.core.capability;

import java.util.List;
import java.util.Map;

public record CapabilityResult(
        Status status,
        String errorCode,
        Map<String, Object> data,
        String display,
        Source source,
        List<String> warnings,
        boolean retryable) {

    public enum Status { SUCCESS, FAILED, UNKNOWN_OUTCOME }

    public CapabilityResult {
        data = ToolProposal.immutableMap(data);
        display = display == null ? "" : display;
        source = source == null ? new Source("runtime", CapabilityDescriptor.ResultTrust.TRUSTED_LOCAL) : source;
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public static CapabilityResult failure(String code, boolean retryable) {
        return new CapabilityResult(Status.FAILED, code, Map.of(), "", null, List.of(), retryable);
    }

    public record Source(String provider, CapabilityDescriptor.ResultTrust trust) {
        public Source {
            provider = CapabilityDescriptor.required(provider, "provider");
            if (trust == null) throw new IllegalArgumentException("trust is required");
        }
    }
}

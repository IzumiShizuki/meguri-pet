package com.meguri.core.capability;

import java.util.List;
import java.util.Map;
import java.util.Set;

public interface McpAdapter {
    Negotiation negotiate(int maximumProtocol, Set<String> supportedCapabilities);
    List<Map<String, Object>> listTools();
    Map<String, Object> invoke(
            String toolName, Map<String, Object> input, CapabilityImplementation.ExecutionContext context)
            throws Exception;
    void onListChanged(Runnable listener);

    record Negotiation(int protocolVersion, Set<String> capabilities) {
        public Negotiation {
            if (protocolVersion < 1) throw new IllegalArgumentException("protocolVersion must be positive");
            capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        }
    }
}

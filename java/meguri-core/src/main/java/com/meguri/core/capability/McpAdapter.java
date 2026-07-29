package com.meguri.core.capability;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

public interface McpAdapter extends AutoCloseable {
    Negotiation negotiate(int maximumProtocol, Set<String> supportedCapabilities);
    List<Map<String, Object>> listTools();
    default List<Map<String, Object>> listPrompts() { return List.of(); }
    default List<Map<String, Object>> listResources() { return List.of(); }
    Map<String, Object> invoke(
            String toolName, Map<String, Object> input, CapabilityImplementation.ExecutionContext context)
            throws Exception;
    default List<Map<String, Object>> getPrompt(String promptName, Map<String, Object> arguments) {
        throw new UnsupportedOperationException("MCP prompts are not supported by this adapter");
    }
    default List<Map<String, Object>> readResource(String uri) {
        throw new UnsupportedOperationException("MCP resources are not supported by this adapter");
    }
    void onListChanged(Runnable listener);
    default void startListChangeMonitoring(Duration pollingInterval) { }
    default boolean pollForListChanges() { return false; }
    @Override default void close() { }

    record Negotiation(int protocolVersion, Set<String> capabilities) {
        public Negotiation {
            if (protocolVersion < 1) throw new IllegalArgumentException("protocolVersion must be positive");
            capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        }
    }
}

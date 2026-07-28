package com.meguri.core.capability;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class McpCapabilitySynchronizer {
    private final String server;
    private final McpAdapter adapter;
    private final CapabilityCatalog catalog;
    private final CapabilityRegistry registry;
    private final McpCapabilityNormalizer normalizer;
    private final Lifecycle lifecycle;
    private final Map<String, String> knownVersions = new LinkedHashMap<>();
    private boolean active = true;

    public McpCapabilitySynchronizer(
            String server,
            McpAdapter adapter,
            CapabilityCatalog catalog,
            CapabilityRegistry registry,
            McpCapabilityNormalizer normalizer) {
        this(server, adapter, catalog, registry, normalizer, new Lifecycle() {
            @Override
            public void activateAll(List<PreparedCapability> capabilities) {
                capabilities.forEach(capability -> {
                    catalog.register(capability.descriptor(), capability.implementation());
                    registry.activate(
                            capability.descriptor().id(), capability.descriptor().version());
                });
            }

            @Override
            public void drain(String capabilityId) {
                registry.drain(capabilityId);
            }

            @Override
            public void retire(String capabilityId, String version) {
                // The standalone registry has no in-flight reference tracker.
            }
        });
    }

    public McpCapabilitySynchronizer(
            String server,
            McpAdapter adapter,
            CapabilityCatalog catalog,
            CapabilityRegistry registry,
            McpCapabilityNormalizer normalizer,
            Lifecycle lifecycle) {
        this.server = server;
        this.adapter = adapter;
        this.catalog = catalog;
        this.registry = registry;
        this.normalizer = normalizer;
        this.lifecycle = lifecycle;
    }

    public McpAdapter.Negotiation start(int maximumProtocol) {
        McpAdapter.Negotiation negotiation = adapter.negotiate(maximumProtocol, Set.of("tools", "list_changed"));
        refresh(negotiation.protocolVersion());
        adapter.onListChanged(() -> {
            synchronized (this) {
                if (!active) return;
            }
            refresh(negotiation.protocolVersion());
        });
        return negotiation;
    }

    public synchronized void refresh(int protocolVersion) {
        if (!active) {
            throw new IllegalStateException("MCP source is no longer active");
        }
        List<PreparedTool> prepared = new ArrayList<>();
        Map<String, String> refreshed = new LinkedHashMap<>();
        for (Map<String, Object> raw : adapter.listTools()) {
            CapabilityDescriptor descriptor = normalizer.normalize(server, raw, protocolVersion);
            String toolName = String.valueOf(raw.get("name"));
            CapabilityImplementation implementation =
                    (input, context) -> invokePinned(
                            descriptor.id(), descriptor.version(), toolName, input, context);
            if (refreshed.putIfAbsent(descriptor.id(), descriptor.version()) != null) {
                throw new IllegalArgumentException("duplicate MCP capability: " + descriptor.id());
            }
            prepared.add(new PreparedTool(descriptor, implementation));
        }
        lifecycle.activateAll(prepared.stream()
                .map(tool -> new PreparedCapability(
                        tool.descriptor(), tool.implementation()))
                .toList());
        knownVersions.forEach((id, version) -> {
            String next = refreshed.get(id);
            if (next != null && !version.equals(next)) {
                lifecycle.retire(id, version);
            }
        });
        for (PreparedTool tool : prepared) {
            CapabilityDescriptor descriptor = tool.descriptor();
            refreshed.put(descriptor.id(), descriptor.version());
        }
        knownVersions.keySet().stream()
                .filter(id -> !refreshed.containsKey(id))
                .forEach(lifecycle::drain);
        knownVersions.clear();
        knownVersions.putAll(refreshed);
        // Existing ExposurePlans retain their frozen grants; this binding change is visible only to later snapshots.
    }

    public synchronized Map<String, String> knownVersions() {
        return Map.copyOf(knownVersions);
    }

    public synchronized void deactivate() {
        active = false;
    }

    private synchronized Map<String, Object> invokePinned(
            String capabilityId,
            String version,
            String toolName,
            Map<String, Object> input,
            CapabilityImplementation.ExecutionContext context) throws Exception {
        if (!active || !version.equals(knownVersions.get(capabilityId))) {
            throw new IllegalStateException(
                    "MCP capability version is no longer active");
        }
        return adapter.invoke(toolName, input, context);
    }

    public interface Lifecycle {
        void activateAll(List<PreparedCapability> capabilities);
        void drain(String capabilityId);
        void retire(String capabilityId, String version);
    }

    public record PreparedCapability(
            CapabilityDescriptor descriptor,
            CapabilityImplementation implementation) { }

    private record PreparedTool(
            CapabilityDescriptor descriptor,
            CapabilityImplementation implementation) { }
}

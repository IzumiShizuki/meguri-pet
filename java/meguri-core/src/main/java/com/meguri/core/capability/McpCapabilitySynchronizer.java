package com.meguri.core.capability;

import java.time.Duration;
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
    private Map<String, PromptDefinition> prompts = Map.of();
    private Map<String, ResourceDefinition> resources = Map.of();
    private Set<String> negotiatedCapabilities = Set.of("tools");
    private boolean active = true;
    private boolean refreshing;
    private boolean refreshPending;

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
        McpAdapter.Negotiation negotiation = adapter.negotiate(
                maximumProtocol, Set.of("tools", "prompts", "resources", "list_changed"));
        negotiatedCapabilities = negotiation.capabilities();
        refresh(negotiation.protocolVersion());
        adapter.onListChanged(() -> {
            synchronized (this) {
                if (!active) return;
            }
            refresh(negotiation.protocolVersion());
        });
        adapter.startListChangeMonitoring(Duration.ofSeconds(30));
        return negotiation;
    }

    public synchronized void refresh(int protocolVersion) {
        if (!active) {
            throw new IllegalStateException("MCP source is no longer active");
        }
        if (refreshing) {
            refreshPending = true;
            return;
        }
        refreshing = true;
        try {
            refreshPending = false;
            refreshOnce(protocolVersion);
            if (refreshPending) {
                refreshPending = false;
                refreshOnce(protocolVersion);
            }
        } finally {
            refreshing = false;
        }
    }

    private void refreshOnce(int protocolVersion) {
        List<PreparedTool> prepared = new ArrayList<>();
        Map<String, String> refreshed = new LinkedHashMap<>();
        List<Map<String, Object>> remoteTools = negotiatedCapabilities.contains("tools")
                ? adapter.listTools() : List.of();
        for (Map<String, Object> raw : remoteTools) {
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
        Map<String, PromptDefinition> preparedPrompts = negotiatedCapabilities.contains("prompts")
                ? definitions(adapter.listPrompts(), "prompt").entrySet().stream()
                        .collect(java.util.stream.Collectors.toUnmodifiableMap(
                                Map.Entry::getKey,
                                entry -> new PromptDefinition(entry.getKey(), entry.getValue())))
                : Map.of();
        Map<String, ResourceDefinition> preparedResources = negotiatedCapabilities.contains("resources")
                ? resourceDefinitions(adapter.listResources()) : Map.of();

        // No registry mutation occurs before all three remote namespaces validate.
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
        prompts = preparedPrompts;
        resources = preparedResources;
        // Existing ExposurePlans retain their frozen grants; this binding change is visible only to later snapshots.
    }

    public synchronized Map<String, String> knownVersions() {
        return Map.copyOf(knownVersions);
    }

    public synchronized Map<String, PromptDefinition> prompts() {
        return prompts;
    }

    public synchronized Map<String, ResourceDefinition> resources() {
        return resources;
    }

    public boolean pollForListChanges(int protocolVersion) {
        synchronized (this) {
            if (!active) throw new IllegalStateException("MCP source is no longer active");
        }
        if (!adapter.pollForListChanges()) return false;
        refresh(protocolVersion);
        return true;
    }

    public List<McpExternalContent> getPrompt(String name, Map<String, Object> arguments) {
        synchronized (this) {
            if (!active || !prompts.containsKey(name)) throw new SecurityException("unknown MCP prompt");
        }
        return McpExternalContent.prompts(
                server, name, new McpPromptNormalizer().normalize(adapter.getPrompt(name, arguments)));
    }

    public List<McpExternalContent> readResource(String uri) {
        synchronized (this) {
            if (!active || !resources.containsKey(uri)) throw new SecurityException("unknown MCP resource");
        }
        return McpExternalContent.resources(server, uri, adapter.readResource(uri));
    }

    public synchronized void deactivate() {
        active = false;
        adapter.close();
    }

    private synchronized Map<String, Object> invokePinned(
            String capabilityId,
            String version,
            String toolName,
            Map<String, Object> input,
            CapabilityImplementation.ExecutionContext context) throws Exception {
        if (!active) {
            throw new IllegalStateException(
                    "MCP source is no longer active");
        }
        return adapter.invoke(toolName, input, context);
    }

    private static Map<String, Map<String, Object>> definitions(
            List<Map<String, Object>> rawValues, String type) {
        Map<String, Map<String, Object>> values = new LinkedHashMap<>();
        for (Map<String, Object> raw : rawValues == null ? List.<Map<String, Object>>of() : rawValues) {
            String name = CapabilityDescriptor.required(String.valueOf(raw.get("name")), "MCP " + type + " name");
            if (values.putIfAbsent(name, Map.copyOf(raw)) != null) {
                throw new IllegalArgumentException("duplicate MCP " + type + ": " + name);
            }
        }
        return Map.copyOf(values);
    }

    private static Map<String, ResourceDefinition> resourceDefinitions(List<Map<String, Object>> rawValues) {
        Map<String, ResourceDefinition> values = new LinkedHashMap<>();
        for (Map<String, Object> raw : rawValues == null ? List.<Map<String, Object>>of() : rawValues) {
            String uri = CapabilityDescriptor.required(String.valueOf(raw.get("uri")), "MCP resource uri");
            String name = String.valueOf(raw.getOrDefault("name", uri));
            if (values.putIfAbsent(uri, new ResourceDefinition(uri, name, Map.copyOf(raw))) != null) {
                throw new IllegalArgumentException("duplicate MCP resource: " + uri);
            }
        }
        return Map.copyOf(values);
    }

    public interface Lifecycle {
        void activateAll(List<PreparedCapability> capabilities);
        void drain(String capabilityId);
        void retire(String capabilityId, String version);
    }

    public record PreparedCapability(
            CapabilityDescriptor descriptor,
            CapabilityImplementation implementation) { }

    public record PromptDefinition(String name, Map<String, Object> metadata) {
        public PromptDefinition { metadata = Map.copyOf(metadata); }
    }

    public record ResourceDefinition(String uri, String name, Map<String, Object> metadata) {
        public ResourceDefinition { metadata = Map.copyOf(metadata); }
    }

    private record PreparedTool(
            CapabilityDescriptor descriptor,
            CapabilityImplementation implementation) { }
}

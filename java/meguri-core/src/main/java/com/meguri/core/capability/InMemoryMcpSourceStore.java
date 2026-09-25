package com.meguri.core.capability;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryMcpSourceStore implements McpSourceStore {
    private final ConcurrentHashMap<String, McpSourceManager.SourceConfiguration> values =
            new ConcurrentHashMap<>();

    @Override
    public List<McpSourceManager.SourceConfiguration> configurations() {
        return values.values().stream()
                .sorted(java.util.Comparator.comparing(
                        McpSourceManager.SourceConfiguration::id))
                .toList();
    }

    @Override
    public Optional<McpSourceManager.SourceConfiguration> find(String sourceId) {
        return Optional.ofNullable(values.get(sourceId));
    }

    @Override
    public void save(McpSourceManager.SourceConfiguration configuration) {
        values.put(configuration.id(), configuration);
    }

    @Override
    public void delete(String sourceId) {
        values.remove(sourceId);
    }
}

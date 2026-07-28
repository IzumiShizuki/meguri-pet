package com.meguri.core.capability;

import java.util.List;
import java.util.Optional;

/** Durable configuration authority for approved MCP sources. */
public interface McpSourceStore {
    List<McpSourceManager.SourceConfiguration> configurations();

    Optional<McpSourceManager.SourceConfiguration> find(String sourceId);

    void save(McpSourceManager.SourceConfiguration configuration);

    void delete(String sourceId);
}

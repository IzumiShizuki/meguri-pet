package com.meguri.core.capability;

import com.meguri.core.dto.TurnRequest;

import java.util.List;
import java.util.Set;

/** Resolves one explicit MCP Prompt/Resource selection behind server-bound scopes. */
@FunctionalInterface
public interface McpContentResolver {
    List<McpExternalContent> resolve(
            TurnRequest.McpContentSelection selection,
            Set<String> authorizedScopes);

    static McpContentResolver unavailable() {
        return (selection, scopes) -> {
            throw new IllegalStateException("MCP content runtime is unavailable");
        };
    }
}

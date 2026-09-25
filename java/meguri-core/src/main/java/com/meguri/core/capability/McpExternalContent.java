package com.meguri.core.capability;

import java.util.List;
import java.util.Map;

/** Typed, explicitly untrusted MCP Prompt/Resource result for Context ingestion. */
public record McpExternalContent(
        Kind kind,
        String server,
        String identifier,
        String content,
        Trust trust,
        int tokenEstimate,
        Map<String, String> provenance) {
    public McpExternalContent {
        if (kind == null) throw new IllegalArgumentException("kind is required");
        server = required(server, "server");
        identifier = required(identifier, "identifier");
        content = required(content, "content");
        if (trust != Trust.UNTRUSTED_EXTERNAL) {
            throw new IllegalArgumentException("MCP content must remain untrusted");
        }
        if (tokenEstimate < 1) throw new IllegalArgumentException("tokenEstimate must be positive");
        provenance = provenance == null ? Map.of() : Map.copyOf(provenance);
    }

    public enum Kind { PROMPT, RESOURCE }
    public enum Trust { UNTRUSTED_EXTERNAL }

    public static List<McpExternalContent> prompts(
            String server,
            String name,
            List<McpPromptNormalizer.PromptMessage> messages) {
        return messages.stream().map(message -> new McpExternalContent(
                Kind.PROMPT, server, name, message.content(), Trust.UNTRUSTED_EXTERNAL,
                PromptSkillContextContract.tokenEstimate(message.content()),
                Map.of("mcp_type", "prompt", "remote_role", message.role().name()))).toList();
    }

    public static List<McpExternalContent> resources(
            String server, String uri, List<Map<String, Object>> contents) {
        return contents.stream().map(item -> {
            String text = required(String.valueOf(item.get("text")), "resource text");
            String sourceUri = String.valueOf(item.getOrDefault("uri", uri));
            return new McpExternalContent(
                    Kind.RESOURCE, server, sourceUri, text, Trust.UNTRUSTED_EXTERNAL,
                    PromptSkillContextContract.tokenEstimate(text),
                    Map.of("mcp_type", "resource", "requested_uri", uri));
        }).toList();
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank() || "null".equals(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}

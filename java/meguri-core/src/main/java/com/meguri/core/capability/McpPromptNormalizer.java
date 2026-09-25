package com.meguri.core.capability;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class McpPromptNormalizer {
    public List<PromptMessage> normalize(List<Map<String, Object>> remoteMessages) {
        if (remoteMessages == null) return List.of();
        List<PromptMessage> normalized = new ArrayList<>();
        for (Map<String, Object> message : remoteMessages) {
            String remoteRole = String.valueOf(message.getOrDefault("role", "user"))
                    .toLowerCase(Locale.ROOT);
            String content = content(message.get("content"));
            Role role = remoteRole.equals("assistant") ? Role.ASSISTANT : Role.USER;
            // Remote system/developer claims are data, never local instructions.
            normalized.add(new PromptMessage(role, content, true));
        }
        return List.copyOf(normalized);
    }

    public enum Role { USER, ASSISTANT }
    public record PromptMessage(Role role, String content, boolean untrusted) { }

    private static String content(Object raw) {
        Object value = raw;
        if (raw instanceof Map<?, ?> block) {
            Object type = block.get("type");
            if (type != null && !"text".equals(String.valueOf(type))) {
                throw new IllegalArgumentException("only MCP text prompt content is supported");
            }
            value = block.get("text");
        }
        String content = CapabilityDescriptor.required(String.valueOf(value), "prompt content");
        if (content.length() > 200_000) throw new IllegalArgumentException("MCP prompt content is too large");
        return content;
    }
}

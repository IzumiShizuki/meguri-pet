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
            String content = CapabilityDescriptor.required(String.valueOf(message.get("content")), "prompt content");
            Role role = remoteRole.equals("assistant") ? Role.ASSISTANT : Role.USER;
            // Remote system/developer claims are data, never local instructions.
            normalized.add(new PromptMessage(role, content, true));
        }
        return List.copyOf(normalized);
    }

    public enum Role { USER, ASSISTANT }
    public record PromptMessage(Role role, String content, boolean untrusted) { }
}

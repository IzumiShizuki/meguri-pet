package com.meguri.core.agent;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class AgentResultValidator {
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "authorization", "cookie", "password", "passwd", "secret",
            "token", "access_token", "refresh_token", "api_key",
            "apikey", "private_key", "client_secret");
    private static final Pattern SENSITIVE_TEXT = Pattern.compile(
            "(?i)(?:bearer\\s+[a-z0-9._~+/=-]{12,}|"
                    + "-----begin\\s+(?:rsa\\s+)?private\\s+key-----|"
                    + "(?:sk|rk|pk)_[a-z0-9_-]{16,})");

    public AgentResult validate(AgentResult result, InvokeAgentProposal proposal) {
        if (result == null) throw new AgentPolicyException("agent result is missing");
        if (!proposal.resultSchema().schemaId().equals(result.schemaId())) {
            throw new AgentPolicyException("agent result schema id does not match");
        }
        if (!proposal.agentId().equals(result.sourceAgentId())) {
            throw new AgentPolicyException("agent result source does not match");
        }
        if (result.sensitive() && !proposal.allowSensitiveResult()) {
            throw new AgentPolicyException("sensitive agent result is not allowed");
        }
        if (!result.payload().keySet().equals(proposal.resultSchema().requiredFields().keySet())) {
            throw new AgentPolicyException("agent result contains fields outside the registered schema");
        }
        proposal.resultSchema().requiredFields().forEach((field, type) -> {
            Object value = result.payload().get(field);
            if (value == null || !matches(value, type)) {
                throw new AgentPolicyException("agent result field has invalid schema: " + field);
            }
        });
        if (!proposal.allowSensitiveResult() && containsSensitiveValue(result.payload(), 0)) {
            throw new AgentPolicyException("agent result contains sensitive credential material");
        }
        return result.asUntrusted();
    }

    private static boolean containsSensitiveValue(Object value, int depth) {
        if (value == null) return false;
        if (depth > 16) return true;
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey()).trim().toLowerCase(java.util.Locale.ROOT);
                if (SENSITIVE_KEYS.contains(key) || containsSensitiveValue(entry.getValue(), depth + 1)) {
                    return true;
                }
            }
            return false;
        }
        if (value instanceof Iterable<?> values) {
            for (Object item : values) {
                if (containsSensitiveValue(item, depth + 1)) return true;
            }
            return false;
        }
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            for (int index = 0; index < length; index++) {
                if (containsSensitiveValue(java.lang.reflect.Array.get(value, index), depth + 1)) {
                    return true;
                }
            }
            return false;
        }
        return value instanceof CharSequence text && SENSITIVE_TEXT.matcher(text).find();
    }

    private static boolean matches(Object value, InvokeAgentProposal.ValueType type) {
        return switch (type) {
            case STRING -> value instanceof String;
            case NUMBER -> value instanceof Number;
            case BOOLEAN -> value instanceof Boolean;
            case OBJECT -> value instanceof Map<?, ?>;
            case ARRAY -> value instanceof List<?> || value.getClass().isArray();
        };
    }
}

package com.meguri.core.capability;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Bounded remote claims, never authority for outcome, retry, approval or identity. */
final class McpToolFailureException extends CapabilityFailureException {
    private static final int MAX_STRUCTURED_BYTES = 4_096;
    private static final int MAX_CODE_LENGTH = 64;
    private static final Pattern UUID_TEXT = Pattern.compile(
            "(?:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}"
                    + "|00000000-0000-0000-0000-000000000000|[fF]{8}-[fF]{4}-[fF]{4}-[fF]{4}-[fF]{12})");
    // Application-owned vocabulary; new remote codes require explicit local review.
    private static final Set<String> CODES = Set.of(
            "AUTH_REQUIRED", "FORBIDDEN", "NOT_FOUND", "CONFLICT", "VALIDATION_FAILED",
            "RATE_LIMITED", "UPSTREAM_ERROR", "NETWORK_ERROR", "TIMEOUT", "NETWORK_DENIED",
            "READ_ONLY", "UNSUPPORTED_CAPABILITY", "OWNER_MISMATCH", "INVALID_INPUT",
            "INVALID_RESPONSE", "INCOMPATIBLE_BACKEND", "INVALID_CURSOR", "STALE_CURSOR",
            "RESPONSE_TOO_LARGE", "OUTPUT_TOO_LARGE", "UNKNOWN_TOOL", "INTERNAL_ERROR",
            "CONFIGURATION");

    private final Map<String, Object> safeDetails;

    private McpToolFailureException(Map<String, Object> safeDetails) {
        // isError is never retried automatically, regardless of the remote retryable claim.
        super("MCP tool returned an error", null, "MCP_TOOL_FAILED", false);
        this.safeDetails = Map.copyOf(safeDetails);
    }

    static McpToolFailureException from(JsonNode structured, Map<String, Object> input) {
        return new McpToolFailureException(validate(structured, input));
    }

    Map<String, Object> safeDetails() { return safeDetails; }

    private static Map<String, Object> validate(JsonNode structured, Map<String, Object> input) {
        Object requestedId = input == null ? null : input.get("request_id");
        if (!(requestedId instanceof String requestId) || requestId.length() != 36
                || !UUID_TEXT.matcher(requestId).matches()
                || structured == null || !structured.isObject()) return Map.of();
        JsonNode responseId = structured.path("request_id");
        if (!responseId.isTextual() || !requestId.equals(responseId.textValue())
                || !structured.path("ok").isBoolean() || structured.path("ok").booleanValue()) return Map.of();

        // Bound the entire structured object, including ignored message/source/extra fields.
        // Nothing from content text is parsed or copied into the observation.
        if (structured.toString().getBytes(StandardCharsets.UTF_8).length > MAX_STRUCTURED_BYTES) return Map.of();
        JsonNode error = structured.path("error");
        JsonNode code = error.path("code");
        JsonNode retryable = error.path("retryable");
        JsonNode uncertain = error.path("uncertain");
        if (!error.isObject() || !code.isTextual() || code.textValue().length() > MAX_CODE_LENGTH
                || !CODES.contains(code.textValue()) || !retryable.isBoolean() || !uncertain.isBoolean()) return Map.of();
        JsonNode status = error.get("http_status");
        if (status != null && (!status.isIntegralNumber() || !status.canConvertToInt()
                || status.intValue() < 400 || status.intValue() > 599)) return Map.of();

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("code", code.textValue());
        if (status != null) details.put("http_status", status.intValue());
        details.put("retryable", retryable.booleanValue());
        details.put("remote_uncertain", uncertain.booleanValue());
        details.put("request_id", requestId);
        return details;
    }
}

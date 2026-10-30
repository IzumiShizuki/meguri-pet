package com.meguri.core.capability;

import java.util.Set;

/** Application-owned failure metadata; raw messages/causes never become tool observations. */
public class CapabilityFailureException extends RuntimeException {
    private static final Set<String> SOURCE_CODES = Set.of("SKILL_SOURCE_DISABLED", "SKILL_SOURCE_FAILED",
            "MODELSCOPE_SOURCE_UNAVAILABLE", "MODELSCOPE_SKILL_NOT_FOUND", "MODELSCOPE_SKILL_ID_INVALID",
            "MODELSCOPE_RESPONSE_INVALID");
    private static final Set<String> WEB_CODES = Set.of("WEB_FETCH_ARGUMENT_INVALID", "WEB_FETCH_URL_REJECTED",
            "WEB_FETCH_ADDRESS_REJECTED", "WEB_FETCH_RESOLVER_TARGET_MISMATCH", "WEB_FETCH_BODY_TOO_LARGE",
            "WEB_FETCH_REDIRECT_LOOP", "WEB_FETCH_REDIRECT_LIMIT", "WEB_FETCH_HTTPS_DOWNGRADE",
            "WEB_FETCH_RESPONSE_REJECTED", "WEB_FETCH_TIMEOUT", "WEB_FETCH_DNS_FAILED", "WEB_FETCH_TLS_FAILED",
            "WEB_FETCH_TRANSPORT_FAILED", "WEB_FETCH_FAILED");
    private static final Set<String> MCP_CODES = Set.of("MCP_TRANSPORT_FAILED", "MCP_TOOL_FAILED", "MCP_RESPONSE_INVALID",
            "MCP_ENDPOINT_REJECTED", "MCP_DNS_FAILED", "MCP_REMOTE_ERROR");
    private static final Set<String> SKILL_CODES = Set.of("SKILL_OPERATION_FAILED", "SKILL_DEPENDENCIES_UNAVAILABLE",
            "SKILL_NOT_IN_FROZEN_SNAPSHOT", "SKILL_SNAPSHOT_NOT_FROZEN", "SKILL_FILE_NOT_FOUND",
            "SKILL_FILE_NOT_TEXT", "SKILL_FILE_UNREADABLE", "SKILL_VIEW_LIMIT_EXCEEDED", "SKILL_REFERENCE_LIMIT_EXCEEDED",
            "SKILL_ROOT_VIEW_REQUIRED", "SKILL_PATH_INVALID", "SKILL_FILE_TOO_LARGE");
    private final String safeCode;
    private final boolean retryable;

    protected CapabilityFailureException(String message, Throwable cause, String code, boolean retryable) {
        super(message, cause);
        if (!SOURCE_CODES.contains(code) && !WEB_CODES.contains(code) && !MCP_CODES.contains(code) && !SKILL_CODES.contains(code)
                && !code.matches("WEB_FETCH_HTTP_[1-5][0-9]{2}"))
            throw new IllegalArgumentException("Unsupported safe failure code");
        this.safeCode = code;
        this.retryable = retryable;
    }
    public final String safeCode() { return safeCode; }
    public boolean retryable() { return retryable; }
    public static String sourceCode(String code) { return code != null && SOURCE_CODES.contains(code) ? code : "SKILL_SOURCE_FAILED"; }
    public static String skillCode(String code) { return code != null && SKILL_CODES.contains(code) ? code : "SKILL_OPERATION_FAILED"; }

    static CapabilityFailureException webFetch(Throwable failure) {
        Throwable error = reactor.core.Exceptions.unwrap(failure);
        String code = "WEB_FETCH_FAILED";
        boolean retryable = false;
        String message = error.getMessage();
        if (error instanceof IllegalArgumentException && message != null
                && (WEB_CODES.contains(message) || message.matches("WEB_FETCH_HTTP_[1-5][0-9]{2}"))) {
            code = message;
            retryable = code.matches("WEB_FETCH_HTTP_(408|429|5[0-9]{2})");
        } else if (error instanceof java.util.concurrent.TimeoutException
                || error instanceof java.net.SocketTimeoutException
                || error instanceof io.netty.handler.timeout.ReadTimeoutException) {
            code = "WEB_FETCH_TIMEOUT"; retryable = true;
        } else if (error instanceof java.net.UnknownHostException) {
            code = "WEB_FETCH_DNS_FAILED"; retryable = true;
        } else if (error instanceof javax.net.ssl.SSLException) code = "WEB_FETCH_TLS_FAILED";
        else if (error instanceof java.io.IOException) { code = "WEB_FETCH_TRANSPORT_FAILED"; retryable = true; }
        return new CapabilityFailureException(code, error, code, retryable);
    }
}

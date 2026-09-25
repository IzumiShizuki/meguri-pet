package com.meguri.core.adapter.application;

import org.springframework.http.HttpStatus;

import java.util.Map;

/** HTTP-independent stable protocol error carried to the controller advice. */
public final class AdapterProtocolException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final boolean retryable;
    private final Map<String, Object> details;

    public AdapterProtocolException(
            HttpStatus status,
            String code,
            String message,
            boolean retryable,
            Map<String, Object> details,
            Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
        this.retryable = retryable;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public AdapterProtocolException(
            HttpStatus status, String code, String message, boolean retryable) {
        this(status, code, message, retryable, Map.of(), null);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }

    public Map<String, Object> details() {
        return details;
    }
}

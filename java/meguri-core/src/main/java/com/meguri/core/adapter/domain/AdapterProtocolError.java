package com.meguri.core.adapter.domain;

import java.util.Map;

public record AdapterProtocolError(
        String code,
        String message,
        boolean retryable,
        Map<String, Object> details) {
    public AdapterProtocolError {
        if (code == null || code.isBlank() || message == null || message.isBlank()) {
            throw new IllegalArgumentException("protocol error code and message are required");
        }
        details = details == null ? Map.of() : Map.copyOf(details);
    }
}

package com.meguri.core.knowledge;

import java.util.Objects;

public record NotionHttpResponse(int statusCode, String body) {
    public NotionHttpResponse {
        if (statusCode < 100 || statusCode > 599) {
            throw new IllegalArgumentException("invalid HTTP status code");
        }
        body = Objects.requireNonNull(body, "body");
    }
}

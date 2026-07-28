package com.meguri.core.knowledge;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/** Transport request whose diagnostic representation never includes credentials. */
public final class NotionHttpRequest {
    private final String path;
    private final Map<String, String> query;
    private final String notionVersion;
    private final NotionApiCredentials credentials;
    private final Duration timeout;

    public NotionHttpRequest(
            String path,
            Map<String, String> query,
            String notionVersion,
            NotionApiCredentials credentials,
            Duration timeout) {
        this.path = required(path, "path");
        this.query = Map.copyOf(Objects.requireNonNull(query, "query"));
        this.notionVersion = required(notionVersion, "notionVersion");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    public String path() {
        return path;
    }

    public Map<String, String> query() {
        return query;
    }

    public String notionVersion() {
        return notionVersion;
    }

    public NotionApiCredentials credentials() {
        return credentials;
    }

    public Duration timeout() {
        return timeout;
    }

    @Override
    public String toString() {
        return "NotionHttpRequest[path=" + path + ", query=" + query
                + ", notionVersion=" + notionVersion + ", credentials=REDACTED"
                + ", timeout=" + timeout + "]";
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}

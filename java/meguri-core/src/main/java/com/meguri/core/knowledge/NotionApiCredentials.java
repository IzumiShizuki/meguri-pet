package com.meguri.core.knowledge;

/** Sensitive bearer credential. String representations are always redacted. */
public final class NotionApiCredentials {
    private final String token;

    public NotionApiCredentials(String token) {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("Notion token is required");
        this.token = token;
    }

    /** Intended only for the injected HTTP transport when creating the Authorization header. */
    public String authorizationHeader() {
        return "Bearer " + token;
    }

    @Override
    public String toString() {
        return "NotionApiCredentials[token=REDACTED]";
    }
}

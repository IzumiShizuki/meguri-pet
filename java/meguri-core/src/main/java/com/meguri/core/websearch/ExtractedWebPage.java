package com.meguri.core.websearch;

import java.time.Instant;

/** Sanitized data-only page extraction result. */
public record ExtractedWebPage(
        String canonicalUrl, String title, String content, Instant fetchedAt) {
    public ExtractedWebPage {
        if (canonicalUrl == null || canonicalUrl.isBlank()
                || content == null || content.isBlank()) {
            throw new IllegalArgumentException("canonicalUrl and content are required");
        }
        title = title == null ? "" : title.trim();
        content = content.trim();
        fetchedAt = fetchedAt == null ? Instant.now() : fetchedAt;
    }
}

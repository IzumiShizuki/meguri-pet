package com.meguri.core.knowledge;

import java.time.Instant;
import java.util.Objects;

public record NotionPage(
        String pageId,
        String title,
        String content,
        Instant lastEditedTime,
        boolean deleted,
        boolean secretMode) {
    public NotionPage {
        pageId = required(pageId, "pageId");
        title = required(title, "title");
        content = content == null ? "" : content;
        Objects.requireNonNull(lastEditedTime, "lastEditedTime");
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}

package com.meguri.core.knowledge;

import java.time.Instant;
import java.util.Objects;

public record SourcePage(
        String sourceId,
        String pageId,
        String title,
        String content,
        String contentHash,
        Instant lastEditedTime,
        KnowledgeAcl acl,
        boolean tombstone,
        boolean secretMode,
        KnowledgeSourceMetadata metadata) {
    public SourcePage {
        sourceId = required(sourceId, "sourceId");
        pageId = required(pageId, "pageId");
        title = required(title, "title");
        content = content == null ? "" : content;
        contentHash = required(contentHash, "contentHash");
        if (!contentHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "contentHash must be a lowercase SHA-256 digest");
        }
        Objects.requireNonNull(lastEditedTime, "lastEditedTime");
        Objects.requireNonNull(acl, "acl");
        metadata = metadata == null
                ? KnowledgeSourceMetadata.defaults(sourceId, pageId, content)
                : metadata;
    }

    public SourcePage(
            String sourceId,
            String pageId,
            String title,
            String content,
            String contentHash,
            Instant lastEditedTime,
            KnowledgeAcl acl,
            boolean tombstone,
            boolean secretMode) {
        this(sourceId, pageId, title, content, contentHash, lastEditedTime,
                acl, tombstone, secretMode, null);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}

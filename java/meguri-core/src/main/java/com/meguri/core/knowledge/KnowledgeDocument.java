package com.meguri.core.knowledge;

import java.time.Instant;
import java.util.Objects;

public record KnowledgeDocument(
        String id,
        String sourceId,
        String pageId,
        String title,
        KnowledgeSourceMetadata metadata,
        KnowledgeAcl acl,
        KnowledgeStatus status,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt) {
    public KnowledgeDocument {
        id = required(id, "id");
        sourceId = required(sourceId, "sourceId");
        pageId = required(pageId, "pageId");
        title = required(title, "title");
        metadata = metadata == null
                ? KnowledgeSourceMetadata.defaults(sourceId, pageId, "")
                : metadata;
        Objects.requireNonNull(acl, "acl");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (status == KnowledgeStatus.DELETED && deletedAt == null) {
            throw new IllegalArgumentException("deleted document requires deletedAt");
        }
    }

    public KnowledgeDocument(
            String id,
            String sourceId,
            String pageId,
            String title,
            KnowledgeAcl acl,
            KnowledgeStatus status,
            Instant createdAt,
            Instant updatedAt,
            Instant deletedAt) {
        this(id, sourceId, pageId, title, null, acl, status,
                createdAt, updatedAt, deletedAt);
    }

    public KnowledgeDocument withState(String newTitle, KnowledgeAcl newAcl,
                                       KnowledgeStatus newStatus, Instant at) {
        return withState(newTitle, metadata, newAcl, newStatus, at);
    }

    public KnowledgeDocument withState(
            String newTitle,
            KnowledgeSourceMetadata newMetadata,
            KnowledgeAcl newAcl,
            KnowledgeStatus newStatus,
            Instant at) {
        return new KnowledgeDocument(id, sourceId, pageId, newTitle, newMetadata,
                newAcl, newStatus,
                createdAt, at, newStatus == KnowledgeStatus.DELETED ? at : null);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}

package com.meguri.core.knowledge;

import java.time.Instant;
import java.util.Objects;

public record KnowledgeChunk(
        String id,
        String documentId,
        String documentVersionId,
        ChunkRole role,
        String parentChunkId,
        int ordinal,
        String content,
        KnowledgeAcl acl,
        Instant validFrom,
        Instant validUntil,
        KnowledgeChunkMetadata metadata,
        KnowledgeStatus status) {
    public static final Instant END_OF_TIME = Instant.parse("9999-12-31T23:59:59Z");

    public KnowledgeChunk(
            String id,
            String documentId,
            String documentVersionId,
            ChunkRole role,
            String parentChunkId,
            int ordinal,
            String content,
            KnowledgeAcl acl,
            Instant validFrom,
            Instant validUntil) {
        this(id, documentId, documentVersionId, role, parentChunkId, ordinal,
                content, acl, validFrom, validUntil, KnowledgeStatus.BUILDING);
    }

    public KnowledgeChunk(
            String id,
            String documentId,
            String documentVersionId,
            ChunkRole role,
            String parentChunkId,
            int ordinal,
            String content,
            KnowledgeAcl acl,
            Instant validFrom,
            Instant validUntil,
            KnowledgeStatus status) {
        this(id, documentId, documentVersionId, role, parentChunkId, ordinal,
                content, acl, validFrom, validUntil,
                KnowledgeChunkMetadata.derive(content), status);
    }

    public KnowledgeChunk {
        id = required(id, "id");
        documentId = required(documentId, "documentId");
        documentVersionId = required(documentVersionId, "documentVersionId");
        Objects.requireNonNull(role, "role");
        if (role == ChunkRole.PARENT && parentChunkId != null) {
            throw new IllegalArgumentException("PARENT chunk cannot have a parent");
        }
        if (role == ChunkRole.CHILD) parentChunkId = required(parentChunkId, "parentChunkId");
        if (ordinal < 0) throw new IllegalArgumentException("ordinal must not be negative");
        content = required(content, "content");
        Objects.requireNonNull(acl, "acl");
        Objects.requireNonNull(validFrom, "validFrom");
        Objects.requireNonNull(validUntil, "validUntil");
        metadata = metadata == null
                ? KnowledgeChunkMetadata.derive(content)
                : metadata;
        Objects.requireNonNull(status, "status");
        if (!validUntil.isAfter(validFrom)) throw new IllegalArgumentException("validUntil must follow validFrom");
    }

    public static KnowledgeChunk parent(String id, String documentId, String versionId,
                                        int ordinal, String content, KnowledgeAcl acl, Instant validFrom) {
        return new KnowledgeChunk(id, documentId, versionId, ChunkRole.PARENT, null,
                ordinal, content, acl, validFrom, END_OF_TIME);
    }

    public static KnowledgeChunk child(String id, String documentId, String versionId,
                                       String parentId, int ordinal, String content,
                                       KnowledgeAcl acl, Instant validFrom) {
        return new KnowledgeChunk(id, documentId, versionId, ChunkRole.CHILD, parentId,
                ordinal, content, acl, validFrom, END_OF_TIME);
    }

    public boolean effectiveAt(Instant at) {
        return published(status)
                && !at.isBefore(validFrom) && at.isBefore(validUntil);
    }

    public boolean keywordRecallEligible() {
        return role == ChunkRole.CHILD;
    }

    public boolean vectorRecallEligible() {
        return role == ChunkRole.CHILD;
    }

    public KnowledgeChunk withStatus(KnowledgeStatus nextStatus) {
        return new KnowledgeChunk(
                id, documentId, documentVersionId, role, parentChunkId, ordinal,
                content, acl, validFrom, validUntil, metadata, nextStatus);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private static boolean published(KnowledgeStatus value) {
        return value == KnowledgeStatus.ACTIVE
                || value == KnowledgeStatus.SUPERSEDED
                || value == KnowledgeStatus.DELETED;
    }
}

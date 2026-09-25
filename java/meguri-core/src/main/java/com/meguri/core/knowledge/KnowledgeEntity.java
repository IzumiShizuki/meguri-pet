package com.meguri.core.knowledge;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

public record KnowledgeEntity(
        String id,
        String documentId,
        String documentVersionId,
        String entityType,
        String canonicalName,
        List<String> aliases,
        String evidenceChunkId,
        KnowledgeAcl acl,
        Instant validFrom,
        Instant validUntil,
        KnowledgeStatus status,
        Instant createdAt) {
    public KnowledgeEntity(
            String id,
            String documentId,
            String documentVersionId,
            String entityType,
            String canonicalName,
            String evidenceChunkId,
            KnowledgeAcl acl,
            Instant createdAt) {
        this(id, documentId, documentVersionId, entityType, canonicalName,
                List.of(canonicalName), evidenceChunkId, acl, createdAt,
                KnowledgeChunk.END_OF_TIME, KnowledgeStatus.BUILDING, createdAt);
    }

    public KnowledgeEntity {
        id = required(id, "id");
        documentId = required(documentId, "documentId");
        documentVersionId = required(documentVersionId, "documentVersionId");
        entityType = required(entityType, "entityType");
        canonicalName = required(canonicalName, "canonicalName");
        LinkedHashSet<String> normalizedAliases = new LinkedHashSet<>();
        normalizedAliases.add(canonicalName);
        if (aliases != null) {
            aliases.stream()
                    .map(value -> required(value, "alias"))
                    .forEach(normalizedAliases::add);
        }
        aliases = List.copyOf(normalizedAliases);
        evidenceChunkId = required(evidenceChunkId, "evidenceChunkId");
        Objects.requireNonNull(acl, "acl");
        Objects.requireNonNull(validFrom, "validFrom");
        Objects.requireNonNull(validUntil, "validUntil");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        if (!validUntil.isAfter(validFrom)) {
            throw new IllegalArgumentException("validUntil must follow validFrom");
        }
    }

    public boolean effectiveAt(Instant at) {
        return published(status)
                && !at.isBefore(validFrom) && at.isBefore(validUntil);
    }

    public KnowledgeEntity withStatus(KnowledgeStatus nextStatus) {
        return new KnowledgeEntity(
                id, documentId, documentVersionId, entityType, canonicalName,
                aliases, evidenceChunkId, acl, validFrom, validUntil,
                nextStatus, createdAt);
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

package com.meguri.core.knowledge;

import java.time.Instant;
import java.util.Objects;

public record KnowledgeRelation(
        String id,
        String documentId,
        String documentVersionId,
        String fromEntityId,
        String relationType,
        String toEntityId,
        String evidenceChunkId,
        KnowledgeAcl acl,
        double confidence,
        Instant validFrom,
        Instant validUntil,
        KnowledgeStatus status,
        Instant createdAt) {
    public KnowledgeRelation(
            String id,
            String documentId,
            String documentVersionId,
            String fromEntityId,
            String relationType,
            String toEntityId,
            String evidenceChunkId,
            KnowledgeAcl acl,
            Instant createdAt) {
        this(id, documentId, documentVersionId, fromEntityId, relationType,
                toEntityId, evidenceChunkId, acl, 1.0, createdAt,
                KnowledgeChunk.END_OF_TIME, KnowledgeStatus.BUILDING, createdAt);
    }

    public KnowledgeRelation {
        id = required(id, "id");
        documentId = required(documentId, "documentId");
        documentVersionId = required(documentVersionId, "documentVersionId");
        fromEntityId = required(fromEntityId, "fromEntityId");
        relationType = required(relationType, "relationType");
        toEntityId = required(toEntityId, "toEntityId");
        evidenceChunkId = required(evidenceChunkId, "evidenceChunkId");
        Objects.requireNonNull(acl, "acl");
        if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
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

    public KnowledgeRelation withStatus(KnowledgeStatus nextStatus) {
        return new KnowledgeRelation(
                id, documentId, documentVersionId, fromEntityId, relationType,
                toEntityId, evidenceChunkId, acl, confidence, validFrom,
                validUntil, nextStatus, createdAt);
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

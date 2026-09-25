package com.meguri.core.knowledge;

import java.time.Instant;
import java.util.Objects;

public record KnowledgeDocumentVersion(
        String id,
        String documentId,
        long versionNumber,
        String contentHash,
        String parserRevision,
        String chunkerRevision,
        String embeddingRevision,
        KnowledgeAcl acl,
        KnowledgeStatus status,
        Instant sourceLastEditedAt,
        Instant createdAt,
        Instant publishedAt,
        Instant supersededAt,
        Instant failedAt,
        String failureReason) {
    public KnowledgeDocumentVersion {
        id = required(id, "id");
        documentId = required(documentId, "documentId");
        if (versionNumber < 1) throw new IllegalArgumentException("versionNumber must be positive");
        contentHash = required(contentHash, "contentHash");
        if (!contentHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "contentHash must be a lowercase SHA-256 digest");
        }
        parserRevision = required(parserRevision, "parserRevision");
        chunkerRevision = required(chunkerRevision, "chunkerRevision");
        embeddingRevision = required(embeddingRevision, "embeddingRevision");
        Objects.requireNonNull(acl, "acl");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(sourceLastEditedAt, "sourceLastEditedAt");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public KnowledgeDocumentVersion(
            String id,
            String documentId,
            long versionNumber,
            String contentHash,
            KnowledgeAcl acl,
            KnowledgeStatus status,
            Instant sourceLastEditedAt,
            Instant createdAt,
            Instant publishedAt,
            Instant supersededAt,
            Instant failedAt,
            String failureReason) {
        this(id, documentId, versionNumber, contentHash,
                "meguri-source-v1", "meguri-parent-child-v1",
                DeterministicSearchProjector.EMBEDDING_MODEL,
                acl, status, sourceLastEditedAt, createdAt, publishedAt,
                supersededAt, failedAt, failureReason);
    }

    public KnowledgeDocumentVersion activate(Instant at) {
        return transition(KnowledgeStatus.ACTIVE, at, null);
    }

    public KnowledgeDocumentVersion supersede(Instant at) {
        return transition(KnowledgeStatus.SUPERSEDED, at, null);
    }

    public KnowledgeDocumentVersion fail(Instant at, String reason) {
        return transition(KnowledgeStatus.FAILED, at, required(reason, "failureReason"));
    }

    public KnowledgeDocumentVersion delete(Instant at) {
        return transition(KnowledgeStatus.DELETED, at, null);
    }

    private KnowledgeDocumentVersion transition(KnowledgeStatus next, Instant at, String reason) {
        Objects.requireNonNull(at, "at");
        return new KnowledgeDocumentVersion(
                id, documentId, versionNumber, contentHash,
                parserRevision, chunkerRevision, embeddingRevision, acl, next,
                sourceLastEditedAt, createdAt,
                next == KnowledgeStatus.ACTIVE ? at : publishedAt,
                next == KnowledgeStatus.SUPERSEDED || next == KnowledgeStatus.DELETED ? at : supersededAt,
                next == KnowledgeStatus.FAILED ? at : failedAt,
                next == KnowledgeStatus.FAILED ? reason : failureReason);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}

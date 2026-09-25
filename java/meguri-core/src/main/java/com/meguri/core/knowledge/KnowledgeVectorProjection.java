package com.meguri.core.knowledge;

import java.util.List;
import java.util.Objects;

/** Persistable deterministic embedding projection for one CHILD chunk. */
public record KnowledgeVectorProjection(
        String chunkId,
        String documentId,
        String documentVersionId,
        KnowledgeAcl acl,
        String embeddingModel,
        List<Double> embedding) {
    public KnowledgeVectorProjection {
        chunkId = required(chunkId, "chunkId");
        documentId = required(documentId, "documentId");
        documentVersionId = required(documentVersionId, "documentVersionId");
        Objects.requireNonNull(acl, "acl");
        embeddingModel = required(embeddingModel, "embeddingModel");
        embedding = List.copyOf(Objects.requireNonNull(embedding, "embedding"));
        if (embedding.isEmpty()) throw new IllegalArgumentException("embedding must not be empty");
        if (embedding.stream().anyMatch(value -> value == null || !Double.isFinite(value))) {
            throw new IllegalArgumentException("embedding values must be finite");
        }
    }

    public int dimensions() {
        return embedding.size();
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}

package com.meguri.core.knowledge;

import java.util.Objects;

public record KnowledgeSearchHit(KnowledgeChunk chunk, double score) {
    public KnowledgeSearchHit {
        Objects.requireNonNull(chunk, "chunk");
        if (!Double.isFinite(score)) throw new IllegalArgumentException("score must be finite");
    }
}

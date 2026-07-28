package com.meguri.core.knowledge;

import java.util.List;
import java.util.Objects;

public record KnowledgeBuild(
        List<KnowledgeChunk> chunks,
        List<KnowledgeEntity> entities,
        List<KnowledgeRelation> relations,
        List<KnowledgeTermProjection> termProjections,
        List<KnowledgeVectorProjection> vectorProjections) {
    public KnowledgeBuild(
            List<KnowledgeChunk> chunks,
            List<KnowledgeEntity> entities,
            List<KnowledgeRelation> relations) {
        this(chunks, entities, relations, List.of(), List.of());
    }

    public KnowledgeBuild {
        chunks = List.copyOf(Objects.requireNonNull(chunks, "chunks"));
        entities = List.copyOf(Objects.requireNonNull(entities, "entities"));
        relations = List.copyOf(Objects.requireNonNull(relations, "relations"));
        termProjections = List.copyOf(Objects.requireNonNull(termProjections, "termProjections"));
        vectorProjections = List.copyOf(Objects.requireNonNull(vectorProjections, "vectorProjections"));
    }
}

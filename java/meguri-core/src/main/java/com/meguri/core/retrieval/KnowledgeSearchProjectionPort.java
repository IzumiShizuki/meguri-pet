package com.meguri.core.retrieval;

import com.meguri.core.knowledge.KnowledgeChunk;
import com.meguri.core.knowledge.KnowledgeDocument;
import com.meguri.core.knowledge.KnowledgeDocumentVersion;
import com.meguri.core.knowledge.KnowledgeEntity;
import com.meguri.core.knowledge.KnowledgeRelation;
import com.meguri.core.knowledge.KnowledgeTermProjection;
import com.meguri.core.knowledge.KnowledgeVectorProjection;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.time.Instant;

/**
 * Minimal anticipated knowledge search projection API.
 *
 * <p>A database implementation should load all requested immutable versions in one
 * consistent read. The repository adapter is a compatibility bridge until that API lands.
 */
public interface KnowledgeSearchProjectionPort {
    List<KnowledgeDocumentVersion> activeVersions();

    Projection loadVersions(Set<String> versionIds);

    default Optional<List<String>> nativeKeywordRanks(
            Set<String> versionIds,
            Set<String> aclHashes,
            Instant validAt,
            String query,
            int limit) {
        return Optional.empty();
    }

    default Optional<List<String>> nativeVectorRanks(
            Set<String> versionIds,
            Set<String> aclHashes,
            Instant validAt,
            List<Double> queryEmbedding,
            int limit) {
        return Optional.empty();
    }

    record Projection(
            List<KnowledgeDocument> documents,
            List<KnowledgeDocumentVersion> versions,
            List<KnowledgeChunk> chunks,
            List<KnowledgeEntity> entities,
            List<KnowledgeRelation> relations,
            List<KnowledgeTermProjection> termProjections,
            List<KnowledgeVectorProjection> vectorProjections,
            boolean searchProjectionsAuthoritative) {
        public Projection(
                List<KnowledgeDocument> documents,
                List<KnowledgeDocumentVersion> versions,
                List<KnowledgeChunk> chunks,
                List<KnowledgeEntity> entities,
                List<KnowledgeRelation> relations) {
            this(documents, versions, chunks, entities, relations,
                    List.of(), List.of(), false);
        }

        public Projection {
            documents = List.copyOf(documents);
            versions = List.copyOf(versions);
            chunks = List.copyOf(chunks);
            entities = List.copyOf(entities);
            relations = List.copyOf(relations);
            termProjections = List.copyOf(termProjections);
            vectorProjections = List.copyOf(vectorProjections);
        }
    }
}

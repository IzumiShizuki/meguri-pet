package com.meguri.core.retrieval;

import com.meguri.core.knowledge.KnowledgeDocument;
import com.meguri.core.knowledge.KnowledgeDocumentVersion;
import com.meguri.core.knowledge.KnowledgeNativeSearch;
import com.meguri.core.knowledge.KnowledgeRepository;
import com.meguri.core.knowledge.KnowledgeStatus;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.time.Instant;

/** Compatibility adapter over the current repository's immutable version reads. */
public final class KnowledgeRepositoryProjectionAdapter implements KnowledgeSearchProjectionPort {
    private final KnowledgeRepository repository;

    public KnowledgeRepositoryProjectionAdapter(KnowledgeRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    @Override
    public List<KnowledgeDocumentVersion> activeVersions() {
        return repository.documents().stream()
                .map(document -> repository.findActiveVersion(document.id()).orElse(null))
                .filter(Objects::nonNull)
                .filter(version -> version.status() == KnowledgeStatus.ACTIVE)
                .sorted(java.util.Comparator.comparing(KnowledgeDocumentVersion::documentId))
                .toList();
    }

    @Override
    public Projection loadVersions(Set<String> versionIds) {
        Set<String> requested = versionIds == null ? Set.of() : Set.copyOf(versionIds);
        List<KnowledgeDocument> documents = repository.documents();
        List<KnowledgeDocumentVersion> versions = documents.stream()
                .flatMap(document -> repository.versions(document.id()).stream())
                .filter(version -> requested.contains(version.id()))
                .toList();
        LinkedHashSet<String> documentIds = versions.stream()
                .map(KnowledgeDocumentVersion::documentId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<KnowledgeDocument> selectedDocuments = documents.stream()
                .filter(document -> documentIds.contains(document.id()))
                .toList();
        var chunks = new ArrayList<com.meguri.core.knowledge.KnowledgeChunk>();
        var entities = new ArrayList<com.meguri.core.knowledge.KnowledgeEntity>();
        var relations = new ArrayList<com.meguri.core.knowledge.KnowledgeRelation>();
        var terms = new ArrayList<com.meguri.core.knowledge.KnowledgeTermProjection>();
        var vectors = new ArrayList<com.meguri.core.knowledge.KnowledgeVectorProjection>();
        for (KnowledgeDocumentVersion version : versions) {
            chunks.addAll(repository.chunks(version.id()));
            entities.addAll(repository.entities(version.id()));
            relations.addAll(repository.relations(version.id()));
            terms.addAll(repository.termProjections(version.id()));
            vectors.addAll(repository.vectorProjections(version.id()));
        }
        return new Projection(
                selectedDocuments, versions, chunks, entities, relations,
                terms, vectors, true);
    }

    @Override
    public Optional<List<String>> nativeKeywordRanks(
            Set<String> versionIds,
            Set<String> aclHashes,
            Instant validAt,
            String query,
            int limit) {
        if (!(repository instanceof KnowledgeNativeSearch nativeSearch)) {
            return Optional.empty();
        }
        return Optional.of(List.copyOf(nativeSearch.rankKeyword(
                versionIds, aclHashes, validAt, query, limit)));
    }

    @Override
    public Optional<List<String>> nativeVectorRanks(
            Set<String> versionIds,
            Set<String> aclHashes,
            Instant validAt,
            List<Double> queryEmbedding,
            int limit) {
        if (!(repository instanceof KnowledgeNativeSearch nativeSearch)) {
            return Optional.empty();
        }
        return Optional.of(List.copyOf(nativeSearch.rankVector(
                versionIds, aclHashes, validAt, queryEmbedding, limit)));
    }
}

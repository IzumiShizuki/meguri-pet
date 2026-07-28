package com.meguri.core.knowledge;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface KnowledgeRepository {
    BuildStart beginBuild(SourcePage page, Instant now);

    void publish(String versionId, KnowledgeBuild build, Instant now);

    void failBuild(String versionId, String reason, Instant now);

    void tombstone(String sourceId, String pageId, Instant now);

    Optional<KnowledgeDocument> findDocument(String sourceId, String pageId);

    Optional<KnowledgeDocumentVersion> findActiveVersion(String documentId);

    List<KnowledgeDocument> documents();

    List<KnowledgeDocumentVersion> versions(String documentId);

    List<KnowledgeChunk> chunks(String versionId);

    List<KnowledgeEntity> entities(String versionId);

    List<KnowledgeRelation> relations(String versionId);

    List<KnowledgeTermProjection> termProjections(String versionId);

    List<KnowledgeVectorProjection> vectorProjections(String versionId);

    List<KnowledgeChunk> recallableChildren(String versionId, KnowledgeAcl acl, Instant at);

    Optional<KnowledgeChunk> restoreParent(String childChunkId, KnowledgeAcl acl, Instant at);

    default List<KnowledgeSearchHit> keywordSearch(
            String versionId, KnowledgeAcl acl, Instant at, String query, int limit) {
        return KnowledgeSearchRanking.keyword(
                recallableChildren(versionId, acl, at), termProjections(versionId), query, limit);
    }

    default List<KnowledgeSearchHit> vectorSearch(
            String versionId, KnowledgeAcl acl, Instant at,
            List<Double> queryEmbedding, int limit) {
        return KnowledgeSearchRanking.vector(
                recallableChildren(versionId, acl, at),
                vectorProjections(versionId), queryEmbedding, limit);
    }
}

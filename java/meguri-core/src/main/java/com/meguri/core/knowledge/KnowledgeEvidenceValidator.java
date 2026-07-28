package com.meguri.core.knowledge;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Publication gate shared by in-memory and PostgreSQL adapters. */
public final class KnowledgeEvidenceValidator {
    public void validate(KnowledgeDocument document,
                         KnowledgeDocumentVersion version,
                         KnowledgeBuild build) {
        if (!document.id().equals(version.documentId())) {
            throw new IllegalArgumentException("version does not belong to document");
        }
        if (!document.acl().equals(version.acl())) {
            throw new IllegalArgumentException("document and version ACL differ");
        }
        if (version.status() != KnowledgeStatus.BUILDING) {
            throw new IllegalArgumentException("only BUILDING versions can be published");
        }

        Map<String, KnowledgeChunk> chunks = new HashMap<>();
        for (KnowledgeChunk chunk : build.chunks()) {
            requireScope(document, version, chunk.documentId(), chunk.documentVersionId(), chunk.acl(), "chunk");
            requireBuilding(chunk.status(), "chunk");
            validateChunkIntegrity(chunk);
            if (chunks.put(chunk.id(), chunk) != null) throw new IllegalArgumentException("duplicate chunk id");
        }
        if (chunks.values().stream().noneMatch(chunk -> chunk.role() == ChunkRole.CHILD)) {
            throw new IllegalArgumentException("published version requires at least one CHILD chunk");
        }
        for (KnowledgeChunk chunk : chunks.values()) {
            if (chunk.role() != ChunkRole.CHILD) continue;
            KnowledgeChunk parent = chunks.get(chunk.parentChunkId());
            if (parent == null || parent.role() != ChunkRole.PARENT) {
                throw new IllegalArgumentException("CHILD chunk requires a PARENT in the same build");
            }
            if (!parent.documentVersionId().equals(chunk.documentVersionId())
                    || !parent.acl().equals(chunk.acl())
                    || !parent.validFrom().equals(chunk.validFrom())
                    || !parent.validUntil().equals(chunk.validUntil())) {
                throw new IllegalArgumentException("parent and child scope, ACL, and validity must match");
            }
            validateSourceAnchor(parent, chunk);
        }

        Map<String, KnowledgeEntity> entities = new HashMap<>();
        for (KnowledgeEntity entity : build.entities()) {
            requireScope(document, version, entity.documentId(), entity.documentVersionId(), entity.acl(), "entity");
            requireBuilding(entity.status(), "entity");
            KnowledgeChunk evidence = requireEvidence(chunks, entity.evidenceChunkId());
            requireEvidenceValidity(
                    evidence, entity.validFrom(), entity.validUntil(), "entity");
            if (entities.put(entity.id(), entity) != null) throw new IllegalArgumentException("duplicate entity id");
        }
        Set<String> relationIds = new HashSet<>();
        for (KnowledgeRelation relation : build.relations()) {
            requireScope(document, version, relation.documentId(), relation.documentVersionId(),
                    relation.acl(), "relation");
            requireBuilding(relation.status(), "relation");
            KnowledgeChunk evidence = requireEvidence(chunks, relation.evidenceChunkId());
            requireEvidenceValidity(
                    evidence, relation.validFrom(), relation.validUntil(), "relation");
            KnowledgeEntity from = entities.get(relation.fromEntityId());
            KnowledgeEntity to = entities.get(relation.toEntityId());
            if (from == null || to == null) {
                throw new IllegalArgumentException("relation endpoints require entities in the same build");
            }
            if (!from.documentVersionId().equals(relation.documentVersionId())
                    || !to.documentVersionId().equals(relation.documentVersionId())
                    || !from.acl().equals(relation.acl())
                    || !to.acl().equals(relation.acl())) {
                throw new IllegalArgumentException("relation endpoints must share version and ACL");
            }
            if (!relationIds.add(relation.id())) throw new IllegalArgumentException("duplicate relation id");
        }
        validateSearchProjections(document, version, build, chunks);
    }

    private static void validateSearchProjections(
            KnowledgeDocument document,
            KnowledgeDocumentVersion version,
            KnowledgeBuild build,
            Map<String, KnowledgeChunk> chunks) {
        Set<String> childIds = chunks.values().stream()
                .filter(chunk -> chunk.role() == ChunkRole.CHILD)
                .map(KnowledgeChunk::id)
                .collect(java.util.stream.Collectors.toSet());

        Map<String, KnowledgeTermProjection> terms = new HashMap<>();
        for (KnowledgeTermProjection projection : build.termProjections()) {
            requireScope(document, version, projection.documentId(),
                    projection.documentVersionId(), projection.acl(), "term projection");
            KnowledgeChunk child = requireProjectedChild(chunks, projection.chunkId());
            KnowledgeTermProjection expected = DeterministicSearchProjector.terms(child);
            if (!expected.termFrequencies().equals(projection.termFrequencies())
                    || expected.tokenCount() != projection.tokenCount()
                    || child.metadata().tokenCount() != projection.tokenCount()) {
                throw new IllegalArgumentException("term projection does not match CHILD content");
            }
            if (terms.put(projection.chunkId(), projection) != null) {
                throw new IllegalArgumentException("duplicate term projection");
            }
        }
        if (!terms.keySet().equals(childIds)) {
            throw new IllegalArgumentException("every CHILD requires one complete term projection");
        }

        Map<String, KnowledgeVectorProjection> vectors = new HashMap<>();
        for (KnowledgeVectorProjection projection : build.vectorProjections()) {
            requireScope(document, version, projection.documentId(),
                    projection.documentVersionId(), projection.acl(), "vector projection");
            KnowledgeChunk child = requireProjectedChild(chunks, projection.chunkId());
            KnowledgeVectorProjection expected = DeterministicSearchProjector.vector(child);
            if (!expected.embeddingModel().equals(projection.embeddingModel())
                    || !expected.embedding().equals(projection.embedding())) {
                throw new IllegalArgumentException("vector projection does not match CHILD content");
            }
            double norm = Math.sqrt(projection.embedding().stream()
                    .mapToDouble(value -> value * value).sum());
            if (Math.abs(norm - 1.0) > 1.0e-9) {
                throw new IllegalArgumentException("vector projection must be L2 normalized");
            }
            if (vectors.put(projection.chunkId(), projection) != null) {
                throw new IllegalArgumentException("duplicate vector projection");
            }
        }
        if (!vectors.keySet().equals(childIds)) {
            throw new IllegalArgumentException("every CHILD requires one complete vector projection");
        }
    }

    private static KnowledgeChunk requireProjectedChild(
            Map<String, KnowledgeChunk> chunks, String chunkId) {
        KnowledgeChunk chunk = chunks.get(chunkId);
        if (chunk == null || chunk.role() != ChunkRole.CHILD) {
            throw new IllegalArgumentException("search projection must reference a CHILD chunk");
        }
        return chunk;
    }

    private static void validateChunkIntegrity(KnowledgeChunk chunk) {
        KnowledgeChunkMetadata expected =
                KnowledgeChunkMetadata.derive(chunk.content());
        if (!expected.contentHash().equals(chunk.metadata().contentHash())
                || expected.tokenCount() != chunk.metadata().tokenCount()) {
            throw new IllegalArgumentException(
                    "chunk integrity metadata does not match content");
        }
        if (chunk.role() == ChunkRole.PARENT
                && chunk.metadata().sourceStart() != null
                && chunk.metadata().sourceEnd() - chunk.metadata().sourceStart()
                        != chunk.content().length()) {
            throw new IllegalArgumentException(
                    "PARENT source anchor does not match content length");
        }
    }

    private static void validateSourceAnchor(
            KnowledgeChunk parent, KnowledgeChunk child) {
        Integer childStart = child.metadata().sourceStart();
        if (childStart == null) return;
        Integer parentStart = parent.metadata().sourceStart();
        Integer parentEnd = parent.metadata().sourceEnd();
        if (parentStart == null || parentEnd == null) {
            throw new IllegalArgumentException(
                    "anchored CHILD requires an anchored PARENT");
        }
        int childEnd = child.metadata().sourceEnd();
        if (childStart < parentStart || childEnd > parentEnd) {
            throw new IllegalArgumentException(
                    "CHILD source anchor escapes its PARENT");
        }
        int relativeStart = childStart - parentStart;
        int relativeEnd = childEnd - parentStart;
        if (relativeEnd > parent.content().length()
                || !parent.content().substring(relativeStart, relativeEnd)
                        .equals(child.content())) {
            throw new IllegalArgumentException(
                    "CHILD source anchor does not match PARENT content");
        }
    }

    private static void requireScope(KnowledgeDocument document,
                                     KnowledgeDocumentVersion version,
                                     String documentId,
                                     String versionId,
                                     KnowledgeAcl acl,
                                     String kind) {
        if (!document.id().equals(documentId)
                || !version.id().equals(versionId)
                || !version.acl().equals(acl)) {
            throw new IllegalArgumentException(kind + " must share document version and ACL");
        }
    }

    private static KnowledgeChunk requireEvidence(
            Map<String, KnowledgeChunk> chunks, String evidenceId) {
        KnowledgeChunk evidence = chunks.get(evidenceId);
        if (evidence == null || evidence.role() != ChunkRole.CHILD) {
            throw new IllegalArgumentException("graph evidence must reference a CHILD chunk");
        }
        return evidence;
    }

    private static void requireBuilding(KnowledgeStatus status, String kind) {
        if (status != KnowledgeStatus.BUILDING) {
            throw new IllegalArgumentException(kind + " must be BUILDING before publication");
        }
    }

    private static void requireEvidenceValidity(
            KnowledgeChunk evidence,
            java.time.Instant validFrom,
            java.time.Instant validUntil,
            String kind) {
        if (!evidence.validFrom().equals(validFrom)
                || !evidence.validUntil().equals(validUntil)) {
            throw new IllegalArgumentException(
                    kind + " validity must match its evidence chunk");
        }
    }
}

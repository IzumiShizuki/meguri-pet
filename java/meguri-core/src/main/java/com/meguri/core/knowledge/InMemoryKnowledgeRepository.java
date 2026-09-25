package com.meguri.core.knowledge;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Thread-safe authority adapter with transaction-equivalent synchronized publication. */
public final class InMemoryKnowledgeRepository implements KnowledgeRepository {
    private final KnowledgeEvidenceValidator validator = new KnowledgeEvidenceValidator();
    private final Map<String, KnowledgeDocument> documents = new LinkedHashMap<>();
    private final Map<String, KnowledgeDocumentVersion> versions = new LinkedHashMap<>();
    private final Map<String, KnowledgeChunk> chunks = new LinkedHashMap<>();
    private final Map<String, KnowledgeEntity> entities = new LinkedHashMap<>();
    private final Map<String, KnowledgeRelation> relations = new LinkedHashMap<>();
    private final Map<String, KnowledgeTermProjection> termProjections = new LinkedHashMap<>();
    private final Map<String, KnowledgeVectorProjection> vectorProjections = new LinkedHashMap<>();

    @Override
    public synchronized BuildStart beginBuild(SourcePage page, Instant now) {
        String key = key(page.sourceId(), page.pageId());
        KnowledgeDocument document = documents.get(key);
        Optional<KnowledgeDocumentVersion> active = document == null
                ? Optional.empty() : findActiveVersion(document.id());
        boolean unchanged = active
                .filter(version -> version.contentHash().equals(page.contentHash())
                        && version.acl().equals(page.acl()))
                .isPresent()
                && document.title().equals(page.title())
                && document.metadata().equals(page.metadata());
        if (unchanged) {
            return new BuildStart(document, active.orElseThrow(), true);
        }

        if (document == null) {
            document = new KnowledgeDocument(
                    id("kdoc"), page.sourceId(), page.pageId(), page.title(),
                    page.metadata(), page.acl(), KnowledgeStatus.BUILDING,
                    now, now, null);
        } else {
            KnowledgeStatus documentStatus = active.isPresent()
                    ? KnowledgeStatus.ACTIVE : KnowledgeStatus.BUILDING;
            document = document.withState(
                    page.title(), page.metadata(), page.acl(), documentStatus, now);
        }
        documents.put(key, document);
        String documentId = document.id();
        long number = versions.values().stream()
                .filter(version -> version.documentId().equals(documentId))
                .mapToLong(KnowledgeDocumentVersion::versionNumber)
                .max().orElse(0L) + 1;
        KnowledgeDocumentVersion version = new KnowledgeDocumentVersion(
                id("kver"), documentId, number, page.contentHash(), page.acl(),
                KnowledgeStatus.BUILDING, page.lastEditedTime(), now,
                null, null, null, null);
        versions.put(version.id(), version);
        return new BuildStart(document, version, false);
    }

    @Override
    public synchronized void publish(String versionId, KnowledgeBuild build, Instant now) {
        KnowledgeDocumentVersion version = requiredVersion(versionId);
        KnowledgeDocument document = documentById(version.documentId());
        long latestVersion = versions(document.id()).stream()
                .mapToLong(KnowledgeDocumentVersion::versionNumber)
                .max().orElseThrow();
        if (version.versionNumber() != latestVersion) {
            throw new IllegalStateException("stale BUILDING version cannot replace a newer version");
        }
        validator.validate(document, version, build);

        findActiveVersion(document.id()).ifPresent(active -> {
            versions.put(active.id(), active.supersede(now));
            updateProjectionStatus(active.id(), KnowledgeStatus.SUPERSEDED);
        });
        for (KnowledgeChunk chunk : build.chunks()) {
            chunks.put(chunk.id(), chunk.withStatus(KnowledgeStatus.ACTIVE));
        }
        for (KnowledgeTermProjection projection : build.termProjections()) {
            termProjections.put(projection.chunkId(), projection);
        }
        for (KnowledgeVectorProjection projection : build.vectorProjections()) {
            vectorProjections.put(projection.chunkId(), projection);
        }
        for (KnowledgeEntity entity : build.entities()) {
            entities.put(entity.id(), entity.withStatus(KnowledgeStatus.ACTIVE));
        }
        for (KnowledgeRelation relation : build.relations()) {
            relations.put(relation.id(), relation.withStatus(KnowledgeStatus.ACTIVE));
        }
        versions.put(version.id(), version.activate(now));
        documents.put(key(document.sourceId(), document.pageId()),
                document.withState(document.title(), version.acl(), KnowledgeStatus.ACTIVE, now));
    }

    @Override
    public synchronized void failBuild(String versionId, String reason, Instant now) {
        KnowledgeDocumentVersion version = requiredVersion(versionId);
        if (version.status() != KnowledgeStatus.BUILDING) return;
        versions.put(version.id(), version.fail(now, reason));
        KnowledgeDocument document = documentById(version.documentId());
        KnowledgeStatus status = findActiveVersion(document.id()).isPresent()
                ? KnowledgeStatus.ACTIVE : KnowledgeStatus.FAILED;
        documents.put(key(document.sourceId(), document.pageId()),
                document.withState(document.title(), document.acl(), status, now));
    }

    @Override
    public synchronized void tombstone(String sourceId, String pageId, Instant now) {
        KnowledgeDocument document = documents.get(key(sourceId, pageId));
        if (document == null) return;
        for (KnowledgeDocumentVersion version : versions.values().stream()
                .filter(candidate -> candidate.documentId().equals(document.id()))
                .filter(candidate -> candidate.status() == KnowledgeStatus.ACTIVE
                        || candidate.status() == KnowledgeStatus.BUILDING)
                .toList()) {
            versions.put(version.id(), version.delete(now));
            updateProjectionStatus(version.id(), KnowledgeStatus.DELETED);
        }
        documents.put(key(sourceId, pageId),
                document.withState(document.title(), document.acl(), KnowledgeStatus.DELETED, now));
    }

    @Override
    public synchronized Optional<KnowledgeDocument> findDocument(String sourceId, String pageId) {
        return Optional.ofNullable(documents.get(key(sourceId, pageId)));
    }

    @Override
    public synchronized Optional<KnowledgeDocumentVersion> findActiveVersion(String documentId) {
        return versions.values().stream()
                .filter(version -> version.documentId().equals(documentId))
                .filter(version -> version.status() == KnowledgeStatus.ACTIVE)
                .findFirst();
    }

    @Override
    public synchronized List<KnowledgeDocument> documents() {
        return List.copyOf(documents.values());
    }

    @Override
    public synchronized List<KnowledgeDocumentVersion> versions(String documentId) {
        return versions.values().stream()
                .filter(version -> version.documentId().equals(documentId))
                .sorted(Comparator.comparingLong(KnowledgeDocumentVersion::versionNumber))
                .toList();
    }

    @Override
    public synchronized List<KnowledgeChunk> chunks(String versionId) {
        return chunks.values().stream()
                .filter(chunk -> chunk.documentVersionId().equals(versionId))
                .sorted(Comparator.comparingInt(KnowledgeChunk::ordinal))
                .toList();
    }

    @Override
    public synchronized List<KnowledgeEntity> entities(String versionId) {
        return entities.values().stream()
                .filter(entity -> entity.documentVersionId().equals(versionId))
                .toList();
    }

    @Override
    public synchronized List<KnowledgeRelation> relations(String versionId) {
        return relations.values().stream()
                .filter(relation -> relation.documentVersionId().equals(versionId))
                .toList();
    }

    @Override
    public synchronized List<KnowledgeTermProjection> termProjections(String versionId) {
        return termProjections.values().stream()
                .filter(projection -> projection.documentVersionId().equals(versionId))
                .sorted(Comparator.comparing(KnowledgeTermProjection::chunkId))
                .toList();
    }

    @Override
    public synchronized List<KnowledgeVectorProjection> vectorProjections(String versionId) {
        return vectorProjections.values().stream()
                .filter(projection -> projection.documentVersionId().equals(versionId))
                .sorted(Comparator.comparing(KnowledgeVectorProjection::chunkId))
                .toList();
    }

    @Override
    public synchronized List<KnowledgeChunk> recallableChildren(
            String versionId, KnowledgeAcl acl, Instant at) {
        if (findActiveVersionById(versionId).isEmpty()) return List.of();
        return chunks(versionId).stream()
                .filter(chunk -> chunk.role() == ChunkRole.CHILD)
                .filter(chunk -> chunk.acl().equals(acl))
                .filter(chunk -> chunk.effectiveAt(at))
                .toList();
    }

    @Override
    public synchronized Optional<KnowledgeChunk> restoreParent(
            String childChunkId, KnowledgeAcl acl, Instant at) {
        KnowledgeChunk child = chunks.get(childChunkId);
        if (child == null || child.role() != ChunkRole.CHILD
                || !child.acl().equals(acl) || !child.effectiveAt(at)
                || findActiveVersionById(child.documentVersionId()).isEmpty()) {
            return Optional.empty();
        }
        KnowledgeChunk parent = chunks.get(child.parentChunkId());
        if (parent == null || parent.role() != ChunkRole.PARENT
                || !parent.documentVersionId().equals(child.documentVersionId())
                || !parent.acl().equals(child.acl())
                || !parent.validFrom().equals(child.validFrom())
                || !parent.validUntil().equals(child.validUntil())) {
            return Optional.empty();
        }
        return Optional.of(parent);
    }

    private Optional<KnowledgeDocumentVersion> findActiveVersionById(String versionId) {
        return Optional.ofNullable(versions.get(versionId))
                .filter(version -> version.status() == KnowledgeStatus.ACTIVE);
    }

    private void updateProjectionStatus(
            String versionId, KnowledgeStatus status) {
        chunks.values().stream()
                .filter(chunk -> chunk.documentVersionId().equals(versionId))
                .toList()
                .forEach(chunk -> chunks.put(chunk.id(), chunk.withStatus(status)));
        entities.values().stream()
                .filter(entity -> entity.documentVersionId().equals(versionId))
                .toList()
                .forEach(entity -> entities.put(entity.id(), entity.withStatus(status)));
        relations.values().stream()
                .filter(relation -> relation.documentVersionId().equals(versionId))
                .toList()
                .forEach(relation -> relations.put(
                        relation.id(), relation.withStatus(status)));
    }

    private KnowledgeDocumentVersion requiredVersion(String id) {
        KnowledgeDocumentVersion version = versions.get(id);
        if (version == null) throw new IllegalArgumentException("unknown version: " + id);
        return version;
    }

    private KnowledgeDocument documentById(String id) {
        return documents.values().stream()
                .filter(document -> document.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("version document is missing"));
    }

    private static String key(String sourceId, String pageId) {
        return sourceId + "\u0000" + pageId;
    }

    private static String id(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }
}

package com.meguri.core.knowledge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** PostgreSQL authority adapter; every publication state switch is one transaction. */
public final class PostgresKnowledgeRepository
        implements KnowledgeRepository, KnowledgeNativeSearch {
    private static final TypeReference<java.util.Map<String, Integer>> TERM_FREQUENCIES =
            new TypeReference<>() { };
    private static final TypeReference<List<Double>> EMBEDDING = new TypeReference<>() { };
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;
    private final KnowledgeEvidenceValidator validator = new KnowledgeEvidenceValidator();

    public PostgresKnowledgeRepository(
            JdbcTemplate jdbc, ObjectMapper mapper, TransactionTemplate transactions) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        initializeSchema();
    }

    @Override
    public BuildStart beginBuild(SourcePage page, Instant now) {
        BuildStart result = transactions.execute(status -> beginBuildInTransaction(page, now));
        if (result == null) throw new IllegalStateException("build transaction returned no result");
        return result;
    }

    private BuildStart beginBuildInTransaction(SourcePage page, Instant now) {
        String sourceKey = page.sourceId() + "\u0000" + page.pageId();
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                (ResultSet rs) -> {
                    rs.next();
                    return null;
                }, sourceKey);
        KnowledgeDocument document = findDocumentForUpdate(page.sourceId(), page.pageId()).orElse(null);
        KnowledgeDocumentVersion active = document == null
                ? null : findActiveVersionForUpdate(document.id()).orElse(null);
        if (active != null && active.contentHash().equals(page.contentHash())
                && active.acl().equals(page.acl())
                && document.title().equals(page.title())
                && document.metadata().equals(page.metadata())) {
            return new BuildStart(document, active, true);
        }

        if (document == null) {
            document = new KnowledgeDocument(
                    id("kdoc"), page.sourceId(), page.pageId(), page.title(),
                    page.metadata(), page.acl(), KnowledgeStatus.BUILDING,
                    now, now, null);
            insertDocument(document);
        } else {
            KnowledgeStatus state = active == null ? KnowledgeStatus.BUILDING : KnowledgeStatus.ACTIVE;
            document = document.withState(
                    page.title(), page.metadata(), page.acl(), state, now);
            updateDocument(document);
        }
        Long current = jdbc.queryForObject("""
                SELECT COALESCE(MAX(version_number), 0)
                FROM knowledge_document_version
                WHERE document_id = ?
                """, Long.class, document.id());
        long number = (current == null ? 0L : current) + 1L;
        KnowledgeDocumentVersion version = new KnowledgeDocumentVersion(
                id("kver"), document.id(), number, page.contentHash(), page.acl(),
                KnowledgeStatus.BUILDING, page.lastEditedTime(), now,
                null, null, null, null);
        insertVersion(version);
        return new BuildStart(document, version, false);
    }

    @Override
    public void publish(String versionId, KnowledgeBuild build, Instant now) {
        transactions.executeWithoutResult(status -> {
            KnowledgeDocumentVersion version = findVersionForUpdate(versionId)
                    .orElseThrow(() -> new IllegalArgumentException("unknown version: " + versionId));
            KnowledgeDocument document = findDocumentByIdForUpdate(version.documentId())
                    .orElseThrow(() -> new IllegalStateException("version document is missing"));
            Long latestVersion = jdbc.queryForObject("""
                    SELECT MAX(version_number)
                    FROM knowledge_document_version
                    WHERE document_id = ?
                    """, Long.class, document.id());
            if (latestVersion == null || version.versionNumber() != latestVersion) {
                throw new IllegalStateException(
                        "stale BUILDING version cannot replace a newer version");
            }
            validator.validate(document, version, build);

            Map<String, KnowledgeTermProjection> terms = build.termProjections().stream()
                    .collect(Collectors.toMap(
                            KnowledgeTermProjection::chunkId, Function.identity()));
            Map<String, KnowledgeVectorProjection> vectors = build.vectorProjections().stream()
                    .collect(Collectors.toMap(
                            KnowledgeVectorProjection::chunkId, Function.identity()));
            build.chunks().stream()
                    .sorted(Comparator.comparing(KnowledgeChunk::role)
                            .thenComparingInt(KnowledgeChunk::ordinal))
                    .forEach(chunk -> insertChunk(
                            chunk, terms.get(chunk.id()), vectors.get(chunk.id()),
                            version.embeddingRevision()));
            build.entities().forEach(this::insertEntity);
            build.relations().forEach(this::insertRelation);
            supersedePublishedProjections(document.id());
            jdbc.update("""
                    UPDATE knowledge_document_version
                    SET status = 'superseded', superseded_at = ?
                    WHERE document_id = ? AND status = 'active'
                    """, Timestamp.from(now), document.id());
            int activated = jdbc.update("""
                    UPDATE knowledge_document_version
                    SET status = 'active', published_at = ?
                    WHERE document_version_id = ? AND status = 'building'
                    """, Timestamp.from(now), version.id());
            if (activated != 1) throw new IllegalStateException("BUILDING version could not be activated");
            activatePublishedProjections(version.id());
            KnowledgeDocument activeDocument =
                    document.withState(document.title(), version.acl(), KnowledgeStatus.ACTIVE, now);
            updateDocument(activeDocument);
            jdbc.update("""
                    UPDATE knowledge_document
                    SET current_active_version_id = ?
                    WHERE document_id = ?
                    """, version.id(), document.id());
        });
    }

    @Override
    public void failBuild(String versionId, String reason, Instant now) {
        transactions.executeWithoutResult(status -> {
            KnowledgeDocumentVersion version = findVersionForUpdate(versionId).orElse(null);
            if (version == null || version.status() != KnowledgeStatus.BUILDING) return;
            jdbc.update("""
                    UPDATE knowledge_document_version
                    SET status = 'failed', failed_at = ?, failure_reason = ?
                    WHERE document_version_id = ? AND status = 'building'
                    """, Timestamp.from(now), reason, versionId);
            KnowledgeDocument document = findDocumentByIdForUpdate(version.documentId()).orElseThrow();
            boolean hasActive = findActiveVersionForUpdate(document.id()).isPresent();
            updateDocument(document.withState(
                    document.title(), document.acl(),
                    hasActive ? KnowledgeStatus.ACTIVE : KnowledgeStatus.FAILED, now));
        });
    }

    @Override
    public void tombstone(String sourceId, String pageId, Instant now) {
        transactions.executeWithoutResult(status -> {
            KnowledgeDocument document = findDocumentForUpdate(sourceId, pageId).orElse(null);
            if (document == null) return;
            jdbc.update("""
                    UPDATE knowledge_document_version
                    SET status = 'deleted', superseded_at = ?
                    WHERE document_id = ? AND status IN ('active', 'building')
                    """, Timestamp.from(now), document.id());
            updateProjectionStatus(
                    document.id(), KnowledgeStatus.DELETED);
            updateDocument(document.withState(
                    document.title(), document.acl(), KnowledgeStatus.DELETED, now));
            jdbc.update("""
                    UPDATE knowledge_document
                    SET current_active_version_id = NULL
                    WHERE document_id = ?
                    """, document.id());
        });
    }

    @Override
    public Optional<KnowledgeDocument> findDocument(String sourceId, String pageId) {
        return one("""
                SELECT * FROM knowledge_document
                WHERE source_id = ? AND page_id = ?
                """, this::readDocument, sourceId, pageId);
    }

    @Override
    public Optional<KnowledgeDocumentVersion> findActiveVersion(String documentId) {
        return one("""
                SELECT * FROM knowledge_document_version
                WHERE document_id = ? AND status = 'active'
                """, this::readVersion, documentId);
    }

    @Override
    public List<KnowledgeDocument> documents() {
        return jdbc.query("""
                SELECT * FROM knowledge_document
                ORDER BY source_id, page_id
                """, (rs, rowNum) -> readDocument(rs));
    }

    @Override
    public List<KnowledgeDocumentVersion> versions(String documentId) {
        return jdbc.query("""
                SELECT * FROM knowledge_document_version
                WHERE document_id = ?
                ORDER BY version_number
                """, (rs, rowNum) -> readVersion(rs), documentId);
    }

    @Override
    public List<KnowledgeChunk> chunks(String versionId) {
        return jdbc.query("""
                SELECT * FROM knowledge_chunk
                WHERE document_version_id = ?
                ORDER BY ordinal
                """, (rs, rowNum) -> readChunk(rs), versionId);
    }

    @Override
    public List<KnowledgeEntity> entities(String versionId) {
        return jdbc.query("""
                SELECT * FROM knowledge_entity
                WHERE document_version_id = ?
                ORDER BY entity_id
                """, (rs, rowNum) -> readEntity(rs), versionId);
    }

    @Override
    public List<KnowledgeRelation> relations(String versionId) {
        return jdbc.query("""
                SELECT * FROM knowledge_relation
                WHERE document_version_id = ?
                ORDER BY relation_id
                """, (rs, rowNum) -> readRelation(rs), versionId);
    }

    @Override
    public List<KnowledgeTermProjection> termProjections(String versionId) {
        return jdbc.query("""
                SELECT *
                FROM knowledge_chunk
                WHERE document_version_id = ?
                  AND chunk_role = 'child'
                  AND term_frequencies_json IS NOT NULL
                ORDER BY chunk_id
                """, (rs, rowNum) -> readTermProjection(rs), versionId);
    }

    @Override
    public List<KnowledgeVectorProjection> vectorProjections(String versionId) {
        return jdbc.query("""
                SELECT *, embedding::text AS embedding_text
                FROM knowledge_chunk
                WHERE document_version_id = ?
                  AND chunk_role = 'child'
                  AND embedding IS NOT NULL
                ORDER BY chunk_id
                """, (rs, rowNum) -> readVectorProjection(rs), versionId);
    }

    @Override
    public List<KnowledgeChunk> recallableChildren(
            String versionId, KnowledgeAcl acl, Instant at) {
        return jdbc.query("""
                SELECT c.*
                FROM knowledge_chunk c
                JOIN knowledge_document_version v
                  ON v.document_version_id = c.document_version_id
                WHERE c.document_version_id = ?
                  AND v.status = 'active'
                  AND c.chunk_role = 'child'
                  AND c.status = 'active'
                  AND c.acl_hash = ?
                  AND c.valid_from <= ?
                  AND c.valid_until > ?
                ORDER BY c.ordinal
                """, (rs, rowNum) -> readChunk(rs),
                versionId, acl.hash(), Timestamp.from(at), Timestamp.from(at));
    }

    @Override
    public Optional<KnowledgeChunk> restoreParent(
            String childChunkId, KnowledgeAcl acl, Instant at) {
        return one("""
                SELECT p.*
                FROM knowledge_chunk c
                JOIN knowledge_chunk p
                  ON p.chunk_id = c.parent_chunk_id
                 AND p.document_version_id = c.document_version_id
                 AND p.acl_hash = c.acl_hash
                 AND p.valid_from = c.valid_from
                 AND p.valid_until = c.valid_until
                JOIN knowledge_document_version v
                  ON v.document_version_id = c.document_version_id
                WHERE c.chunk_id = ?
                  AND c.chunk_role = 'child'
                  AND p.chunk_role = 'parent'
                  AND v.status = 'active'
                  AND c.status = 'active'
                  AND p.status = 'active'
                  AND c.acl_hash = ?
                  AND c.valid_from <= ?
                  AND c.valid_until > ?
                """, this::readChunk,
                childChunkId, acl.hash(), Timestamp.from(at), Timestamp.from(at));
    }

    @Override
    public List<String> rankKeyword(
            java.util.Set<String> versionIds,
            java.util.Set<String> aclHashes,
            Instant validAt,
            String query,
            int limit) {
        SearchScope scope = searchScope(versionIds, aclHashes, validAt, limit);
        if (scope.empty()) return List.of();
        return nativeKeywordRanks(
                scope.versionIds(), scope.aclHashes(), validAt, query, limit);
    }

    @Override
    public List<String> rankVector(
            java.util.Set<String> versionIds,
            java.util.Set<String> aclHashes,
            Instant validAt,
            List<Double> queryEmbedding,
            int limit) {
        SearchScope scope = searchScope(versionIds, aclHashes, validAt, limit);
        if (scope.empty()) return List.of();
        if (queryEmbedding == null
                || queryEmbedding.size()
                != DeterministicSearchProjector.EMBEDDING_DIMENSIONS) {
            throw new IllegalArgumentException(
                    "query embedding must match the persisted vector dimension");
        }
        return nativeVectorRanks(
                scope.versionIds(), scope.aclHashes(), validAt, queryEmbedding, limit);
    }

    private static SearchScope searchScope(
            java.util.Set<String> versionIds,
            java.util.Set<String> aclHashes,
            Instant validAt,
            int limit) {
        Objects.requireNonNull(versionIds, "versionIds");
        Objects.requireNonNull(aclHashes, "aclHashes");
        Objects.requireNonNull(validAt, "validAt");
        if (versionIds.isEmpty() || aclHashes.isEmpty() || limit <= 0) {
            return new SearchScope(List.of(), List.of());
        }
        return new SearchScope(
                versionIds.stream().sorted().toList(),
                aclHashes.stream().sorted().toList());
    }

    private List<String> nativeKeywordRanks(
            List<String> versionIds,
            List<String> aclHashes,
            Instant validAt,
            String query,
            int limit) {
        List<String> terms = DeterministicSearchProjector
                .termFrequencies(query).keySet().stream().sorted().toList();
        if (terms.isEmpty()) return List.of();
        String sql = """
                WITH query_terms(term) AS (
                    VALUES %s
                ),
                corpus AS (
                    SELECT c.*
                    FROM knowledge_chunk c
                    JOIN knowledge_chunk p
                      ON p.chunk_id = c.parent_chunk_id
                     AND p.document_version_id = c.document_version_id
                     AND p.acl_hash = c.acl_hash
                    JOIN knowledge_document_version v
                      ON v.document_version_id = c.document_version_id
                    WHERE c.document_version_id IN (%s)
                      AND c.acl_hash IN (%s)
                      AND v.status IN ('active', 'superseded', 'deleted')
                      AND c.status IN ('active', 'superseded', 'deleted')
                      AND p.status IN ('active', 'superseded', 'deleted')
                      AND c.chunk_role = 'child'
                      AND p.chunk_role = 'parent'
                      AND c.valid_from <= ?
                      AND c.valid_until > ?
                      AND c.term_frequencies_json IS NOT NULL
                      AND c.search_vector @@ to_tsquery('simple', ?)
                ),
                stats AS (
                    SELECT COUNT(*)::double precision AS corpus_size,
                           AVG(token_count)::double precision AS average_length
                    FROM corpus
                ),
                document_frequencies AS (
                    SELECT q.term,
                           COUNT(c.chunk_id) FILTER (
                               WHERE c.term_frequencies_json ? q.term
                           )::double precision AS document_frequency
                    FROM query_terms q
                    CROSS JOIN corpus c
                    GROUP BY q.term
                ),
                scores AS (
                    SELECT c.chunk_id,
                           SUM(
                               LN(1.0 + (
                                   (s.corpus_size - d.document_frequency + 0.5)
                                   / (d.document_frequency + 0.5)
                               ))
                               * (
                                   COALESCE(
                                       (c.term_frequencies_json ->> d.term)
                                           ::double precision,
                                       0.0
                                   ) * 2.2
                               )
                               / (
                                   COALESCE(
                                       (c.term_frequencies_json ->> d.term)
                                           ::double precision,
                                       0.0
                                   )
                                   + 1.2 * (
                                       0.25 + 0.75 * c.token_count
                                       / GREATEST(s.average_length, 1.0)
                                   )
                               )
                           ) AS score
                    FROM corpus c
                    CROSS JOIN stats s
                    CROSS JOIN document_frequencies d
                    GROUP BY c.chunk_id
                )
                SELECT chunk_id
                FROM scores
                WHERE score > 0
                ORDER BY score DESC, chunk_id
                LIMIT ?
                """.formatted(
                placeholders(terms.size(), "(?)"),
                placeholders(versionIds.size(), "?"),
                placeholders(aclHashes.size(), "?"));
        java.util.ArrayList<Object> arguments = new java.util.ArrayList<>();
        arguments.addAll(terms);
        arguments.addAll(versionIds);
        arguments.addAll(aclHashes);
        arguments.add(Timestamp.from(validAt));
        arguments.add(Timestamp.from(validAt));
        arguments.add(String.join(" | ", terms));
        arguments.add(limit);
        return jdbc.queryForList(sql, String.class, arguments.toArray());
    }

    private List<String> nativeVectorRanks(
            List<String> versionIds,
            List<String> aclHashes,
            Instant validAt,
            List<Double> queryEmbedding,
            int limit) {
        String sql = """
                WITH query_vector AS (
                    SELECT CAST(? AS vector) AS embedding
                )
                SELECT c.chunk_id
                FROM knowledge_chunk c
                JOIN knowledge_chunk p
                  ON p.chunk_id = c.parent_chunk_id
                 AND p.document_version_id = c.document_version_id
                 AND p.acl_hash = c.acl_hash
                JOIN knowledge_document_version v
                  ON v.document_version_id = c.document_version_id
                CROSS JOIN query_vector q
                WHERE c.document_version_id IN (%s)
                  AND c.acl_hash IN (%s)
                  AND v.status IN ('active', 'superseded', 'deleted')
                  AND c.status IN ('active', 'superseded', 'deleted')
                  AND p.status IN ('active', 'superseded', 'deleted')
                  AND c.chunk_role = 'child'
                  AND p.chunk_role = 'parent'
                  AND c.valid_from <= ?
                  AND c.valid_until > ?
                  AND c.embedding IS NOT NULL
                  AND 1.0 - (c.embedding <=> q.embedding) > 0
                ORDER BY c.embedding <=> q.embedding, c.chunk_id
                LIMIT ?
                """.formatted(
                placeholders(versionIds.size(), "?"),
                placeholders(aclHashes.size(), "?"));
        java.util.ArrayList<Object> arguments = new java.util.ArrayList<>();
        arguments.add(vectorLiteral(queryEmbedding));
        arguments.addAll(versionIds);
        arguments.addAll(aclHashes);
        arguments.add(Timestamp.from(validAt));
        arguments.add(Timestamp.from(validAt));
        arguments.add(limit);
        return jdbc.queryForList(sql, String.class, arguments.toArray());
    }

    private Optional<KnowledgeDocument> findDocumentForUpdate(String sourceId, String pageId) {
        return one("""
                SELECT * FROM knowledge_document
                WHERE source_id = ? AND page_id = ?
                FOR UPDATE
                """, this::readDocument, sourceId, pageId);
    }

    private Optional<KnowledgeDocument> findDocumentByIdForUpdate(String documentId) {
        return one("""
                SELECT * FROM knowledge_document
                WHERE document_id = ?
                FOR UPDATE
                """, this::readDocument, documentId);
    }

    private Optional<KnowledgeDocumentVersion> findActiveVersionForUpdate(String documentId) {
        return one("""
                SELECT * FROM knowledge_document_version
                WHERE document_id = ? AND status = 'active'
                FOR UPDATE
                """, this::readVersion, documentId);
    }

    private Optional<KnowledgeDocumentVersion> findVersionForUpdate(String versionId) {
        return one("""
                SELECT * FROM knowledge_document_version
                WHERE document_version_id = ?
                FOR UPDATE
                """, this::readVersion, versionId);
    }

    private void insertDocument(KnowledgeDocument document) {
        KnowledgeSourceMetadata metadata = document.metadata();
        jdbc.update("""
                INSERT INTO knowledge_document (
                    document_id, source_id, page_id, title,
                    source_uri, canonical_uri, language, project_id,
                    source_parent_id, linked_document_ids,
                    acl_hash, acl_json, status, created_at, updated_at, deleted_at
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb),
                    ?, CAST(? AS jsonb), ?, ?, ?, ?
                )
                """, document.id(), document.sourceId(), document.pageId(), document.title(),
                metadata.sourceUri(), metadata.canonicalUri(), metadata.language(),
                metadata.projectId(), metadata.sourceParentId(),
                write(metadata.linkedDocumentIds()),
                document.acl().hash(), write(document.acl()), wire(document.status()),
                Timestamp.from(document.createdAt()), Timestamp.from(document.updatedAt()),
                timestamp(document.deletedAt()));
    }

    private void updateDocument(KnowledgeDocument document) {
        KnowledgeSourceMetadata metadata = document.metadata();
        int updated = jdbc.update("""
                UPDATE knowledge_document
                SET title = ?, source_uri = ?, canonical_uri = ?, language = ?,
                    project_id = ?, source_parent_id = ?,
                    linked_document_ids = CAST(? AS jsonb),
                    acl_hash = ?, acl_json = CAST(? AS jsonb), status = ?,
                    updated_at = ?, deleted_at = ?
                WHERE document_id = ?
                """, document.title(), metadata.sourceUri(), metadata.canonicalUri(),
                metadata.language(), metadata.projectId(), metadata.sourceParentId(),
                write(metadata.linkedDocumentIds()),
                document.acl().hash(), write(document.acl()),
                wire(document.status()), Timestamp.from(document.updatedAt()),
                timestamp(document.deletedAt()), document.id());
        if (updated != 1) throw new IllegalStateException("knowledge document update failed");
    }

    private void insertVersion(KnowledgeDocumentVersion version) {
        jdbc.update("""
                INSERT INTO knowledge_document_version (
                    document_version_id, document_id, version_number, content_hash,
                    parser_revision, chunker_revision, embedding_revision,
                    acl_hash, acl_json, status, source_last_edited_at, created_at,
                    published_at, superseded_at, failed_at, failure_reason
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?,
                    ?, ?, ?, ?, ?, ?
                )
                """, version.id(), version.documentId(), version.versionNumber(), version.contentHash(),
                version.parserRevision(), version.chunkerRevision(), version.embeddingRevision(),
                version.acl().hash(), write(version.acl()), wire(version.status()),
                Timestamp.from(version.sourceLastEditedAt()), Timestamp.from(version.createdAt()),
                timestamp(version.publishedAt()), timestamp(version.supersededAt()),
                timestamp(version.failedAt()), version.failureReason());
    }

    private void insertChunk(
            KnowledgeChunk chunk,
            KnowledgeTermProjection terms,
            KnowledgeVectorProjection vector,
            String embeddingRevision) {
        boolean child = chunk.role() == ChunkRole.CHILD;
        if (child && (terms == null || vector == null)) {
            throw new IllegalArgumentException(
                    "CHILD chunk requires complete search projections");
        }
        if (!child && (terms != null || vector != null)) {
            throw new IllegalArgumentException(
                    "PARENT chunk cannot carry search projections");
        }
        KnowledgeChunkMetadata metadata = chunk.metadata();
        jdbc.update("""
                INSERT INTO knowledge_chunk (
                    chunk_id, document_id, document_version_id, chunk_role, parent_chunk_id,
                    ordinal, content, section_path, heading, source_start, source_end,
                    content_sha256, acl_hash, acl_json, valid_from, valid_until, status,
                    term_frequencies_json, token_count, normalized_terms,
                    embedding_model, embedding_revision, embedding_dimensions, embedding
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?,
                    ?, CAST(? AS jsonb), ?, ?, ?, CAST(? AS jsonb), ?, ?,
                    ?, ?, ?, CAST(? AS vector)
                )
                """, chunk.id(), chunk.documentId(), chunk.documentVersionId(),
                wire(chunk.role()), chunk.parentChunkId(), chunk.ordinal(), chunk.content(),
                write(metadata.sectionPath()), metadata.heading(),
                metadata.sourceStart(), metadata.sourceEnd(), metadata.contentHash(),
                chunk.acl().hash(), write(chunk.acl()), Timestamp.from(chunk.validFrom()),
                Timestamp.from(chunk.validUntil()), wire(chunk.status()),
                child ? write(terms.termFrequencies()) : null,
                metadata.tokenCount(),
                child ? terms.normalizedTerms() : null,
                child ? vector.embeddingModel() : null,
                child ? embeddingRevision : null,
                child ? vector.dimensions() : null,
                child ? vectorLiteral(vector.embedding()) : null);
    }

    private void insertEntity(KnowledgeEntity entity) {
        jdbc.update("""
                INSERT INTO knowledge_entity (
                    entity_id, document_id, document_version_id, entity_type, canonical_name,
                    aliases, evidence_chunk_id, acl_hash, acl_json,
                    valid_from, valid_until, status, created_at
                ) VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, CAST(? AS jsonb),
                          ?, ?, ?, ?)
                """, entity.id(), entity.documentId(), entity.documentVersionId(),
                entity.entityType(), entity.canonicalName(), write(entity.aliases()),
                entity.evidenceChunkId(), entity.acl().hash(), write(entity.acl()),
                Timestamp.from(entity.validFrom()), Timestamp.from(entity.validUntil()),
                wire(entity.status()), Timestamp.from(entity.createdAt()));
    }

    private void insertRelation(KnowledgeRelation relation) {
        jdbc.update("""
                INSERT INTO knowledge_relation (
                    relation_id, document_id, document_version_id, from_entity_id,
                    relation_type, to_entity_id, evidence_chunk_id, acl_hash, acl_json,
                    confidence, valid_from, valid_until, status, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?)
                """, relation.id(), relation.documentId(), relation.documentVersionId(),
                relation.fromEntityId(), relation.relationType(), relation.toEntityId(),
                relation.evidenceChunkId(), relation.acl().hash(), write(relation.acl()),
                relation.confidence(), Timestamp.from(relation.validFrom()),
                Timestamp.from(relation.validUntil()), wire(relation.status()),
                Timestamp.from(relation.createdAt()));
    }

    private void supersedePublishedProjections(String documentId) {
        updateProjectionStatus(
                documentId, KnowledgeStatus.ACTIVE, KnowledgeStatus.SUPERSEDED);
    }

    private void activatePublishedProjections(String versionId) {
        for (String table : List.of(
                "knowledge_chunk", "knowledge_entity", "knowledge_relation")) {
            int updated = jdbc.update(
                    "UPDATE " + table
                            + " SET status = 'active'"
                            + " WHERE document_version_id = ? AND status = 'building'",
                    versionId);
            if (updated < 0) {
                throw new IllegalStateException(
                        "knowledge projection activation failed");
            }
        }
    }

    private void updateProjectionStatus(
            String documentId, KnowledgeStatus status) {
        for (String table : List.of(
                "knowledge_chunk", "knowledge_entity", "knowledge_relation")) {
            jdbc.update(
                    "UPDATE " + table + " SET status = ? WHERE document_id = ?",
                    wire(status), documentId);
        }
    }

    private void updateProjectionStatus(
            String documentId,
            KnowledgeStatus current,
            KnowledgeStatus next) {
        for (String table : List.of(
                "knowledge_chunk", "knowledge_entity", "knowledge_relation")) {
            jdbc.update(
                    "UPDATE " + table
                            + " SET status = ? WHERE document_id = ? AND status = ?",
                    wire(next), documentId, wire(current));
        }
    }

    private KnowledgeDocument readDocument(ResultSet rs) throws SQLException {
        KnowledgeSourceMetadata metadata = new KnowledgeSourceMetadata(
                rs.getString("source_uri"),
                rs.getString("canonical_uri"),
                rs.getString("language"),
                rs.getString("project_id"),
                rs.getString("source_parent_id"),
                read(rs.getString("linked_document_ids"), STRING_LIST));
        return new KnowledgeDocument(
                rs.getString("document_id"), rs.getString("source_id"), rs.getString("page_id"),
                rs.getString("title"), metadata, readAcl(rs.getString("acl_json")),
                status(rs.getString("status")), instant(rs, "created_at"),
                instant(rs, "updated_at"), nullableInstant(rs, "deleted_at"));
    }

    private KnowledgeDocumentVersion readVersion(ResultSet rs) throws SQLException {
        return new KnowledgeDocumentVersion(
                rs.getString("document_version_id"), rs.getString("document_id"),
                rs.getLong("version_number"), rs.getString("content_hash"),
                rs.getString("parser_revision"), rs.getString("chunker_revision"),
                rs.getString("embedding_revision"),
                readAcl(rs.getString("acl_json")), status(rs.getString("status")),
                instant(rs, "source_last_edited_at"), instant(rs, "created_at"),
                nullableInstant(rs, "published_at"), nullableInstant(rs, "superseded_at"),
                nullableInstant(rs, "failed_at"), rs.getString("failure_reason"));
    }

    private KnowledgeChunk readChunk(ResultSet rs) throws SQLException {
        KnowledgeChunkMetadata metadata = new KnowledgeChunkMetadata(
                read(rs.getString("section_path"), STRING_LIST),
                rs.getString("heading"),
                nullableInteger(rs, "source_start"),
                nullableInteger(rs, "source_end"),
                rs.getString("content_sha256"),
                rs.getInt("token_count"));
        return new KnowledgeChunk(
                rs.getString("chunk_id"), rs.getString("document_id"),
                rs.getString("document_version_id"),
                ChunkRole.valueOf(rs.getString("chunk_role").toUpperCase(Locale.ROOT)),
                rs.getString("parent_chunk_id"), rs.getInt("ordinal"), rs.getString("content"),
                readAcl(rs.getString("acl_json")), instant(rs, "valid_from"),
                instant(rs, "valid_until"), metadata, status(rs.getString("status")));
    }

    private KnowledgeEntity readEntity(ResultSet rs) throws SQLException {
        return new KnowledgeEntity(
                rs.getString("entity_id"), rs.getString("document_id"),
                rs.getString("document_version_id"), rs.getString("entity_type"),
                rs.getString("canonical_name"),
                read(rs.getString("aliases"), STRING_LIST),
                rs.getString("evidence_chunk_id"),
                readAcl(rs.getString("acl_json")), instant(rs, "valid_from"),
                instant(rs, "valid_until"), status(rs.getString("status")),
                instant(rs, "created_at"));
    }

    private KnowledgeRelation readRelation(ResultSet rs) throws SQLException {
        return new KnowledgeRelation(
                rs.getString("relation_id"), rs.getString("document_id"),
                rs.getString("document_version_id"), rs.getString("from_entity_id"),
                rs.getString("relation_type"), rs.getString("to_entity_id"),
                rs.getString("evidence_chunk_id"), readAcl(rs.getString("acl_json")),
                rs.getDouble("confidence"), instant(rs, "valid_from"),
                instant(rs, "valid_until"), status(rs.getString("status")),
                instant(rs, "created_at"));
    }

    private KnowledgeTermProjection readTermProjection(ResultSet rs) throws SQLException {
        return new KnowledgeTermProjection(
                rs.getString("chunk_id"), rs.getString("document_id"),
                rs.getString("document_version_id"), readAcl(rs.getString("acl_json")),
                read(rs.getString("term_frequencies_json"), TERM_FREQUENCIES),
                rs.getInt("token_count"));
    }

    private KnowledgeVectorProjection readVectorProjection(ResultSet rs) throws SQLException {
        return new KnowledgeVectorProjection(
                rs.getString("chunk_id"), rs.getString("document_id"),
                rs.getString("document_version_id"), readAcl(rs.getString("acl_json")),
                rs.getString("embedding_model"),
                read(rs.getString("embedding_text"), EMBEDDING));
    }

    private void initializeSchema() {
        if (jdbc.getDataSource() == null) {
            throw new IllegalStateException("PostgreSQL knowledge repository requires a datasource");
        }
        ResourceDatabasePopulator populator =
                new ResourceDatabasePopulator(new ClassPathResource("db/knowledge-runtime.sql"));
        populator.setContinueOnError(false);
        populator.execute(jdbc.getDataSource());
    }

    private <T> Optional<T> one(String sql, SqlReader<T> reader, Object... arguments) {
        List<T> values = jdbc.query(sql, (rs, rowNum) -> reader.read(rs), arguments);
        if (values.size() > 1) throw new IllegalStateException("expected at most one knowledge row");
        return values.stream().findFirst();
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("failed to serialize knowledge value", error);
        }
    }

    private KnowledgeAcl readAcl(String value) {
        try {
            return mapper.readValue(value, KnowledgeAcl.class);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("failed to deserialize knowledge ACL", error);
        }
    }

    private <T> T read(String value, TypeReference<T> type) {
        try {
            return mapper.readValue(value, type);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("failed to deserialize knowledge projection", error);
        }
    }

    private static KnowledgeStatus status(String value) {
        return KnowledgeStatus.valueOf(value.toUpperCase(Locale.ROOT));
    }

    private static String wire(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column).toInstant();
    }

    private static Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Integer nullableInteger(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static String vectorLiteral(List<Double> embedding) {
        return embedding.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(",", "[", "]"));
    }

    private static String placeholders(int count, String placeholder) {
        if (count < 1) throw new IllegalArgumentException("placeholder count must be positive");
        return java.util.Collections.nCopies(count, placeholder).stream()
                .collect(Collectors.joining(","));
    }

    private static String id(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    private record SearchScope(
            List<String> versionIds,
            List<String> aclHashes) {
        private boolean empty() {
            return versionIds.isEmpty() || aclHashes.isEmpty();
        }
    }

    @FunctionalInterface
    private interface SqlReader<T> {
        T read(ResultSet resultSet) throws SQLException;
    }
}

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS knowledge_document (
    document_id VARCHAR(128) PRIMARY KEY,
    source_id VARCHAR(128) NOT NULL,
    page_id VARCHAR(255) NOT NULL,
    title TEXT NOT NULL,
    source_uri TEXT NOT NULL CHECK (length(source_uri) > 0),
    canonical_uri TEXT NOT NULL CHECK (length(canonical_uri) > 0),
    language VARCHAR(32) NOT NULL CHECK (length(language) > 0),
    project_id VARCHAR(128) NOT NULL CHECK (length(project_id) > 0),
    source_parent_id VARCHAR(255),
    linked_document_ids JSONB NOT NULL DEFAULT '[]'::jsonb
        CONSTRAINT ck_knowledge_document_linked_ids
        CHECK (jsonb_typeof(linked_document_ids) = 'array'),
    current_active_version_id VARCHAR(128),
    acl_hash CHAR(64) NOT NULL,
    acl_json JSONB NOT NULL,
    status VARCHAR(32) NOT NULL
        CHECK (status IN ('building', 'active', 'superseded', 'failed', 'deleted')),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ,
    UNIQUE (source_id, page_id),
    CHECK ((status = 'deleted' AND deleted_at IS NOT NULL)
        OR (status <> 'deleted' AND deleted_at IS NULL))
);

ALTER TABLE knowledge_document
    ADD COLUMN IF NOT EXISTS source_uri TEXT;
ALTER TABLE knowledge_document
    ADD COLUMN IF NOT EXISTS canonical_uri TEXT;
ALTER TABLE knowledge_document
    ADD COLUMN IF NOT EXISTS language VARCHAR(32);
ALTER TABLE knowledge_document
    ADD COLUMN IF NOT EXISTS project_id VARCHAR(128);
ALTER TABLE knowledge_document
    ADD COLUMN IF NOT EXISTS source_parent_id VARCHAR(255);
ALTER TABLE knowledge_document
    ADD COLUMN IF NOT EXISTS linked_document_ids JSONB NOT NULL DEFAULT '[]'::jsonb;
UPDATE knowledge_document
SET source_uri = COALESCE(source_uri, source_id || '://' || page_id),
    canonical_uri = COALESCE(canonical_uri, source_id || '://' || page_id),
    language = COALESCE(language, 'und'),
    project_id = COALESCE(project_id, source_id),
    linked_document_ids = COALESCE(linked_document_ids, '[]'::jsonb)
WHERE source_uri IS NULL
   OR canonical_uri IS NULL
   OR language IS NULL
   OR project_id IS NULL
   OR linked_document_ids IS NULL;
ALTER TABLE knowledge_document ALTER COLUMN source_uri SET NOT NULL;
ALTER TABLE knowledge_document ALTER COLUMN canonical_uri SET NOT NULL;
ALTER TABLE knowledge_document ALTER COLUMN language SET NOT NULL;
ALTER TABLE knowledge_document ALTER COLUMN project_id SET NOT NULL;
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'ck_knowledge_document_linked_ids'
          AND conrelid = 'knowledge_document'::regclass
    ) THEN
        ALTER TABLE knowledge_document
            ADD CONSTRAINT ck_knowledge_document_linked_ids
            CHECK (jsonb_typeof(linked_document_ids) = 'array');
    END IF;
END
$$;

CREATE TABLE IF NOT EXISTS knowledge_document_version (
    document_version_id VARCHAR(128) PRIMARY KEY,
    document_id VARCHAR(128) NOT NULL
        REFERENCES knowledge_document(document_id),
    version_number BIGINT NOT NULL CHECK (version_number > 0),
    content_hash CHAR(64) NOT NULL
        CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    parser_revision VARCHAR(128) NOT NULL,
    chunker_revision VARCHAR(128) NOT NULL,
    embedding_revision VARCHAR(128) NOT NULL,
    acl_hash CHAR(64) NOT NULL,
    acl_json JSONB NOT NULL,
    status VARCHAR(32) NOT NULL
        CHECK (status IN ('building', 'active', 'superseded', 'failed', 'deleted')),
    source_last_edited_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    superseded_at TIMESTAMPTZ,
    failed_at TIMESTAMPTZ,
    failure_reason TEXT,
    UNIQUE (document_id, version_number),
    UNIQUE (document_version_id, document_id, acl_hash),
    CHECK ((status <> 'active') OR published_at IS NOT NULL),
    CHECK ((status <> 'failed') OR (failed_at IS NOT NULL AND failure_reason IS NOT NULL))
);

ALTER TABLE knowledge_document_version
    ADD COLUMN IF NOT EXISTS parser_revision VARCHAR(128);
ALTER TABLE knowledge_document_version
    ADD COLUMN IF NOT EXISTS chunker_revision VARCHAR(128);
ALTER TABLE knowledge_document_version
    ADD COLUMN IF NOT EXISTS embedding_revision VARCHAR(128);
UPDATE knowledge_document_version
SET parser_revision = COALESCE(parser_revision, 'meguri-source-v1'),
    chunker_revision = COALESCE(chunker_revision, 'meguri-parent-child-v1'),
    embedding_revision = COALESCE(embedding_revision, 'meguri-feature-hash-v1')
WHERE parser_revision IS NULL
   OR chunker_revision IS NULL
   OR embedding_revision IS NULL;
ALTER TABLE knowledge_document_version ALTER COLUMN parser_revision SET NOT NULL;
ALTER TABLE knowledge_document_version ALTER COLUMN chunker_revision SET NOT NULL;
ALTER TABLE knowledge_document_version ALTER COLUMN embedding_revision SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_knowledge_version_document_identity
    ON knowledge_document_version (document_version_id, document_id);

ALTER TABLE knowledge_document
    ADD COLUMN IF NOT EXISTS current_active_version_id VARCHAR(128);
UPDATE knowledge_document d
SET current_active_version_id = v.document_version_id
FROM knowledge_document_version v
WHERE v.document_id = d.document_id
  AND v.status = 'active'
  AND d.current_active_version_id IS DISTINCT FROM v.document_version_id;
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'fk_knowledge_document_current_active_version'
          AND conrelid = 'knowledge_document'::regclass
    ) THEN
        ALTER TABLE knowledge_document
            ADD CONSTRAINT fk_knowledge_document_current_active_version
            FOREIGN KEY (current_active_version_id, document_id)
            REFERENCES knowledge_document_version(document_version_id, document_id);
    END IF;
END
$$;

CREATE UNIQUE INDEX IF NOT EXISTS uq_knowledge_version_active
    ON knowledge_document_version (document_id)
    WHERE status = 'active';

CREATE INDEX IF NOT EXISTS ix_knowledge_version_hash
    ON knowledge_document_version (document_id, content_hash, status);

CREATE TABLE IF NOT EXISTS knowledge_chunk (
    chunk_id VARCHAR(128) PRIMARY KEY,
    document_id VARCHAR(128) NOT NULL,
    document_version_id VARCHAR(128) NOT NULL,
    chunk_role VARCHAR(16) NOT NULL CHECK (chunk_role IN ('parent', 'child')),
    parent_chunk_id VARCHAR(128),
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
    content TEXT NOT NULL CHECK (length(content) > 0),
    section_path JSONB NOT NULL DEFAULT '[]'::jsonb
        CONSTRAINT ck_knowledge_chunk_section_path
        CHECK (jsonb_typeof(section_path) = 'array'),
    heading TEXT,
    source_start INTEGER,
    source_end INTEGER,
    content_sha256 CHAR(64) NOT NULL
        CONSTRAINT ck_knowledge_chunk_content_sha256
        CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    acl_hash CHAR(64) NOT NULL,
    acl_json JSONB NOT NULL,
    valid_from TIMESTAMPTZ NOT NULL,
    valid_until TIMESTAMPTZ NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'building'
        CHECK (status IN ('building', 'active', 'superseded', 'failed', 'deleted')),
    term_frequencies_json JSONB,
    token_count INTEGER NOT NULL
        CONSTRAINT ck_knowledge_chunk_token_count CHECK (token_count > 0),
    normalized_terms TEXT,
    search_vector TSVECTOR GENERATED ALWAYS AS
        (to_tsvector('simple', COALESCE(normalized_terms, ''))) STORED,
    embedding_model VARCHAR(128),
    embedding_revision VARCHAR(128),
    embedding_dimensions INTEGER,
    embedding VECTOR(64),
    FOREIGN KEY (document_version_id, document_id, acl_hash)
        REFERENCES knowledge_document_version(document_version_id, document_id, acl_hash),
    UNIQUE (chunk_id, document_version_id, acl_hash),
    UNIQUE (chunk_id, document_version_id, acl_hash, valid_from, valid_until),
    FOREIGN KEY (parent_chunk_id, document_version_id, acl_hash, valid_from, valid_until)
        REFERENCES knowledge_chunk(
            chunk_id, document_version_id, acl_hash, valid_from, valid_until),
    CHECK ((chunk_role = 'parent' AND parent_chunk_id IS NULL)
        OR (chunk_role = 'child' AND parent_chunk_id IS NOT NULL)),
    CONSTRAINT ck_knowledge_chunk_source_offsets
    CHECK ((source_start IS NULL AND source_end IS NULL)
        OR (source_start >= 0 AND source_end > source_start)),
    CHECK (valid_until > valid_from)
);

ALTER TABLE knowledge_chunk
    ADD COLUMN IF NOT EXISTS status VARCHAR(32) NOT NULL DEFAULT 'active';
ALTER TABLE knowledge_chunk
    ADD COLUMN IF NOT EXISTS section_path JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE knowledge_chunk
    ADD COLUMN IF NOT EXISTS heading TEXT;
ALTER TABLE knowledge_chunk
    ADD COLUMN IF NOT EXISTS source_start INTEGER;
ALTER TABLE knowledge_chunk
    ADD COLUMN IF NOT EXISTS source_end INTEGER;
ALTER TABLE knowledge_chunk
    ADD COLUMN IF NOT EXISTS content_sha256 CHAR(64);
ALTER TABLE knowledge_chunk
    ADD COLUMN IF NOT EXISTS term_frequencies_json JSONB;
ALTER TABLE knowledge_chunk
    ADD COLUMN IF NOT EXISTS token_count INTEGER;
ALTER TABLE knowledge_chunk
    ADD COLUMN IF NOT EXISTS normalized_terms TEXT;
ALTER TABLE knowledge_chunk
    ADD COLUMN IF NOT EXISTS search_vector TSVECTOR GENERATED ALWAYS AS
        (to_tsvector('simple', COALESCE(normalized_terms, ''))) STORED;
ALTER TABLE knowledge_chunk
    ADD COLUMN IF NOT EXISTS embedding_model VARCHAR(128);
ALTER TABLE knowledge_chunk
    ADD COLUMN IF NOT EXISTS embedding_revision VARCHAR(128);
ALTER TABLE knowledge_chunk
    ADD COLUMN IF NOT EXISTS embedding_dimensions INTEGER;
ALTER TABLE knowledge_chunk
    ADD COLUMN IF NOT EXISTS embedding VECTOR(64);
ALTER TABLE knowledge_chunk ALTER COLUMN status SET DEFAULT 'building';

UPDATE knowledge_chunk
SET section_path = COALESCE(section_path, '[]'::jsonb),
    content_sha256 = COALESCE(
        content_sha256, encode(digest(content, 'sha256'), 'hex')),
    token_count = COALESCE(
        token_count,
        GREATEST(
            1,
            cardinality(regexp_split_to_array(trim(content), E'\\s+'))
        )
    )
WHERE section_path IS NULL
   OR content_sha256 IS NULL
   OR token_count IS NULL;
UPDATE knowledge_chunk c
SET embedding_revision = v.embedding_revision
FROM knowledge_document_version v
WHERE v.document_version_id = c.document_version_id
  AND c.chunk_role = 'child'
  AND c.embedding_revision IS NULL;
UPDATE knowledge_chunk
SET embedding_revision = NULL
WHERE chunk_role = 'parent'
  AND embedding_revision IS NOT NULL;
ALTER TABLE knowledge_chunk ALTER COLUMN content_sha256 SET NOT NULL;
ALTER TABLE knowledge_chunk ALTER COLUMN token_count SET NOT NULL;
ALTER TABLE knowledge_chunk ALTER COLUMN embedding_revision DROP NOT NULL;
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'ck_knowledge_chunk_embedding_revision'
          AND conrelid = 'knowledge_chunk'::regclass
    ) THEN
        ALTER TABLE knowledge_chunk
            ADD CONSTRAINT ck_knowledge_chunk_embedding_revision
            CHECK ((chunk_role = 'parent' AND embedding_revision IS NULL)
                OR (chunk_role = 'child' AND embedding_revision IS NOT NULL));
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'ck_knowledge_chunk_section_path'
          AND conrelid = 'knowledge_chunk'::regclass
    ) THEN
        ALTER TABLE knowledge_chunk
            ADD CONSTRAINT ck_knowledge_chunk_section_path
            CHECK (jsonb_typeof(section_path) = 'array');
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'ck_knowledge_chunk_content_sha256'
          AND conrelid = 'knowledge_chunk'::regclass
    ) THEN
        ALTER TABLE knowledge_chunk
            ADD CONSTRAINT ck_knowledge_chunk_content_sha256
            CHECK (content_sha256 ~ '^[0-9a-f]{64}$');
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'ck_knowledge_chunk_token_count'
          AND conrelid = 'knowledge_chunk'::regclass
    ) THEN
        ALTER TABLE knowledge_chunk
            ADD CONSTRAINT ck_knowledge_chunk_token_count
            CHECK (token_count > 0);
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'ck_knowledge_chunk_source_offsets'
          AND conrelid = 'knowledge_chunk'::regclass
    ) THEN
        ALTER TABLE knowledge_chunk
            ADD CONSTRAINT ck_knowledge_chunk_source_offsets
            CHECK ((source_start IS NULL AND source_end IS NULL)
                OR (source_start >= 0 AND source_end > source_start));
    END IF;
END
$$;

UPDATE knowledge_chunk c
SET status = v.status
FROM knowledge_document_version v
WHERE v.document_version_id = c.document_version_id
  AND c.status IS DISTINCT FROM v.status;

DO $$
BEGIN
    IF to_regclass('knowledge_chunk_term_projection') IS NOT NULL THEN
        EXECUTE $migration$
            UPDATE knowledge_chunk c
            SET term_frequencies_json = p.term_frequencies_json,
                token_count = p.token_count,
                normalized_terms = p.normalized_terms
            FROM knowledge_chunk_term_projection p
            WHERE p.chunk_id = c.chunk_id
              AND c.term_frequencies_json IS NULL
        $migration$;
    END IF;
    IF to_regclass('knowledge_chunk_vector_projection') IS NOT NULL THEN
        EXECUTE $migration$
            UPDATE knowledge_chunk c
            SET embedding_model = p.embedding_model,
                embedding_dimensions = p.dimensions,
                embedding = p.embedding_json::text::vector
            FROM knowledge_chunk_vector_projection p
            WHERE p.chunk_id = c.chunk_id
              AND c.embedding IS NULL
        $migration$;
    END IF;
END
$$;

CREATE INDEX IF NOT EXISTS ix_knowledge_chunk_recall
    ON knowledge_chunk (
        document_version_id, acl_hash, status, valid_from, valid_until, ordinal)
    WHERE chunk_role = 'child' AND status = 'active';

CREATE INDEX IF NOT EXISTS ix_knowledge_chunk_parent
    ON knowledge_chunk (parent_chunk_id)
    WHERE chunk_role = 'child';

CREATE UNIQUE INDEX IF NOT EXISTS uq_knowledge_chunk_projection_scope
    ON knowledge_chunk (chunk_id, document_version_id, acl_hash, chunk_role);

CREATE INDEX IF NOT EXISTS ix_knowledge_chunk_search
    ON knowledge_chunk USING GIN (search_vector)
    WHERE chunk_role = 'child' AND status = 'active';

CREATE INDEX IF NOT EXISTS ix_knowledge_chunk_embedding_hnsw
    ON knowledge_chunk USING HNSW (embedding vector_cosine_ops)
    WHERE chunk_role = 'child' AND status = 'active' AND embedding IS NOT NULL;

CREATE TABLE IF NOT EXISTS knowledge_entity (
    entity_id VARCHAR(128) PRIMARY KEY,
    document_id VARCHAR(128) NOT NULL,
    document_version_id VARCHAR(128) NOT NULL,
    entity_type VARCHAR(128) NOT NULL,
    canonical_name TEXT NOT NULL,
    aliases JSONB NOT NULL DEFAULT '[]'::jsonb
        CHECK (jsonb_typeof(aliases) = 'array'),
    evidence_chunk_id VARCHAR(128) NOT NULL,
    acl_hash CHAR(64) NOT NULL,
    acl_json JSONB NOT NULL,
    valid_from TIMESTAMPTZ NOT NULL,
    valid_until TIMESTAMPTZ NOT NULL,
    status VARCHAR(32) NOT NULL
        CHECK (status IN ('building', 'active', 'superseded', 'failed', 'deleted')),
    created_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (document_version_id, document_id, acl_hash)
        REFERENCES knowledge_document_version(document_version_id, document_id, acl_hash),
    FOREIGN KEY (evidence_chunk_id, document_version_id, acl_hash)
        REFERENCES knowledge_chunk(chunk_id, document_version_id, acl_hash),
    UNIQUE (entity_id, document_version_id, acl_hash)
);

ALTER TABLE knowledge_entity
    ADD COLUMN IF NOT EXISTS aliases JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE knowledge_entity
    ADD COLUMN IF NOT EXISTS valid_from TIMESTAMPTZ;
ALTER TABLE knowledge_entity
    ADD COLUMN IF NOT EXISTS valid_until TIMESTAMPTZ;
ALTER TABLE knowledge_entity
    ADD COLUMN IF NOT EXISTS status VARCHAR(32) NOT NULL DEFAULT 'active';
UPDATE knowledge_entity e
SET aliases = jsonb_build_array(e.canonical_name)
WHERE e.aliases = '[]'::jsonb;
UPDATE knowledge_entity e
SET valid_from = c.valid_from,
    valid_until = c.valid_until,
    status = v.status
FROM knowledge_chunk c, knowledge_document_version v
WHERE c.chunk_id = e.evidence_chunk_id
  AND v.document_version_id = e.document_version_id
  AND (e.valid_from IS NULL OR e.valid_until IS NULL
       OR e.status IS DISTINCT FROM v.status);
ALTER TABLE knowledge_entity ALTER COLUMN valid_from SET NOT NULL;
ALTER TABLE knowledge_entity ALTER COLUMN valid_until SET NOT NULL;
ALTER TABLE knowledge_entity ALTER COLUMN status SET DEFAULT 'building';

CREATE INDEX IF NOT EXISTS ix_knowledge_entity_lookup
    ON knowledge_entity (document_version_id, acl_hash, entity_type, canonical_name);

CREATE TABLE IF NOT EXISTS knowledge_relation (
    relation_id VARCHAR(128) PRIMARY KEY,
    document_id VARCHAR(128) NOT NULL,
    document_version_id VARCHAR(128) NOT NULL,
    from_entity_id VARCHAR(128) NOT NULL,
    relation_type VARCHAR(128) NOT NULL,
    to_entity_id VARCHAR(128) NOT NULL,
    evidence_chunk_id VARCHAR(128) NOT NULL,
    acl_hash CHAR(64) NOT NULL,
    acl_json JSONB NOT NULL,
    confidence DOUBLE PRECISION NOT NULL
        CHECK (confidence >= 0 AND confidence <= 1),
    valid_from TIMESTAMPTZ NOT NULL,
    valid_until TIMESTAMPTZ NOT NULL,
    status VARCHAR(32) NOT NULL
        CHECK (status IN ('building', 'active', 'superseded', 'failed', 'deleted')),
    created_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (document_version_id, document_id, acl_hash)
        REFERENCES knowledge_document_version(document_version_id, document_id, acl_hash),
    FOREIGN KEY (from_entity_id, document_version_id, acl_hash)
        REFERENCES knowledge_entity(entity_id, document_version_id, acl_hash),
    FOREIGN KEY (to_entity_id, document_version_id, acl_hash)
        REFERENCES knowledge_entity(entity_id, document_version_id, acl_hash),
    FOREIGN KEY (evidence_chunk_id, document_version_id, acl_hash)
        REFERENCES knowledge_chunk(chunk_id, document_version_id, acl_hash)
);

ALTER TABLE knowledge_relation
    ADD COLUMN IF NOT EXISTS confidence DOUBLE PRECISION NOT NULL DEFAULT 1.0;
ALTER TABLE knowledge_relation
    ADD COLUMN IF NOT EXISTS valid_from TIMESTAMPTZ;
ALTER TABLE knowledge_relation
    ADD COLUMN IF NOT EXISTS valid_until TIMESTAMPTZ;
ALTER TABLE knowledge_relation
    ADD COLUMN IF NOT EXISTS status VARCHAR(32) NOT NULL DEFAULT 'active';
UPDATE knowledge_relation r
SET valid_from = c.valid_from,
    valid_until = c.valid_until,
    status = v.status
FROM knowledge_chunk c, knowledge_document_version v
WHERE c.chunk_id = r.evidence_chunk_id
  AND v.document_version_id = r.document_version_id
  AND (r.valid_from IS NULL OR r.valid_until IS NULL
       OR r.status IS DISTINCT FROM v.status);
ALTER TABLE knowledge_relation ALTER COLUMN valid_from SET NOT NULL;
ALTER TABLE knowledge_relation ALTER COLUMN valid_until SET NOT NULL;
ALTER TABLE knowledge_relation ALTER COLUMN status SET DEFAULT 'building';

CREATE INDEX IF NOT EXISTS ix_knowledge_relation_lookup
    ON knowledge_relation (
        document_version_id, acl_hash, status, from_entity_id, relation_type);

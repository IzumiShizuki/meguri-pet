CREATE TABLE IF NOT EXISTS meguri_retrieval_trace (
    trace_id TEXT PRIMARY KEY,
    snapshot_id TEXT NOT NULL,
    knowledge_revision BIGINT NOT NULL CHECK (knowledge_revision >= 0),
    valid_at TIMESTAMPTZ NOT NULL,
    algorithm_revision TEXT NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    trace_json JSONB NOT NULL
);

ALTER TABLE meguri_retrieval_trace
    ADD COLUMN IF NOT EXISTS valid_at TIMESTAMPTZ;

UPDATE meguri_retrieval_trace
SET valid_at = completed_at
WHERE valid_at IS NULL;

ALTER TABLE meguri_retrieval_trace
    ALTER COLUMN valid_at SET NOT NULL;

ALTER TABLE meguri_retrieval_trace
    ADD COLUMN IF NOT EXISTS algorithm_revision TEXT;

UPDATE meguri_retrieval_trace
SET algorithm_revision = 'legacy-unversioned'
WHERE algorithm_revision IS NULL OR algorithm_revision = '';

ALTER TABLE meguri_retrieval_trace
    ALTER COLUMN algorithm_revision SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_meguri_retrieval_trace_completed
    ON meguri_retrieval_trace (completed_at DESC);

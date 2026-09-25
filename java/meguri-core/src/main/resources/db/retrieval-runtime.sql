CREATE TABLE IF NOT EXISTS meguri_retrieval_trace (
    trace_id TEXT PRIMARY KEY,
    snapshot_id TEXT NOT NULL,
    knowledge_revision BIGINT NOT NULL CHECK (knowledge_revision >= 0),
    valid_at TIMESTAMPTZ NOT NULL,
    algorithm_revision TEXT NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    projection_version TEXT NOT NULL DEFAULT 'safe-retrieval-trace-v1',
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

ALTER TABLE meguri_retrieval_trace
    ADD COLUMN IF NOT EXISTS projection_version TEXT;

UPDATE meguri_retrieval_trace
SET projection_version = 'legacy-content-bearing'
WHERE projection_version IS NULL OR projection_version = '';

-- Remove query and evidence text left by pre-projection releases. Legacy rows retain
-- their stable trace identity and snapshot metadata but cannot replay old content.
UPDATE meguri_retrieval_trace
SET trace_json = jsonb_build_object(
        'projectionVersion', 'safe-retrieval-trace-v1',
        'traceId', trace_id,
        'plan', jsonb_build_object(
            'mode', 'NONE', 'queryDigest', 'legacy-redacted',
            'sources', jsonb_build_array(), 'sourceBudgets', jsonb_build_object(),
            'sourceSeats', jsonb_build_object(), 'graphEnabled', false,
            'graphMaxHops', 0, 'totalItemLimit', 0, 'deadline', completed_at),
        'snapshotId', snapshot_id, 'revision', knowledge_revision,
        'validAt', valid_at, 'algorithmRevision', algorithm_revision,
        'completedAt', completed_at, 'items', jsonb_build_array(),
        'lanes', jsonb_build_object(), 'ranks', jsonb_build_object(),
        'candidates', jsonb_build_array(),
        'degradations', jsonb_build_array('legacy_trace_redacted')),
    projection_version = 'safe-retrieval-trace-v1'
WHERE projection_version = 'legacy-content-bearing';

ALTER TABLE meguri_retrieval_trace
    ALTER COLUMN projection_version SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_meguri_retrieval_trace_completed
    ON meguri_retrieval_trace (completed_at DESC);

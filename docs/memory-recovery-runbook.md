# Memory backup restore validation

The environment workflow owns backup creation and isolated staging restore. After restore, run the read-only domain validator against the temporary database before cleanup:

```powershell
$env:MEGURI_ENV = 'staging'
$env:MEGURI_DATABASE_URL_FILE = 'C:\absolute\path\to\restored-app-url.txt'
$env:MEGURI_DATABASE_REVISION = '20260714_0004'
$env:MEGURI_EMBEDDING_MODEL_REVISION = '5617a9f61b028005a4858fdac845db406aefb181'
& 'D:\environment\anaconda3\envs\py314\python.exe' scripts\validate_memory_recovery.py `
  --recall-corpus 'C:\absolute\path\to\approved-memory-recall-corpus.json' `
  --require-fixed-recall
```

The validator emits JSON and exits non-zero on revision drift, invalid current-version pointers, active items without versions, ready-embedding hash mismatch, audit replay mismatch, missing required corpus or any failed recall case. It reports table counts for all nine required tables and never prints the database URL or memory content. Recall output contains case identifiers and aggregate counts only.

The approved JSON corpus has this shape. IDs must come from the known staging fixture or pre-backup snapshot; optional `expected_version_ids` proves that the restored current-version pointer is the one recalled. By default the corpus must contain at least one `modes: ["exact_vector"]` case, so the staging gate cannot pass using keyword fallback alone.

```json
{
  "corpus_id": "staging-memory-restore-v1",
  "cases": [
    {
      "case_id": "preference-current-version",
      "tenant_id": "meguri-staging",
      "user_id": "fixture-user",
      "query": "known fixture preference",
      "expected_memory_ids": ["00000000-0000-0000-0000-000000000001"],
      "expected_version_ids": {
        "00000000-0000-0000-0000-000000000001": "00000000-0000-0000-0000-000000000002"
      },
      "minimum_recall_at_k": 1.0,
      "limit": 5,
      "modes": ["exact_vector"]
    }
  ]
}
```

Set `MEGURI_TEST_RECOVERY_RECALL_CORPUS` to the same file and `MEGURI_REQUIRE_RECOVERY_RECALL=true` when running the integration suite against the restored target. The staging restore rehearsal must additionally record archive checksum/size, PostgreSQL and pgvector versions, source and isolated target identities, start/end times, cleanup proof, RPO and RTO. Production restore is not authorized by this runbook.

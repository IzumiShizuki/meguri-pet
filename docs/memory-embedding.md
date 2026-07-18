# Memory embedding contract

- Model: `BAAI/bge-m3`
- Immutable revision: `5617a9f61b028005a4858fdac845db406aefb181`
- Dimension: 1024
- Content binding: SHA-256 of the immutable version text
- Baseline search: exact pgvector cosine distance
- ANN/HNSW: disabled

The revision is a full Hugging Face commit, not `main`, `latest` or a short floating alias. The adapter rejects a non-commit revision, the wrong vector count and any vector not exactly 1024 dimensions. The worker rereads version content before persistence and refuses a changed content hash.

Install the optional runtime dependency in the service image and pre-stage the pinned model in the configured cache. Normal service startup never downloads a model implicitly because `MEGURI_EMBEDDING_LOCAL_FILES_ONLY` defaults to `true`.

```powershell
& 'D:\environment\anaconda3\envs\py314\python.exe' -m pip install -e '.[embedding]'
$env:MEGURI_EMBEDDING_CACHE_DIR = 'D:\absolute\model-cache'
& 'D:\environment\anaconda3\envs\py314\python.exe' scripts\run_memory_embedding_worker.py --worker-id memory-worker-01 --batch-size 20
```

`MEGURI_EMBEDDING_BACKEND=disabled` deliberately disables vector generation. `MEGURI_EMBEDDING_MODEL_REVISION` must equal the release revision; a mismatch fails provider construction instead of mixing vector spaces. The worker command processes one bounded batch so a scheduler or service supervisor owns repetition, shutdown and restart policy.

Approval commits item, version, audit and outbox even when the embedding service is unavailable. The outbox worker retries with bounded exponential delay and dead-letters after the configured maximum; memory failure must not block text generation. Exact keyword/structured paths remain available while a vector is pending.

The pinned revision is visible in the [BAAI/bge-m3 commit history](https://huggingface.co/BAAI/bge-m3/commits/5617a9f61b028005a4858fdac845db406aefb181). Changing it requires re-embedding, release-manifest review and fixed-recall comparison; it is not a configuration-only rollout.

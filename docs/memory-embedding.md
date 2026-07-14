# Memory embedding contract

- Model: `BAAI/bge-m3`
- Immutable revision: `5617a9f61b028005a4858fdac845db406aefb181`
- Dimension: 1024
- Content binding: SHA-256 of the immutable version text
- Baseline search: exact pgvector cosine distance
- ANN/HNSW: disabled

The revision is a full Hugging Face commit, not `main`, `latest` or a short floating alias. The adapter rejects a non-commit revision, the wrong vector count and any vector not exactly 1024 dimensions. The worker rereads version content before persistence and refuses a changed content hash.

Approval commits item, version, audit and outbox even when the embedding service is unavailable. The outbox worker retries with bounded exponential delay and dead-letters after the configured maximum; memory failure must not block text generation. Exact keyword/structured paths remain available while a vector is pending.

The pinned revision is visible in the [BAAI/bge-m3 commit history](https://huggingface.co/BAAI/bge-m3/commits/5617a9f61b028005a4858fdac845db406aefb181). Changing it requires re-embedding, release-manifest review and fixed-recall comparison; it is not a configuration-only rollout.

# Memory embedding contract

- Provider: Alibaba Cloud Model Studio, HTTPS API only
- Model: `text-embedding-v4`
- Meguri release fingerprint: `dashscope-text-embedding-v4-2048-r20260725`
- Dimension: 2048
- Content binding: SHA-256 of the immutable version text
- Baseline search: exact pgvector cosine distance
- ANN/HNSW: disabled

`text-embedding-v4` runs at the provider: Meguri stores no embedding weights and sends requests only to the HTTPS Model Studio endpoint. Model Studio does not return an immutable commit hash, so Meguri records a controlled release fingerprint. Changing the upstream model, output dimension or validated configuration requires a new fingerprint and a full rebuild of the derived vectors. The adapter rejects malformed output, non-finite values and vectors that are not exactly 2048 dimensions.

The single DashScope secret is loaded only from `MEGURI_DASHSCOPE_API_KEY_FILE`. It is mounted in containers as `/run/secrets/dashscope_api_key`; inline keys are rejected. The same credential is used for `qwen3-rerank`, which reranks the bounded canonical-Lore and long-term-memory candidate sets after scope filtering.

```powershell
& '.\ops\scripts\import-dashscope-api-key.ps1' -SourceCsv 'D:\download\your-api-key.csv'
# Load only local retrieval settings; do not print the secret file.
Get-Content .\ops\env\dashscope.local.env | ForEach-Object {
  if ($_ -match '^([^#=]+)=(.*)$') { Set-Item -Path ("Env:" + $matches[1]) -Value $matches[2] }
}
& 'D:\environment\anaconda3\envs\py314\python.exe' scripts\build_dashscope_rag_vectors.py --data-root .\datasets\meguri
& 'D:\environment\anaconda3\envs\py314\python.exe' scripts\run_memory_embedding_worker.py --worker-id memory-worker-01 --batch-size 20
```

`MEGURI_EMBEDDING_BACKEND=dashscope` and `MEGURI_RERANK_BACKEND=dashscope` are the managed configuration. `MEGURI_EMBEDDING_MODEL_REVISION` must equal the release fingerprint; a mismatch fails provider construction instead of mixing vector spaces. The worker command processes one bounded batch so a scheduler or service supervisor owns repetition, shutdown and restart policy.

Approval commits item, version, audit and outbox even when the embedding service is unavailable. The outbox worker retries with bounded exponential delay and dead-letters after the configured maximum; memory failure must not block text generation. Exact keyword/structured paths remain available while a vector is pending.

The legacy local BGE-M3 adapter remains test-only/backward-compatible and is not selected by the managed environment. Switching back to it also requires a separate vector rebuild and release review.

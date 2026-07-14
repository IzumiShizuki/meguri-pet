# Memory retrieval performance evidence

Date: 2026-07-14

Command:

```text
python scripts/benchmark_memory_retrieval.py --corpus-size 500 --queries 40 --dimension 1024 --top-k 5 --seed 20260714
```

Measured deterministic in-process exact cosine baseline:

| Corpus | Queries | Dimension | top-k | p50 | p95 | p99 | Error rate | Recall@5 |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 500 | 40 | 1024 | 5 | 15.873 ms | 18.219 ms | 19.290 ms | 0.0% | 100.0% |

This measures the reproducible algorithm harness on the local machine, not PostgreSQL network/database latency. No dev or staging database URL was available, so database p50/p95/p99 and concurrency throughput are unmeasured.

HNSW status: **not enabled**. ANN latency, error rate and recall are intentionally null. Exact search remains the supported mode until a live corpus-size threshold and exact-vs-ANN recall comparison justify a separately reviewed migration.

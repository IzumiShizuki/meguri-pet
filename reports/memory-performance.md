# Memory retrieval performance evidence

Date: 2026-07-14

Command:

```text
python scripts/benchmark_memory_retrieval.py --corpus-size 500 --queries 40 --dimension 1024 --top-k 5 --seed 20260714
```

Measured in-process exact cosine snapshot over the deterministic corpus/query seed:

| Corpus | Queries | Dimension | top-k | p50 | p95 | p99 | Error rate | Recall@5 |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 500 | 40 | 1024 | 5 | 16.233 ms | 18.533 ms | 21.858 ms | 0.0% | 100.0% |

This measures the reproducible algorithm/corpus harness on the local machine; wall-clock latency naturally varies between runs and is not PostgreSQL network/database latency. No dev or staging database URL was available, so database p50/p95/p99 and concurrency throughput are unmeasured.

HNSW status: **not enabled**. ANN latency, error rate and recall are intentionally null. Exact search remains the supported mode until a live corpus-size threshold and exact-vs-ANN recall comparison justify a separately reviewed migration.

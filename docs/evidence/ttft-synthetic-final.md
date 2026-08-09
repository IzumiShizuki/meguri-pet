# TTFT synthetic comparison — 2026-08-01

Status: `MEASURED_SYNTHETIC`; production end-to-end TTFT remains `NOT_MEASURED`.

The benchmark used 15 warmups and 100 measured samples on Java 21.0.11 / Windows 11. The complete machine-readable result is `ttft-synthetic-final.json`.

| Comparison | Percentile | Baseline | Candidate | Reduction |
| --- | ---: | ---: | ---: | ---: |
| First-token coalescing seam | P50 | 61.747 ms | 15.439 ms | 46.308 ms |
| First-token coalescing seam | P95 | 62.005 ms | 15.550 ms | 46.455 ms |
| First-token coalescing seam | P99 | 62.309 ms | 15.697 ms | 46.612 ms |
| Controlled FAST-stage model | P50 | 61.993 ms | 15.442 ms | 46.552 ms |
| Controlled FAST-stage model | P95 | 62.290 ms | 15.584 ms | 46.706 ms |
| Controlled FAST-stage model | P99 | 64.948 ms | 16.010 ms | 48.938 ms |

The first comparison measures Provider-stream subscription to first chunk emission. The second is a controlled Reactor stage model and is not the integrated Turn FAST path.

Excluded from both comparisons: real Provider and queueing, PostgreSQL/network persistence, HTTP/SSE serialization and socket flush, reverse proxy buffering, client transport, and first visible render. These values must not be presented as production E2E TTFT.

# Framework bootstrap

## Current repository inventory

- The workspace was not a Git repository when phase 0 started.
- Existing content consisted of `data/`, `datasets/`, and the dataset builder under `scripts/`.
- The canonical data build is `meguri_v2_02c3db0c507d7c2d` with GO gates in `datasets/meguri/build_report.json`.
- No existing application framework, dependency manifest, AstrBot plugin, AIRI checkout, or deployment stack was present in this workspace.
- The canonical dataset and both legacy data directories remain unchanged by this bootstrap.

## Implementation plan and status

1. Phase 0: record architecture decisions and the production safety boundary. Complete.
2. Phase 1: implement a local FastAPI runtime, provider contracts, runtime state, semantic response schema, deterministic expression mapping, event streaming, and tests. Complete.
3. Local phase 2: validate the canonical build id at startup and read the official RAG/expression exports without modifying them. Complete.
4. AIRI adapter and PNG desktop renderer spike. Pending.
5. Offline AstrBot gateway plugin against mock platform events. Pending.
6. Existing MemoryOS compatibility evaluation through a read-only test scope. Pending and requires confirmation that the service has no production consumers.
7. Production deployment. Blocked by design until backup/restore, port exposure, pgvector, MemoryOS ownership, and explicit approval gates are resolved.

## First-phase file tree

```text
services/meguri_core/  FastAPI app, schemas, providers, state machine, orchestrator
tests/                 local contract and isolation tests
docs/adr/              architecture decisions
scripts/               existing dataset builder plus local run helper
datasets/meguri/       read-only canonical data source
```

## Resource estimate

- Local mock core: about 80-180 MiB RSS, negligible disk beyond source code.
- In-memory RAG metadata for 174 train chunks: below 10 MiB.
- Production core target: 256-512 MiB memory limit before real provider profiling.
- A separate PostgreSQL/pgvector container is not included in phase 1; expected budget would be at least 512 MiB plus index/data growth.
- AIRI desktop and local TTS are outside the server budget.

## Risks

- The expression labels are heuristic and still require visual review.
- The existing PostgreSQL 16.13 image has no pgvector extension.
- Existing MemoryOS ownership and external consumers are unknown; it has no observed additional authentication.
- The server exposes infrastructure ports publicly and has no complete, restoration-tested backup chain.
- The existing AstrBot instance is production state with plugins, databases, and snapshots; its host network and `/opt/astrbot/data` must remain untouched.
- This workspace was not initialized as Git, so the requested `feat/framework-bootstrap` branch could not be created locally.


# Production readiness and rollback gate

This document is a planning artifact only. Phase 1 permits local development and read-only server verification. It does not authorize a deployment, a restart, a migration, a firewall change, a Docker change, or a write to the existing AstrBot, MemoryOS, PostgreSQL, Redis, Kafka, Nacos, OpenResty or certificate state.

The machine-readable gate is `configs/production_gate.json`. The read-only checker is:

```powershell
D:\environment\anaconda3\envs\py314\python.exe scripts\check_production_gate.py
```

It must exit non-zero while any gate is false. A future deployment pipeline must consume this result and stop before acquiring mutation credentials.

## Required topology

- Meguri core runs as a separately versioned service on a bridge network.
- AstrBot remains the existing host-network instance. Its `/opt/astrbot/data`, plugins, database, snapshots and process lifecycle are not changed. A future gateway may call a loopback-bound core port such as `127.0.0.1:8100`.
- Website traffic enters through the existing authenticated reverse proxy. The core, MemoryOS, PostgreSQL, Redis, Kafka and Nacos ports are never published directly to the public internet.
- PostgreSQL and any future pgvector database use a dedicated Meguri database/schema only after backup and restore evidence. Installing pgvector into the existing PostgreSQL instance is not a phase-1 action.
- MemoryOS remains an external, file-backed service until its owner, consumers, authentication and restore path are confirmed. The shadow adapter is not a production write path.

## Go gates

Each gate needs dated evidence, an owner and a rollback reference. A verbal confirmation is insufficient.

1. Inventory every consumer, volume, port, DNS record, certificate, scheduled job and secret involved in the proposed change.
2. Produce an encrypted backup manifest for PostgreSQL, Redis, Kafka/Nacos configuration where applicable, OpenResty/certificate configuration, AstrBot metadata and MemoryOS files. Record checksums and retention.
3. Restore each backup into an isolated namespace or host and verify representative reads. A backup that has not been restored is not a passing gate.
4. Build and scan an immutable Meguri artifact identified by the canonical build ID. Keep the previous artifact available for rollback.
5. Apply schema changes only as reviewed, forward/backward-compatible migrations against the dedicated Meguri database. No shared-database extension or destructive migration is allowed.
6. Verify authentication and identity binding at the proxy and core boundary. The website must not self-assert a cross-platform user identity, and an internal core port must not become public.
7. Verify health, terminal-turn rate, SSE reconnect/gap rate, memory failure rate, logs with secrets/redacted prompts, disk usage and alert ownership.
8. Run a canary with a bounded user/session allowlist, then compare latency, error and event metrics against the baseline. No AstrBot production data is used as a test fixture.

## Rollback sequence

Rollback is a rehearsed sequence, not an emergency improvisation:

1. Stop routing new canary traffic to the Meguri artifact and keep the previous artifact available.
2. Preserve logs, event IDs, health responses and the exact artifact/build IDs for the incident record.
3. Repoint the proxy or local gateway to the previous artifact. Do not restart or reinstall AstrBot as a rollback mechanism.
4. If a dedicated Meguri schema changed, apply its reviewed down-migration or restore the dedicated database snapshot. Never restore over the shared production database without an explicit, separately approved plan.
5. Verify `/health`, one authenticated test turn, SSE terminal delivery, memory read/write behavior and proxy certificate/DNS state.
6. Keep the failed artifact and data snapshot quarantined for investigation. Do not delete evidence.

## Backup and recovery evidence

The evidence package must include backup command metadata, timestamps, source volume/database identifiers, checksums, encryption/secret handling, restore target, restore duration, row/file counts and a human-readable verification result. Recovery objectives (RPO/RTO) must be set before a production change; they cannot be inferred from the existing file-backed MemoryOS or exposed infrastructure ports.

Until this package exists, `configs/production_gate.json` must remain blocked and the local framework must continue using `FakeMemoryProvider` and loopback-only clients.

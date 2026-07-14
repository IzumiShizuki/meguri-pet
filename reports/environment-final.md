# Meguri environment isolation final report

> Date: 2026-07-15 (Asia/Shanghai)
>
> Branch: `feat/environment-isolation`
>
> Current Staging release: `meguri-staging-20260715-r002`
>
> Production mutation: **blocked**
>
> Full Staging application: **NO-GO pending authenticated DeepSeek smoke**

## Outcome

E-001 through E-009 and the environment/recovery portion of E-010 are
implemented and verified against a real isolated Staging deployment on
`111.228.35.186`. The server now has an independent `meguri-staging` Compose
project, edge/internal networks, pgvector database, app/migration roles, named
volume, file secrets, release state, checksummed backups, and last-good
rollback. Existing AstrBot, website, middleware, PostgreSQL and public routing
were left unchanged.

The task is not declared fully complete. The configured external DeepSeek
provider has no usable API credential, so real Turn/SSE/RAG/Memory integration
has not passed. Environment acceptance evidence remains fail-closed for that
single external gate, and all Production operations remain blocked.

## E-task status

| Task | Status | Runtime result |
| --- | --- | --- |
| E-001 | Complete | Explicit dev/Staging/Production projects; live Staging networks and volume are independently named |
| E-002 | Complete | Isolation checker and negative fixtures pass |
| E-003 | Complete | Immutable `r001`/`r002` Manifests pass readiness validation |
| E-004 | Complete for Staging | Empty pgvector database migrated to `20260714_0004`; unsafe migration fixture blocked core replacement |
| E-005 | Complete for Staging | Live readiness has zero failures; secrets are file-only and mode `0400` |
| E-006 | Complete | Staging core is loopback-only; ledger remains the Production exposure gate |
| E-007 | Complete for Staging | Explicit two-image rollback, image-fault rollback, and readiness-fault rollback passed |
| E-008 | Complete for Staging | Non-empty backup/restore/fingerprint rehearsal passed; RTO evidence recorded |
| E-009 | Complete | CI controls and manual Production approval gate are present and tested |
| E-010 | Environment checks complete; application smoke blocked | All required isolation/recovery booleans have evidence; authenticated DeepSeek Turn/SSE remains missing |

## Runtime topology

- `meguri-staging-core-1`: healthy, networks `meguri-staging-edge` and
  `meguri-staging-internal`, host binding `127.0.0.1:18080` only.
- `meguri-staging-postgres-1`: healthy, internal network only, no host port.
- `meguri-staging-postgres-data`: the only Meguri named volume.
- `/opt/meguri/staging`: independent config/data, secrets, logs, backups,
  releases, state and evidence directories.
- `MEGURI_MUTATION_ALLOWED=false`; no public proxy route was added.

Current image digests:

- Core `r002`: `sha256:02d3986e7a8453a9a25d7b64c3517aaaba35602c84cde618905125561b3001bb`.
- Core `r001` rollback target: `sha256:61ab40fb1a1c2a1a1e05cf097217c5cdde568b37c75d05b7e971f725d941a821`.
- Migration: `sha256:3d826edb697234cd53e48ae976834889ae5b90fee0e5eed725079fca779e5589`.
- PostgreSQL/pgvector: `sha256:1d533553fefe4f12e5d80c7b80622ba0c382abb5758856f52983d8789179f0fb`.

## Verification

Repository commands:

```text
python -m pytest -q
python -m unittest discover -v
pnpm test:ts
python ops/scripts/check_environment_isolation.py
docker-compose ... config --quiet
python -m unittest -v tests.test_staging_deployment tests.test_postgres_backup
```

Results:

- Local Pytest: 184 passed, with six expected database-gated skips. The gated
  provider/workflow/recovery subset was then copied
  into a disposable container on `meguri-staging-internal` and passed all 7
  live PostgreSQL cases with zero skips; the container was removed.
- Python unittest discovery: 128 passed.
- TypeScript: 20 passed.
- Remote-control-plane, migration, readiness, rollback and restore commands:
  passed as described in `reports/environment-completion-audit.md` and
  `reports/staging-restore-rehearsal.md`.
- A synthetic DeepSeek Turn failed closed with sanitized HTTP 401 evidence;
  no credential value appeared in output or logs.

## Restore and rollback

The authoritative non-empty archive is 35,922 bytes at SHA-256
`1bfdaab8ecc9eae974e723faf8eec78954bde349cabc1a5d21b9ce08367e33df`.
Restore to `meguri_staging_restore_20260715_0154` matched revision, pgvector,
nine table counts, active count and fingerprint, then cleaned up in 10.974 s.

Rollback evidence:

1. Deploy `r001` core digest `61ab40...`.
2. Deploy `r002` core digest `02d398...`.
3. Explicitly roll back to `r001`; state records `rollback_from=r002`.
4. Redeploy `r002`.
5. Inject nonexistent core digest; deployment fails and `r002` remains current.
6. Inject LLM base/Manifest mismatch; readiness returns 503 and `r002` is restored.
7. Inject unsafe app-role identity; migration fails before candidate core replacement.

## Protected services

Before/after inventory digests are
`f0391c00f39e138997cc19956a343a00f55f6ab719a18306af689f2cc2acfe7d` and
`0dbdbbbbd610d19cb7f85a0fc7a863e724eb2a7e2985381b334aff443d55af57`.
All 22 protected containers remained running and had start timestamps older
than the Staging window. No protected container, network, volume, database,
route, firewall rule, DNS record, certificate or proxy configuration changed.

## Commits

The original fine-grained E-001 through E-010 commits remain listed in Git
history. This runtime completion pass added:

- `5d1e709` remote image-build efficiency;
- `e8473e0` adapterless external DeepSeek release support;
- `575aba7` TLS remote-control-plane deploy/backup support;
- `60c8921` restored table-count and fixed-query verification;
- `604a07e` active-memory identity fingerprint verification.

The only unrelated worktree edit, `training/generate_tts_samples.py`, remains
unstaged and untouched.

## Handoffs

- `docs/contracts/environment-contract.md`
- `docs/contracts/memory-environment-handoff.md`
- `docs/contracts/llm-staging-handoff.md`
- `ops/contracts/memory-agent.environment-contract.json`
- `ops/contracts/llm-agent.environment-contract.json`

## Remaining and blocked operations

Required before full Staging GO:

1. Provision a dedicated DeepSeek key into
   `/opt/meguri/staging/secrets/llm-api-key.txt` without printing it.
2. Re-run real Turn, SSE reconnect/cancel, RAG and native MemoryProvider smoke.
3. Replace the blocked acceptance status only after those results pass.

Still prohibited without separate approval: Production migration or restore,
Production mutation, traffic switching, OpenResty/1Panel changes, firewall,
DNS, certificate or public-entry changes, existing PostgreSQL changes,
AstrBot changes, and deletion of any existing image, container or volume.

# Environment isolation implementation progress

## Baseline

- Branch: `feat/environment-isolation`
- Starting commit: `ad8d405bb30d055eb5ff7107beae29584efcf6bc`
- Baseline report commit: `4e91939`
- Data build ID: `meguri_v2_02c3db0c507d7c2d`
- Production mutation: blocked

## E-001 — repository and Compose baseline

- Status: completed
- Files: `.dockerignore`, `Dockerfile`, `ops/README.md`,
  `ops/compose/compose.base.yaml`, three environment overlays, three env
  examples, and `ops/secrets/README.md`.
- Safety: repository-only; no server mutation.
- Test command: `docker compose --project-name meguri-<env> --env-file
  ops/env/<env>.env.example -f ops/compose/compose.base.yaml -f
  ops/compose/compose.<env>.yaml config --quiet` for dev, staging, and
  production.
- Result: all three returned exit code 0. Resolved projects are
  `meguri-dev`, `meguri-staging`, and `meguri-production`; network names and
  PostgreSQL volume names are unique across environments.
- Open risks: current core does not yet expose `/health/live` or consume every
  `_FILE` secret; those are scheduled for E-005.

## E-002 — environment isolation static checker

- Status: completed
- Files: `ops/scripts/check_environment_isolation.py`, six committed fault
  fixtures, and `tests/test_environment_checker.py`.
- Checks: project identity, environment identity, edge/internal network
  identity, internal-only PostgreSQL, named database storage, cross-environment
  paths/networks/credentials, plaintext secret variables, production debug and
  mutation defaults, production build/port safety, and floating/`latest`
  images.
- Test commands:
  - `python ops/scripts/check_environment_isolation.py`
  - `python ops/scripts/check_environment_isolation.py --fixture <fixture>`
    for every file under `tests/fixtures/environment_isolation/`
  - `python -m unittest -v tests.test_environment_checker`
- Result: normal configuration returned 0; all six fault fixtures returned 1
  with their expected diagnostic code; 2 tests passed.
- Safety: static repository inspection only; no Docker daemon or server access.

## E-003 — Release Manifest schema, generator, and checker

- Status: completed
- Files: `ops/manifests/release-manifest.schema.json`, example manifest,
  `generate_release_manifest.py`, `check_release_manifest.py`, and
  `tests/test_release_manifest.py`.
- Required contract: release/environment/Git identity, named image digests,
  data build, Prompt/Response/expression hashes, database revision, embedding
  revision, LLM base/adapter revision, test status, and generation timestamp.
- Readiness behavior: staging/production checks fail on placeholders, non-passed
  tests, or any explicitly supplied runtime mismatch; there is no warning-only
  path.
- Test commands:
  - `python -m unittest -v tests.test_release_manifest`
  - `python ops/scripts/check_release_manifest.py
    ops/manifests/example.release-manifest.json`
  - same command with `--readiness` (expected nonzero for example placeholders)
  - `python ops/scripts/generate_release_manifest.py --help`
- Result: 4 tests passed; schema-only example returned 0; readiness returned 1
  and identified every placeholder; direct generator CLI loaded successfully.
- Safety: repository-only; generated test artifacts were temporary.

## E-004 - PostgreSQL migration job and least-privilege app role

- Status: implementation completed; empty-database runtime acceptance remains
  gated on the isolated staging deployment in E-008/E-010.
- Files: `Dockerfile.migration`, `alembic.ini`, `migrations/`,
  `ops/migration/`, migration Compose wiring, two additional per-environment
  secret contracts, a negative isolation fixture, and
  `tests/test_migration_job.py`.
- Ownership: PostgreSQL bootstraps with an environment-specific migration
  owner. The one-shot job creates or rotates a distinct app role, runs Alembic,
  and grants only connect/schema/table/sequence access. Core receives only the
  app URL and cannot read the migration-owner URL.
- Startup gate: core uses Compose condition
  `service_completed_successfully`; any migration failure prevents core from
  starting.
- Test commands:
  - `python ops/scripts/check_environment_isolation.py`
  - `python -m unittest -v tests.test_environment_checker
    tests.test_migration_job`
  - `python -m alembic -c alembic.ini heads`
  - the E-001 Compose `config --quiet` command for all three environments
- Result: the isolation checker passed; all seven committed fault fixtures
  failed with their expected diagnostic. After integrating the authoritative
  Memory service, Alembic reports the single `20260714_0004` head across the
  pgvector, memory-table, exact-index and outbox revisions; all three Compose
  projects render successfully.
- Safety: repository-only so far; no existing database, server container,
  volume, network, route, or credential was changed.

## E-005 - liveness, readiness, and file-secret runtime contract

- Status: completed locally; managed-environment runtime proof remains part of
  the isolated staging acceptance.
- Endpoints: `/health/live` proves only that the process can answer;
  `/health/ready` returns 503 in dev/staging/production unless the release and
  runtime identities, mounted data build, Prompt/Response/expression hashes,
  provider selection, secret files, and live Alembic revision all match.
- Secret behavior: core rejects inline secret variables and loads database,
  LLM, JWT, and AstrBot values only from their `_FILE` paths. Error payloads do
  not echo secret values.
- Packaging: the core image now includes Prompt/Response contract files and
  `asyncpg`; the environment-specific Release Manifest is mounted read-only.
- Test commands:
  - `python -m unittest -v tests.test_deployment_readiness
    tests.test_llm_provider tests.test_meguri_core`
  - `python ops/scripts/check_environment_isolation.py`
  - `python -m unittest -v tests.test_environment_checker`
- Result: 25 readiness/provider/core tests and 2 isolation-checker tests
  passed; the normal isolation configuration passed.
- Safety: repository-only; no remote health route, container, database, or
  secret was touched.

## E-006 - temporary/raw exposure ledger and production gate

- Status: completed.
- Inventory: all 29 ports reachable from the workstation baseline are recorded
  by service group with declared binding, observed reachability,
  authentication state, data classification, responsible-owner confirmation,
  evidence, and an explicit closure condition. The two repository-only Meguri
  loopback ports are also registered.
- Validator: every published Meguri Compose port must appear in the ledger;
  unapproved all-interface Meguri bindings fail closed. Existing protected
  services are inventory-only and were not changed.
- Production behavior: the structural command succeeds, while
  `--production-gate` intentionally returns 1 for the ten unresolved existing
  exposure groups. Production remains blocked.
- Test commands:
  - `python ops/scripts/check_exposure_ledger.py`
  - `python -m unittest -v tests.test_exposure_ledger`
  - `python ops/scripts/check_exposure_ledger.py --production-gate` (expected
    nonzero)
- Result: structural validation passed; 4 ledger tests passed; the production
  gate returned 1 with the expected unresolved reviews.
- Safety: ledger-only; no firewall, cloud rule, reverse proxy, listener,
  container, or existing service was changed.

## E-007 - immutable staging deploy, health gate, and last-good rollback

- Status: repository implementation completed; real staging execution remains
  pending server deployment access, release artifacts, and E-008 restore proof.
- Preflight: staging-only, absolute release paths, `meguri-staging` project,
  matching env/Manifest release and DB identities, all tests passed, no
  readiness placeholders, mutation disabled, and core/migration/PostgreSQL
  images pinned to Manifest-matching `@sha256` digests.
- Sequence: validate Compose, pull immutable images, wait for the isolated
  PostgreSQL service, run the one-shot migration, start core without touching
  unrelated services, then require `/health/ready` to return the candidate
  release ID.
- State: atomic `current.json`, `last-good.json`, and `rollback-target.json`.
  Migration failure occurs before old core replacement; same-revision
  readiness failure restores last-good automatically. Cross-revision changes
  fail before mutation until E-008 supplies a verified restore path.
- Files: `ops/deployment/release.py`, `deploy_staging.py`,
  `rollback_staging.py`, staging runbook, and deployment tests.
- Test commands:
  - `python -m unittest -v tests.test_staging_deployment
    tests.test_release_manifest`
  - `python ops/scripts/deploy_staging.py --help`
  - `python ops/scripts/rollback_staging.py --help`
- Result: 5 deploy/rollback fault-path tests and 4 Release Manifest regression
  tests passed; both direct CLI entrypoints loaded and displayed usage.
- Safety: repository simulation only; no server Compose command, pull,
  migration, health request, or rollback was run.

## E-008 - staging PostgreSQL backup and restore rehearsal

- Status: repository implementation completed; real staging rehearsal is
  pending the isolated deployment and server-side release/secret access.
- Backup: custom format, compression, no owner/ACL, environment-specific output
  directory, mode `0600`, and atomic metadata containing size, SHA-256, release,
  data build, PostgreSQL version, and Alembic revision. No password is copied
  into the host process or report.
- Rehearsal: only a safe `meguri_staging_restore_*` database may be created;
  checksum is verified before mutation; Alembic and pgvector must match; the
  temporary database is force-dropped after success or failure.
- Active recovery: intentionally unavailable without a maintenance window and
  explicit recovery approval; production restore remains blocked.
- Test commands:
  - `python -m unittest -v tests.test_postgres_backup`
  - `python ops/scripts/backup_staging.py --help`
  - `python ops/scripts/rehearse_staging_restore.py --help`
- Result: 5 backup/restore control-flow and fault tests passed; both CLI
  entrypoints loaded. `reports/staging-restore-rehearsal.md` clearly records
  that no runtime RPO/RTO evidence exists yet.
- Safety: local fake transport only; no server database, archive, directory,
  container, volume, or secret was accessed.

## E-009 - CI/CD boundaries and production approval

- Status: completed; production remains deliberately blocked.
- CI: PR/push validation runs isolation/exposure checks, asserts that the
  production exposure gate does not unexpectedly pass, renders all Compose
  projects, executes environment/deployment tests, and builds both images
  without publishing.
- Staging CD: manual dispatch only, explicit `DEPLOY_STAGING` confirmation,
  serialized concurrency, a dedicated `[self-hosted, meguri-staging]` runner,
  GitHub `staging` environment, dry-run preflight, then last-good deployment.
- Production: approval schema/checker requires a Manifest digest, validity
  window, change ticket, three distinct owners, and six positive evidence
  checks. Existing base/exposure gates are independent blockers. The production
  workflow validates only and contains no deploy or mutation step.
- Test commands:
  - `python -m unittest -v tests.test_production_approval
    tests.test_release_manifest`
  - `python ops/scripts/check_production_approval.py --help`
- Result: 4 production/workflow tests and 4 Manifest regressions passed; the
  current blocked artifact returned 1 with base-gate and exposure failures.
- Safety: repository-only; no GitHub workflow was triggered and no image was
  pushed, deployed, or promoted.

## E-010 - automated acceptance evidence and Agent handoff

- Status: repository and protected-server invariant acceptance completed;
  runtime staging acceptance remains blocked with explicit machine-readable
  evidence.
- Handoff contracts: the native pgvector Memory implementation through M-012
  is integrated at schema head `20260714_0004`, with file-only app credentials,
  pinned BGE-M3 metadata, recovery validation and exact-search benchmarks. The
  LLM training/evaluation pipeline through L-013 is integrated with locked
  safety/comparison gates, adapter digest selection, an evaluated model
  registry, authenticated staging gateway, last-good routing, enforced runtime
  concurrency and gateway release-header validation.
- Manifest hardening: `model_registry_id` and `llm_adapter_sha256` are required;
  staging/production readiness rejects an unregistered model or an adapter
  revision without a digest.
- Runtime evidence contract: requires all data/account/volume isolation,
  empty-database migration, migration failure, backup/restore, two rollback
  fault injections, and protected-service invariants to be true with evidence
  digests. The committed blocked artifact intentionally returns 1.
- Live read-only recheck (latest: 2026-07-14 22:27:23 +08:00): Docker Engine 29.2.1, all
  22 protected containers running, all required networks/named volumes present,
  and no Meguri environment object. No mutation was issued.
- Final regression (before the final integrated rerun recorded in
  `reports/environment-final.md`):
  - Python environment suite: 95 passed;
  - TypeScript: 20 passed;
  - Compose: dev/staging/production config passed;
  - isolation, Agent contracts, and live protected-server invariants passed;
  - staging acceptance and production approval/exposure gates returned nonzero
    as required by the current evidence state.
- Blockers: no immutable core/migration image digests, server release directory
  and independent secret provisioning/deployment access, trained registered
  LLM candidate/last-good artifacts, real isolated PostgreSQL contract run, or
  real staging evidence. Production remains blocked.

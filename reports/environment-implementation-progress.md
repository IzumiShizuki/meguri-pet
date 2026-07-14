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
  failed with their expected diagnostic; 8 checker/migration tests passed;
  Alembic reported the single `20260714_0001` head; all three Compose projects
  rendered successfully.
- Safety: repository-only so far; no existing database, server container,
  volume, network, route, or credential was changed.

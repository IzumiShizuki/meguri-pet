# Meguri environment isolation final report

> Date: 2026-07-14 (Asia/Shanghai)
>
> Branch: `feat/environment-isolation`
>
> Production mutation: **blocked**
>
> Real staging acceptance: **blocked pending artifacts/access**

## Outcome

The repository now contains an isolated, fail-closed environment and deployment
framework for dev, staging, and production. E-001 through E-009 are implemented
and locally verified. E-010 repository acceptance plus a second read-only
protected-server check passed, but runtime staging acceptance is intentionally
not claimed because the required release artifacts, server-side secret access,
native Memory provider, and registered LLM candidate/last-good do not exist yet.

This implementation follows the authority order from
[15｜开发、Staging 与生产隔离实施计划](https://app.notion.com/p/39da363659638157a494e897cedef86f),
cross-checked with the current repository/server state, pages 13/11/00,
[14.1](https://app.notion.com/p/39da3636596381eb8f13f5e3f3850d07),
[16｜PostgreSQL + pgvector](https://app.notion.com/p/39da36365963818b904ad4960dd3addc),
and [17｜文本 LLM 微调](https://app.notion.com/p/39da3636596381c1a701d377af7101ec).

## Delivered controls

| Task | Result | Main evidence |
| --- | --- | --- |
| E-001 | Complete | Three explicit Compose projects; unique edge/internal networks, database volumes, logs, backups, and secret paths |
| E-002 | Complete | Static isolation checker plus seven committed fault fixtures |
| E-003 | Complete | Release Manifest schema/generator/readiness checker with image, data, Prompt, Schema, expression, DB, embedding, model registry and adapter identities |
| E-004 | Local implementation complete | Isolated pgvector PostgreSQL, one-shot Alembic migration, separate migration owner/app role, core startup gate |
| E-005 | Local implementation complete | `/health/live`, fail-closed `/health/ready`, file-only secrets, runtime identity and live DB revision check |
| E-006 | Complete | All 29 observed reachable ports registered; ten unresolved existing exposure groups block production |
| E-007 | Local implementation complete | Digest-only staging preflight, ordered migration/start, atomic last-good state, automatic same-revision rollback |
| E-008 | Local implementation complete | Checksummed custom backup and isolated restore-rehearsal workflow; runtime RPO/RTO not yet measured |
| E-009 | Complete | CI validation, manual serialized staging CD, validation-only production approval workflow |
| E-010 | Repository/live invariant complete; staging blocked | Agent contracts, protected-server invariants, machine-readable all-or-nothing staging acceptance evidence |

## Isolation and security properties

- PostgreSQL joins only each environment's `internal: true` network and exposes
  no host port. Dev/staging core binds only to loopback; production publishes no
  port and requires a separately approved entry change.
- Dev, staging, and production have distinct project, network, database, owner,
  app role, named volume, data/log/backup directory, and secret-file identity.
- Core never receives the migration-owner URL or app-role provisioning password.
  Inline database, LLM, JWT, or AstrBot secrets are rejected.
- Migration failure prevents core startup. Release readiness fails on Manifest,
  mounted data, artifact hash, provider, model, adapter, secret, or live DB
  revision drift.
- Staging deployment accepts only Manifest-matching `@sha256` images. Candidate
  readiness failure restores same-revision last-good. Cross-revision deployment
  remains blocked until a verified active recovery workflow exists.
- Production requires independent base, exposure, Manifest, restore, rollback,
  backup, route, multi-owner and time-window approval gates. Current gates do
  not pass and no production deploy workflow exists.

## Verification results

Final local results:

- `python -m unittest discover -v`: **95 passed**;
- `pnpm test:ts`: **20 passed**;
- dev/staging/production `docker compose ... config --quiet`: **passed**;
- environment isolation checker: **passed**;
- Memory/LLM Agent contract checker: **passed**;
- blocked staging acceptance checker: **returned 1 as expected**;
- production exposure/approval gates: **returned nonzero as expected**.

The pnpm command printed a non-fatal registry metadata/update-check failure, but
the workspace was already up to date and all 20 Node tests completed with zero
failures.

## Protected server recheck

At 2026-07-14 22:01 +08:00, a read-only Docker TLS query confirmed:

- Docker Engine remains `29.2.1`;
- all 22 baseline containers remain running;
- every protected network and named volume remains present;
- no container, network, or volume begins with `meguri-dev`,
  `meguri-staging`, or `meguri-production`.

No server file, secret, container, network, volume, image, database, route,
firewall rule, listener, AstrBot object, site object, or existing PostgreSQL
object was created, changed, restarted, or removed by this task.

## Runtime staging blockers

The following evidence is still absent and cannot be safely inferred:

1. immutable pushed digests for core and migration images plus a pinned pgvector
   image digest;
2. `/opt/meguri/staging` release/data/log/backup/secret directories and a
   deployment identity able to provision them without exposing secret values;
3. a wired `native_pgvector` Memory provider and its real schema migrations;
4. registered LLM candidate and last-good identifiers, adapter revision/digest,
   authenticated endpoint and enforced concurrency;
5. real empty-database migration, user/account/data/volume isolation, backup,
   restore, migration-failure and image/readiness rollback evidence;
6. measured staging RPO/RTO and checksummed before/after server inventories.

Until those items exist, `ops/acceptance/blocked.staging-acceptance.json` and
`reports/staging-restore-rehearsal.md` remain authoritative: staging is not
accepted and production cannot be promoted.

## Next safe sequence

1. Memory and LLM owners satisfy the contracts under `ops/contracts/`.
2. CI builds/pushes immutable images and generates a non-placeholder staging
   Release Manifest with registered model/adapter evidence.
3. Provision only `/opt/meguri/staging` directories and independent mode-0600
   secret files through an approved server deployment identity.
4. Capture protected-server inventory, deploy staging, run empty-DB migration
   and isolation tests, create/restore a backup, inject migration/image/readiness
   faults, and capture the after inventory.
5. Replace the blocked staging artifact only when every required check is true;
   update the restore report with real RPO/RTO.
6. Keep production blocked until its separate approval and exposure gates are
   resolved. Do not change OpenResty/1Panel or traffic as part of staging proof.

## Fine-grained commits

- `4e91939` baseline report
- `add61a8` isolated Compose baseline
- `20be91d` isolation checker
- `b78a5bd` Release Manifest
- `5097f46` migration/app-role gate
- `cf7118d` runtime readiness and file secrets
- `0198fb4` exposure inventory
- `ce809f0` staging deploy/last-good rollback
- `b05566c` backup/restore rehearsal
- `ebc7003` CI/CD and production approval

The E-010 acceptance/contracts/final-report commit follows these entries.

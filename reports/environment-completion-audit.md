# Meguri environment goal completion audit

Audit time: 2026-07-14 22:37:12 +08:00

Branch: `feat/environment-isolation`

Audited commit: `3021d67`

Verdict: **goal not achieved; runtime Staging acceptance is blocked**

## Requirement-by-requirement result

| Scope | Current evidence | Verdict |
| --- | --- | --- |
| Ordered planning/baseline review | `reports/environment-baseline.md` records the required Notion authority order plus the repository and read-only server baseline | Proven |
| Isolation branch | Current branch is `feat/environment-isolation`; the only unrelated working-tree change is the user's preserved, unstaged `training/generate_tts_samples.py` | Proven |
| E-001 Compose isolation | Dev, Staging and Production Compose render successfully with distinct project, tenant, network, database, role, volume, path and secret identities | Repository implementation proven; runtime Staging objects absent |
| E-002 isolation checker | Normal configuration passes and committed negative fixtures are covered by the Python suite | Proven |
| E-003 Release Manifest | Schema, generator, validator, runtime identity checks and model-registry/adapter bindings pass | Implementation proven; no non-placeholder Staging release exists |
| E-004 PostgreSQL/pgvector/migration | Linear Alembic head is `20260714_0004`; native provider, separate owner/app role and migration startup gate are implemented | Implementation proven; empty isolated PostgreSQL execution is missing |
| E-005 health/readiness/secrets | Liveness/readiness, file-only secrets, live revision checks and gateway release-header validation are covered by tests | Local proof only; no Staging health endpoint exists |
| E-006 exposure inventory | Structural exposure ledger passes; the Production gate identifies ten unresolved existing exposure groups | Inventory proven; Production remains blocked |
| E-007 Staging deploy/rollback/last-good | Digest-only preflight, ordered deploy, readiness gate, same-revision rollback and LLM candidate/last-good routing are tested | Implementation proven; no real image/readiness fault drill exists |
| E-008 backup/restore | Checksummed backup, isolated restore target, cleanup and recovery validator are tested with injected transports | Implementation proven; no real archive, restore, RPO or RTO evidence exists |
| E-009 CI/CD/Production approval | CI, manual serialized Staging workflow and validation-only Production approval workflow are tested | Proven as repository controls; no images were published and Production is not approved |
| E-010 acceptance/contracts | Memory and LLM contracts pass; all-or-nothing Staging and Production gates fail closed | Acceptance explicitly not achieved |

## Decisive verification

- Python: `179 passed, 6 skipped`; all six skips require an isolated PostgreSQL URL.
- TypeScript: `20 passed`.
- Dev/Staging/Production Compose render: passed.
- Isolation checker, exposure structure, agent contracts and Manifest schema: passed.
- Alembic head: `20260714_0004`.
- Staging acceptance checker: exit `1` as required by the blocked artifact.
- Production exposure gate: exit `1`; Production approval gate: exit `1`.
- LLM model registry: `models: []`.
- LLM routing: `candidate_model_id: null`, `last_good_model_id: null`.
- Immutable SFT dataset identity includes source build and split hashes; its
  focused regression suite passes.
- No model artifacts, checkpoints, release state or immutable candidate Manifest are present.

## Live protected-server evidence

The read-only Docker TLS recheck passed at 22:37:12 +08:00:

- Docker Engine `29.2.1`;
- 22 protected containers running;
- zero Meguri containers;
- zero Meguri networks;
- zero Meguri named volumes.

No production write, migration, restart, route change, traffic switch or entry
change was executed.

## Blocking external prerequisites

The following cannot be produced safely from the current repository or
credentials:

1. a trained, human-reviewed, safety-gated and registered LLM candidate plus an
   approved last-good model, adapter digest and authenticated endpoint;
2. immutable pushed core, migration and pgvector image digests and a matching
   non-placeholder Staging Release Manifest;
3. independent Staging secret files and an approved provisioning mechanism;
4. an isolated PostgreSQL test/Staging connection for real migration, provider,
   backup, restore, data/account/volume isolation and RPO/RTO evidence.

Reusing existing protected-service secrets, deploying placeholders, accepting
mock/fake providers, or mutating the server before these gates exist would
violate the environment-isolation contract. This is the same external-state
impasse observed on the user-triggered goal turn and both automatic
continuations.

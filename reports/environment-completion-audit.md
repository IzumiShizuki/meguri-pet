# Meguri environment goal completion audit

Audit time: 2026-07-15 01:58 +08:00

Branch: `feat/environment-isolation`

Runtime release commit: `575aba783bc820757929c6938bb3a699427c3f82`

Evidence tooling commit: `604a07e55b614211b7a5ea604e952a122fd4c123`

Verdict: **environment runtime gates passed; full Staging application remains NO-GO**

## Requirement audit

| Scope | Authoritative evidence | Verdict |
| --- | --- | --- |
| Ordered Notion review and baseline | Pages 15, 13, 11, 00, main authority section, 14.1, 16 environment dependencies, and 17 Staging integration were read in order; `reports/environment-baseline.md` retains the original server baseline | Proven |
| E-001 Compose isolation | Three explicit projects render; live Staging owns only `meguri-staging-edge`, `meguri-staging-internal`, and `meguri-staging-postgres-data` | Proven |
| E-002 static isolation checker | Normal configuration passes and all committed negative fixtures fail with specific diagnostics | Proven |
| E-003 Release Manifest | `r001` and `r002` pass readiness validation and bind Git/data/artifact/DB/embedding/LLM/image identities | Proven |
| E-004 PostgreSQL/migration | Fresh pgvector PostgreSQL migrated to `20260714_0004`; app and migration roles are distinct; an unsafe migration-role fixture failed before core replacement | Proven |
| E-005 health/readiness/secrets | Live `r002` readiness reports every check passed; secret files are mode `0400`; PostgreSQL has no host port | Proven |
| E-006 exposure ledger | Staging core binds only `127.0.0.1:18080`; no public route, proxy, firewall, DNS, or certificate was changed | Proven for Staging; Production exposure review remains blocked |
| E-007 deploy/rollback | Two distinct core image digests deployed; explicit rollback changed `r002` to `r001`, then `r002` was redeployed; nonexistent-image and readiness-mismatch candidates automatically restored `r002` | Proven |
| E-008 backup/restore | Non-empty custom archive restored to a fresh database; revision, pgvector, nine counts and fixed fingerprint matched; target cleanup and elapsed times recorded | Proven for Staging |
| E-009 CI/Production approval | Repository workflows and manual Production gates pass their tests; Production approval artifact remains blocked | Proven as controls; no Production mutation authorized |
| E-010 integrated acceptance | Data/account/volume isolation, empty migration, migration failure, backup/restore, image/readiness rollback and protected-service invariants have real evidence | Environment checks proven; real authenticated DeepSeek Turn/SSE is missing |
| Required handoffs | Three Markdown contracts plus machine-readable Memory/LLM contracts exist | Proven |
| Notion synchronization | Final runtime report still needs to be written after repository verification | Pending at this audit point |

## Runtime evidence

- Final local regression: 184 passed, 6 database-gated skips, and one upstream
  Starlette/httpx deprecation warning; TypeScript 20 passed. The gated live
  subset is covered separately below.
- Docker Engine: `29.2.1` on `111.228.35.186`.
- Current release: `meguri-staging-20260715-r002`.
- Core: healthy, digest
  `sha256:02d3986e7a8453a9a25d7b64c3517aaaba35602c84cde618905125561b3001bb`.
- PostgreSQL: healthy, digest
  `sha256:1d533553fefe4f12e5d80c7b80622ba0c382abb5758856f52983d8789179f0fb`.
- Migration: digest
  `sha256:3d826edb697234cd53e48ae976834889ae5b90fee0e5eed725079fca779e5589`.
- Readiness: release, artifact digests, providers, secret files, mounted data,
  and live database revision all passed.
- PostgreSQL sees only `meguri_staging`; expected roles are
  `meguri_staging_migration` and `meguri_staging_app`. Probe connections using
  dev and production account identities both failed.
- No temporary restore database remains.
- The previously gated native provider contract, candidate/version lifecycle,
  tenant isolation, transaction rollback, cross-client identity/session,
  embedding outbox, and recovery validator ran inside a disposable container
  on `meguri-staging-internal`: 7 passed, 0 skipped; the container was removed.

Evidence digests:

- Release Manifest: `da63ef6e536cdc0c6edaab7ccc15b98aab6a3088ad9ec542e77b00e236eb5cd0`.
- Restore metadata: `8d23141c0cc7699b73ad7b6aa6abb40a6ae167c489f4d593d4a12bc8a120bc42`.
- Before inventory: `f0391c00f39e138997cc19956a343a00f55f6ab719a18306af689f2cc2acfe7d`.
- After inventory: `0dbdbbbbd610d19cb7f85a0fc7a863e724eb2a7e2985381b334aff443d55af57`.

All 22 protected containers were running after the exercise, and every one had
a start time earlier than the pre-Staging inventory cutoff. Existing AstrBot,
site, databases, middleware, networks and named volumes were not restarted or
modified.

## Remaining blocker

No usable DeepSeek API credential is available. The Staging secret file holds
an unavailable placeholder so file-secret and readiness behavior can be
tested without reusing another service's credentials. A synthetic Turn reached
the configured `https://api.deepseek.com/v1` provider and failed closed; the
server log recorded sanitized `LLM provider request failed (HTTP 401)` and the
client received HTTP 500 without a key value.

Therefore the environment substrate and recovery acceptance are proven, but
the full Staging application must remain NO-GO until a real file-only DeepSeek
key is provisioned and Turn, SSE, timeout/cancel, RAG and MemoryProvider smoke
tests pass. Fine-tuned-model registration remains the later LLM Agent path.

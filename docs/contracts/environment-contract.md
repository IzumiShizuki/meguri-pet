# Meguri environment contract

## Authority and status

This contract implements Notion plan 15 for the repository at
`D:\program\meguri-pet`. Dev, Staging and Production are separate failure
domains. The live Staging substrate is verified; full application acceptance
is still blocked on an authenticated DeepSeek smoke. Production remains
non-mutating and requires a separate approval.

## Fixed identities

| Environment | Compose project | Edge network | Internal network | Database volume |
| --- | --- | --- | --- | --- |
| dev | `meguri-dev` | `meguri-dev-edge` | `meguri-dev-internal` | `meguri-dev-postgres-data` |
| staging | `meguri-staging` | `meguri-staging-edge` | `meguri-staging-internal` | `meguri-staging-postgres-data` |
| production | `meguri-production` | `meguri-production-edge` | `meguri-production-internal` | `meguri-production-postgres-data` |

PostgreSQL joins only the environment's internal network. Core joins edge and
internal. Staging core is exposed only as `127.0.0.1:18080`; PostgreSQL has no
host port. No client may scan or fall back to another environment.

## Server paths

Each environment owns `/opt/meguri/<environment>/{config,secrets,logs,backups}`.
Staging additionally uses:

```text
/opt/meguri/staging/config/data
/opt/meguri/staging/releases/<release-id>
/opt/meguri/staging/state
/opt/meguri/staging/evidence
```

Only immutable read-only release/data artifacts may be shared by releases in
one environment. Databases, writable volumes, users, memory, sessions, tokens,
provider keys, logs and backups are never shared across environments.

## Secret contract

Managed environments accept only file-backed secrets:

```text
MEGURI_POSTGRES_PASSWORD_FILE
MEGURI_POSTGRES_APP_PASSWORD_FILE
MEGURI_DATABASE_URL_FILE
MEGURI_MIGRATION_DATABASE_URL_FILE
MEGURI_LLM_API_KEY_FILE
MEGURI_JWT_SECRET_FILE
MEGURI_ASTRBOT_SHARED_TOKEN_FILE
```

Core receives the app database URL but never the migration-owner URL or app
role provisioning password. Real values are not stored in Git, manifests,
health output or reports. Staging files are mode `0400` and owned by the
container UID that consumes them.

## Release contract

Every managed release binds an immutable Manifest to Git commit, image
digests, data build, Prompt/Schema/expression hashes, Alembic revision,
embedding revision, LLM base/adapter identity, model registry ID and passed
test status. Readiness fails on any mismatch.

Current Staging identity:

```text
release_id: meguri-staging-20260715-r002
data_build_id: meguri_v2_02c3db0c507d7c2d
database_revision: 20260714_0004
embedding_model_revision: 5617a9f61b028005a4858fdac845db406aefb181
llm_base_model: deepseek-chat
llm_adapter_revision: null
model_registry_id: external-deepseek-chat-staging
```

## Deploy and rollback

Use `ops/scripts/deploy_staging.py` and `rollback_staging.py`. The deployer
validates Compose and Manifest, starts PostgreSQL, runs the one-shot migration,
starts core, and accepts only the matching ready release. State consists of
`current.json`, `last-good.json`, and `rollback-target.json`.

TLS remote control planes supply `--compose`, set
`MEGURI_HEALTH_PROBE_MODE=compose`, and may set pull policy `never` only after
inspecting all digest references on the target daemon. Host-local execution
uses HTTP loopback readiness. Production always pulls prebuilt immutable
artifacts and never builds in place.

## Production boundary

The following remain blocked without explicit separate approval: Production
migration/restore/mutation, traffic switching, proxy/1Panel changes, public
entry changes, firewall, DNS, certificates, existing PostgreSQL, AstrBot, and
deletion of existing images, containers or volumes.

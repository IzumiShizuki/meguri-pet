# Staging deploy and rollback

This workflow owns only the `meguri-staging` Compose project and paths below
`/opt/meguri/staging`. It does not edit OpenResty, 1Panel, AstrBot, firewall
rules, cloud security groups, or an existing Compose project.

## Release directory contract

Create an immutable directory `/opt/meguri/staging/releases/<release-id>/`
containing:

- `runtime.env`: environment-specific paths and three image references pinned
  with `@sha256:`; no secret values;
- `release-manifest.json`: the CI-generated and verified Release Manifest;
- the data build referenced by `MEGURI_DATA_DIR`, staged before deployment.

All real credentials remain mode `0600` files under
`/opt/meguri/staging/secrets`. Never print or copy their values into a release
directory.

## Preflight and deploy

Run the repository isolation, manifest, and exposure checks first. The current
exposure production gate is expected to fail and does not authorize production.

```bash
python ops/scripts/check_environment_isolation.py
python ops/scripts/check_exposure_ledger.py
python ops/scripts/check_release_manifest.py \
  /opt/meguri/staging/releases/<release-id>/release-manifest.json --readiness
python ops/scripts/deploy_staging.py \
  --env-file /opt/meguri/staging/releases/<release-id>/runtime.env \
  --manifest /opt/meguri/staging/releases/<release-id>/release-manifest.json \
  --dry-run
python ops/scripts/deploy_staging.py \
  --env-file /opt/meguri/staging/releases/<release-id>/runtime.env \
  --manifest /opt/meguri/staging/releases/<release-id>/release-manifest.json
```

The deployer validates Compose, pulls immutable images, waits for the isolated
PostgreSQL service, runs the one-shot migration job, starts only the candidate
core, and accepts it only when `/health/ready` reports the candidate release ID.
It writes `current.json`, `last-good.json`, and `rollback-target.json` atomically
under `/opt/meguri/staging/state`.

## Remote Docker control plane

When the repository reaches the server through a TLS Docker endpoint instead
of a shell on the host, keep the server-side paths in
`MEGURI_RELEASE_MANIFEST_FILE` and the secret variables unchanged. Add these
non-secret controls to the local release env:

```text
MEGURI_CONTROL_PLANE_MANIFEST_FILE=<absolute-local-manifest-path>
MEGURI_IMAGE_PULL_POLICY=never
MEGURI_HEALTH_PROBE_MODE=compose
```

`never` is permitted only when all three Manifest-matching digest references
have already been inspected on that daemon. Supply the standalone Compose
binary with `--compose`; readiness is then checked from inside `core`, so the
server can keep port 8000 bound to host loopback. Keep the control-plane state
directory environment-specific and mirror its three JSON files to
`/opt/meguri/staging/state` after a successful deployment or rollback.

## Failure and rollback behavior

- Migration failure occurs before core replacement, so the running old core is
  left in place.
- A same-database-revision candidate that fails readiness is automatically
  replaced with last-good.
- A database revision change is rejected by this workflow until the
  backup/restore workflow supplies a verified cross-revision rollback path.
- Explicit same-revision rollback uses:

```bash
python ops/scripts/rollback_staging.py
```

Do not use `docker compose down -v`, remove named volumes, run Alembic
`downgrade`, or change public routing as a rollback shortcut.

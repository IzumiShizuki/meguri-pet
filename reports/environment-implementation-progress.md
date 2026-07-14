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

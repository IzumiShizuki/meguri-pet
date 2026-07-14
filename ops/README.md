# Meguri environment operations

This tree implements three explicit and isolated Compose projects:

- `meguri-dev`
- `meguri-staging`
- `meguri-production`

Every command must provide both the base file and one environment overlay, and
must load the matching example or server-side env file. Never rely on the
working-directory-derived Compose project name.

```bash
docker compose \
  --project-name meguri-dev \
  --env-file ops/env/dev.env.example \
  -f ops/compose/compose.base.yaml \
  -f ops/compose/compose.dev.yaml \
  config
```

The committed env files contain configuration and secret *file paths* only.
Actual secret values live outside the repository as described in
`ops/secrets/README.md`.

The production overlay is a deploy contract, not permission to deploy.
Production mutation, migration, public traffic changes, and secret provisioning
remain blocked until the production approval gate succeeds.


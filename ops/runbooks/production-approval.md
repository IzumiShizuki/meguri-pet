# Production approval boundary

Production mutation is not authorized by the presence of a Compose file,
Release Manifest, Git tag, CI success, or a single reviewer. The repository's
current `configs/production_gate.json` is blocked and the exposure ledger has
ten unresolved review groups, so the production approval checker must return
nonzero.

A future approval artifact must bind one immutable production Manifest digest
and release ID to a change ticket, a short approval window, and at least three
distinct approver roles/identities. All staging acceptance, restore, rollback,
exposure, production backup, and route-change checks must be true. The base gate
and exposure gate must independently pass.

Validation command:

```bash
python ops/scripts/check_production_approval.py \
  --approval /secure/path/production-approval.json \
  --manifest /immutable/path/release-manifest.json
```

`.github/workflows/production-approval.yml` performs only this validation under
the `production-approval` GitHub environment. It intentionally contains no
Compose deployment, migration, traffic switch, reverse-proxy edit, firewall
change, or secret provisioning step. Adding a production deploy workflow is a
separate reviewed change after every gate is resolved.

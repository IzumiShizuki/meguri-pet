## Context

See proposal.md. Read-only preflight found AstrBot 4.28.1 / gateway 0.7.0 running on the personal server with host networking. The current plugin main exactly matches the implementation's pre-feature baseline. The server schema has existing hidden-field presentation settings absent from the generic ZIP. Its persona table is empty and the local agent runner default persona ID is `default`.

## Goals / Non-Goals

**Goals:** Activate the verified feature while retaining current default Meguri chat, owner Todo creation and all unrelated production state.

**Non-Goals:** Create or rename personas, migrate histories, restart Core, change providers/destinations, send real QQ messages, deploy the whole dirty repository or push Git.

## Decisions

1. Patch only `main.py`, `metadata.yaml`, `_conf_schema.json` and the new `persona_routing.py`. The first two match the prepared baseline; build schema from the actual remote schema plus the two new reviewed fields so visibility/customizations survive. Do not overwrite the whole source ZIP.
2. Set `persona_routing_enabled=true`, `meguri_persona_ids=["default"]`. The current default remains Meguri; future or explicitly selected other personas remain native. No user persona record or prompt is created.
3. Record preflight file/config hashes and container identities. Back up exact replaced files and config in a fresh permission-restricted release directory, copy them into an isolated restore rehearsal and compare hashes. Save an explicit rollback manifest, including the original absence of `persona_routing.py`.
4. Validate candidate syntax/schema and isolated routing before activation. Atomically replace the four files and the two config fields, then restart only `astrbot`. Retain its image, identity, networking and mounts. Compare every pre-existing config value, including targets and owner permissions, after reload.
5. Use installed AstrBot APIs with synthetic events and a gateway spy in a separate process; do not invoke platform sends. Verify effective default, a nonlisted persona, explicit Core access, unavailable resolver fallback, permissions and plugin priority. Preserve real Core service health/start identity throughout.

## Risks / Trade-offs

- [Bot temporarily offline during restart] -> A single existing-container restart with readiness checks; restore prior files/config on failure.
- [Dashboard auto-fills schema defaults] -> Verify pre-existing values remain equal and permit only declared new persona defaults.
- [Default ID is not a custom named persona] -> Report the exact current mapping and provide the setting location for future changes.
- [Synthetic checks cannot establish QQ delivery] -> Clearly state zero real platform sends and the remaining human receipt boundary.

## Migration Plan

Deploy only after local artifacts and reviewed candidate are ready. Back up and rehearse restore, install and restart AstrBot, then verify startup/hashes/routing/invariants. On failure stop AstrBot, preserve failed candidate files, restore exact originals/config, remove only the newly introduced module and start the same container. Leave backups and evidence intact. No database or network rollback is required.

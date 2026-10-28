# Verification: prioritize-personal-astrbot-plugins

Verified 2026-10-05, Asia/Shanghai. Gateway 0.6.0 installed and activated after explicit user authorization.

| Dimension | Result |
| --- | --- |
| Completeness | 13/13 implementation, audit, packaging, installation and verification tasks complete; 4/4 requirements covered |
| Correctness | All 11 specification scenarios checked through routing regressions or catalogue review |
| Coherence | Uses AstrBot's existing per-event dispatch metadata, keeps ordinary chat and explicit Meguri ownership, and performs no Core/platform/permission expansion |

## Evidence

- `docs/astrbot-personal-commands.md`: 77 installed plugin directories, 353 registered command/group entries and 30 regex entries. Contains common commands, full aliases/subcommands, current QQ-official/WeChat platform limits and the difference between source support and live operation.
- `adapters/astrbot/astrbot_plugin_meguri_gateway/plugin_routing.py`: matched command/group/regex ownership plus bounded checks for Bilibili cards, media-parser links/allowlist, image-search triggers/unexpired state and enabled, loaded meme keywords. Reads existing plugin instances; does not invoke handlers, repeat filters or request the network.
- `bridge.py` and `main.py`: legacy command-group discovery, slash/case/whitespace-safe aliases, reconstruction after host wake normalization, explicit Core precedence, default-LLM suppression without stopping plugin dispatch, and no global-registry override of session-filtered metadata.
- 90 AstrBot tests passed: `python -X utf8 -m pytest <all tests/test_astrbot*.py paths> -q`. New regressions cover priority, scoped handlers, parsed-parameter preservation, explicit/bare Meguri distinction, textless cards, alias fallback, ordinary chat, media configuration and whitelist, search state timeout and meme configuration.
- Read-only checks inside the installed AstrBot container passed for all seven cases: actual installed Bilibili regex matching BV/long URL/short URL; actual CommandFilter alias and independent CommandGroupFilter ownership; Bilibili card ownership; passive listener leaves ordinary chat with Core. These use synthetic events and the proposed module held in memory, with no files deployed or platform messages sent.
- Package `output/astrbot_plugin_meguri_gateway-personal-0.6.0.zip` built with the existing packager; archive root, included routing module, CRC, Python syntax, schema and absence of local config/cache files checked. OpenSpec strict validation and `git diff --check` passed.
- `output/astrbot-personal-priority-preview.json` records pre-installation/proposed hashes and the exact five-file deployment scope: main, bridge, metadata, schema and the new routing module. Before installation, main/metadata/schema matched repository HEAD; bridge differed only in comments/docstrings. No live configuration values needed updating. Restore/reload the backed-up files for rollback.

## Authorized installation

- The user explicitly requested installation after reviewing the package and deployment scope. The five current source hashes matched the prepared preview; archive CRC, syntax and proposed hashes were rechecked before replacement.
- Backup: `/AstrBot/data/plugin_backups/personal-priority-20261005-141607`. Preserves existing reviewed files in `old/`, the original gateway configuration with restrictive permissions, and an original/proposed hash manifest. `plugin_routing.py` was originally absent; remove that exact file when restoring the old release.
- After activation, every backed-up file matched its original hash (including the recorded absence of the new module), and the live gateway configuration remained byte-for-byte identical to its backup.
- Installed only `main.py`, `bridge.py`, `metadata.yaml`, `_conf_schema.json` and `plugin_routing.py` at `/AstrBot/data/plugins/astrbot_plugin_meguri_gateway`. Deployed hashes match the reviewed package. No live configuration values, Core service or network settings were changed.
- Existing stored dashboard credentials were rejected, so authenticated hot reload was unavailable. Restarted only the AstrBot container, preserving its image and host network mode. It started at `2026-10-05T06:16:27.43580248Z` (14:16 Beijing time).
- Startup logs report gateway version `0.6.0` and `Meguri AstrBot gateway initialized`; dashboard is listening, and the container remains running. No gateway load errors were found. Bilibili, search-video, media-parser, image-search and music appear in startup logs; this alone does not establish external API or delivery success.
- From the AstrBot container, authenticated Core `/health` returned HTTP 200, status `ok`, service `meguri-core`.
- Loaded the deployed entrypoint and actual installed AstrBot filter classes in a separate process; all 11 synthetic checks passed: BV, long/short Bilibili URLs, search alias, command group, textless Bilibili card, wife passthrough, ordinary chat, explicit and wake-normalized Meguri commands, and an excluded handler leaving the message with Core. A gateway spy proves plugin-owned events create no Core call, preserve parsed parameters and leave plugin dispatch running. No plugin handler, Core turn or platform send was invoked.
- `output/astrbot-personal-priority-deployment.json` records timestamps, file hashes, backup, restart mode, startup/health checks and deployed routing checks without credentials.

## Delivery boundary

No implementation defects found in the verified scope. The server runs gateway 0.6.0; the repository's production-installation approval requirement was satisfied by the user's explicit installation request. No actual QQ end-to-end request, complete plugin-by-plugin live loading audit, external API/account test or delivery test was performed. Installed directories and startup mentions are not claimed to prove those behaviours. The OpenSpec change is retained, not archived.

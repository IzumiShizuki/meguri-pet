## Verification: deploy-astrbot-persona-routing

Verified 2026-10-21, Asia/Shanghai. Detailed operator report: `reports/astrbot-persona-routing-deployment-2026-10-21.md`.

| Dimension | Result |
| --- | --- |
| Completeness | 6/6 tasks; all 3 requirements and 5 scenarios have evidence |
| Correctness | 25 local tests; candidate and active isolated routing each 12/12; exact hashes/config comparisons pass |
| Coherence | Four-file deployment, original default preserved, native fallback, unchanged Core and production boundaries |

## Requirement mapping

1. **Preserve the deployed bot and configuration:** `verify.json` proves version 0.8.0, enabled mapping `["default"]`, exact four-file candidate hashes, unchanged pre-existing config values/schema presentation/other plugin hashes and unchanged container identity/image/network/ports/mount contents. Preflight proves current default `default` and no custom personas. `actual-default-persona`, `native-persona`, `switch-back-to-default` and forced/native/group checks cover backend selection. Existing Todo/report and independent reminder settings remain as before.
2. **Recoverable activation:** `stage.json` proves full plugin/config backup and exact restore rehearsal. The first activation's overly strict mount-order check actually executed automatic rollback; subsequent activation's original-file/config hash guards proved restoration to the prior baseline before retry. The backup, rejected candidate and diagnostics remain in the restricted release directory. The final candidate passed after comparison was corrected to compare mount content independently of array order.
3. **Verify without platform sends:** final startup includes 0.8.0 and gateway initialization. Candidate/live checks cover all named scenarios with zero platform sends and zero real Core turns. Existing Core health is `ok`, and its original startup timestamp is unchanged. The SDK boundary is synthetic, using the installed native persona resolver source; real QQ receipt is not claimed.

## Checks and findings

- Local persona routing: 25 passed. The prior implementation's 141-test AstrBot regression evidence remains its earlier snapshot, not an additional rerun in this deployment.
- Candidate and live server routing: 12/12 each, including explicit command, sender/group restrictions, plugin priority, unavailable resolver and native Todo noninterception.
- Backup/rehearsal covers 29 original plugin files plus exact configuration; four deployment hashes and all other file hashes checked.
- Fixed reviewed archive SHA256: `6fd604fe2ba18e6c84d520656d801aa190f6f7c879d2f96185b291090ccde48c`. The mutable workspace's unrelated gateway-settings helper was rejected during isolated preparation and excluded from deployment.
- OpenSpec strict validation and local staged whitespace checks pass.

No unresolved critical findings, warnings or skipped verification dimensions. The deployment was explicitly authorized by the human user in this chat. No other server, website, Core process, container image, credentials, permissions, destinations or database records were modified; no Git push or actual QQ test send occurred. Production rollback assets and local ignored evidence are intentionally retained.

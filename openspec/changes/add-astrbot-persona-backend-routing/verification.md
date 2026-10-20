## Verification Report: add-astrbot-persona-backend-routing

| Dimension | Status |
| --- | --- |
| Completeness | 6/6 tasks; all 3 requirements and 11 scenarios covered |
| Correctness | 25 focused policy/entrypoint/package tests; 141 AstrBot regression tests passed |
| Coherence | Opt-in native resolver policy; existing command and authorization boundaries preserved |

## Implementation evidence

- **Optional exact persona routing:** `persona_routing.py:PersonaBackendRouting`, `main.py` initialization and the pre-Todo gate, and `_conf_schema.json`. Tests cover exact/case-sensitive IDs, selected/native personas, enabled empty lists, legacy defaults, unchanged native event extras and pre-existing LLM suppression.
- **Effective persona and session isolation:** the policy reads the event's own current conversation and calls AstrBot's native effective-persona resolver. Tests cover defaults without conversation creation, resolved overrides taking precedence, switching on the next message, separate private/two-group sessions, missing/stale conversation, no-persona sentinel, unavailable APIs and sanitized lookup failures.
- **Explicit commands and routing boundaries:** entrypoint tests cover explicit Core access without persona lookup, denied senders, group permissions, disabled private/group automatic routes, plugin-command priority and native personas bypassing natural-language Meguri Todo drafting.
- **Operator setup:** `adapters/astrbot/README.md` documents actual-ID configuration, private/group switches, native model prerequisite, independent histories, unsupported-host fallback and rollback. Plugin version is 0.8.0.

## Validation

- `python -m pytest tests -k astrbot`: **141 passed**, 407 deselected; one pre-existing FastAPI/Starlette HTTPX deprecation warning.
- `python -m pytest tests/test_astrbot_persona_routing.py`: **25 passed** after formatting.
- Ran the 25 new tests again against an isolated export of the staged Git tree: **25 passed**, proving that the local commit does not depend on unrelated dirty plugin changes.
- Existing `D:/environment/anaconda3/Scripts/ruff.exe`: new policy/tests pass lint and format checks; entrypoint passes fatal syntax/undefined-name checks.
- Executed the actual downloaded AstrBot **v4.28.1** `resolve_selected_persona` method against the new policy: **7 passed** (new local-runner defaults for both backends, conversation selection, forced native/Meguri overrides, no persona, remote-runner default). Evidence: ignored `output/astrbot-persona-routing-native-check.json`.
- Rebuilt `output/astrbot_plugin_meguri_gateway-persona-0.8.0.zip`. Compiled all Python entries, verified policy/main/schema/metadata bytes against final sources, and checked absence of runtime state/token files. Evidence: ignored `output/astrbot-persona-routing-package-check.json`.
- `openspec validate add-astrbot-persona-backend-routing --type change --strict --no-interactive` and staged whitespace checks pass.

## Findings and handoff

No critical findings, warnings or skipped local verification dimensions. Pre-existing working-tree changes remain outside this commit; only this task's changes were selectively staged. No production installation, runtime configuration edit, service restart, real QQ send, or Git push was performed. The switch remains disabled until an operator installs the package and supplies actual persona IDs. Real platform delivery remains a deployment follow-up, not a local-test claim.

Cleanup note: automatic approval review rejected recursive removal of the ignored `output/persona-routing-baseline` verification directory (`blocked by policy`). The temporary export and source snapshots were retained; this does not affect the plugin archive or local commit.

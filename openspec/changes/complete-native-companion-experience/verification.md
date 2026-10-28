# Verification: complete-native-companion-experience

Date: 2026-10-05. Scope: native desktop companion features; excludes operating-system expansion, main model/provider configuration, production deployment and database migration.

## Summary

| Dimension | Result |
|---|---|
| Completeness | 26/26 implementation and evidence tasks complete; 14 requirements, 25 scenarios mapped |
| Correctness | Unit, authority, contract, Electron and representative Live2D checks pass; real database and user voice/model packages are separately unverified |
| Coherence | Host-owned preferences and named IPC retained; AIRI renderer reused without modifying its checkout; Python native_pgvector remains the authority; GPT-SoVITS/default environment preserved |

## Requirement and scenario evidence

Paths below are relative to this repository.

| Requirement | Implementation | Scenario evidence |
|---|---|---|
| Managed character resources | `apps/desktop-meguri/src/main/live2d-store.mjs`, `live2d-protocol.mjs`, `src/main/index.mjs` | Directory/ZIP copy, activation/removal, traversal/external/missing/executable rejection: `live2d-store.test.ts`; constrained asset URLs/origins: `live2d-protocol.test.ts` |
| Interactive rendering and fallback | `scripts/live2d-integration.mjs`, `src/renderer/components/Live2DCharacter.vue`, both Stage surfaces | Official isolated Haru render with 232 hit samples, Head/Body, pointer, TapBody and expression; forced missing model recovers to PNG: `scripts/live2d-runtime-smoke.mjs`, `.codex-runtime/live2d-smoke/runtime.json` |
| Verified and consented formal memory | `conversation.ts`, `DesktopMemoryIdentity.java`, `DesktopMemoryService.java`, `services/meguri_core/desktop_memory_bridge.py` | Consent/readiness gating and withdrawal: `conversation.test.ts`; local credential and hosted allowlists: `DesktopMemoryTest.java`; token/provider/tenant rejection: `tests/unit/memory/test_desktop_bridge.py` |
| Scoped memory management | `DesktopMemoryController.java`, Python desktop bridge, `MemorySettings.vue`, `services/memory-api.ts` | Actor overrides rejected by strict bodies; foreign candidates/memories not mutated; existing review/delete/restore/export service contracts reused; management unavailable state checked in Electron. Real pgvector mutation not run |
| Bounded summary controls | Java server snapshot extraction, Python checkpoint-based persistent idempotency, conversation summary controls | Recent 20-message bound, credential filtering, duplicate checkpoint, consent withdrawal and failure/cancellation excluded from successful-turn count tested; Python first result retained when extraction changes |
| Safe rich replies | `utils/message-format.ts`, `MessageContent.vue`, ChatDock/Galgame | Parser tests cover HTML/link/image/math/code safety and malformed fallback; Electron verifies headings, table, formulas and absence of executable/image nodes |
| Copy and keyboard access | `MessageContent.vue`, shared CSS | Native buttons, focus styling and selectable text; isolated Electron mocks clipboard and verifies exact Markdown/math source plus copy feedback |
| Local speech model lifecycle | `local-services/tts-runtime/model_manager.py`, `server.py`, `main/speech-resources.mjs`, `SpeechSettings.vue` | Import/hash/managed deletion/unsupported format/host auth/failed switch rollback tested; mocked HTTPS download rejects corrupted weights and preserves default; executable checkpoint rejected before unsafe upstream loader |
| Playback preferences | `services/speech-playback.ts`, conversation and Live2D integration, existing quick mute buttons | Global/surface switches, speed bounds, volume normalization and ONCE dedup tested. Replies/reminders delegate to same helper; text path remains independent of synthesis |
| Wallpaper and bubble controls | `desktop-wallpaper.mjs`, preference normalization, `PersonalizationSettings.vue`, shared CSS | Bounded decoded image, MIME/data signature, remote/oversized rejection tested; live wallpaper, palette and narrow layout checked in Electron |
| Preset and system colors | `conversation-palette.ts`, preferences store, host accent callback | All preset light/dark text contrasts and legacy migration tested; system accent is host-derived and validated; Galgame uses its dark palette independently |
| Privacy choices and withdrawal | `PrivacyDialog.vue`, `PrivacySettings.vue`, `privacy-policy.ts`, conversation consent/history sync | No consent means no hello/turn/memory readiness call; withdrawal during hello/active stream cancels locally; history save disabled preserves prior data until explicit clear. Electron covers first-run choices and post-withdrawal send rejection |
| Sanitized diagnostics | `desktop-diagnostics.mjs`, main export dialog/handler | Whitelist, bounded errors, secret/text/path exclusion tested; renderer receives only outcome/selected destination, no raw private configuration |
| Accurate release checks | `desktop-updates.mjs`, main startup/manual handlers, `UpdateSettings.vue`, Stage banner | SemVer/prerelease, no-release/rate-limit/failure states, coalescing, strict release URL and safe opening tested; no installer/publisher added; preview checks do not contact GitHub |

## Commands and results

- `pnpm --filter @meguri/desktop-meguri test:unit`: 52 files, 304 tests pass.
- `pnpm --filter @meguri/desktop-meguri typecheck` and `build`: pass. Build has >500 KB chunk warnings; performance not measured.
- `pnpm --filter @meguri/desktop-meguri test:preload`, `test:instance`, `test:tray`: pass after clearing session-only `ELECTRON_RUN_AS_NODE`.
- `electron scripts/companion-experience-visual-smoke.mjs`: pass, isolated preview sessions, network APIs blocked, 9 screenshots, no console errors.
- `MEGURI_LIVE2D_SMOKE_MODEL=<isolated Haru>` + `electron scripts/live2d-runtime-smoke.mjs`: pass. Missing-model console error is expected and recorded separately from unexpected errors.
- `py314/python -m pytest -o addopts= -q --junitxml=tmp/companion-python-tests.xml`: 438 passed, 8 skipped, 1 existing Starlette/httpx deprecation warning.
- GPTSoVits Python `-m unittest local-services.tts-runtime.test_safe_weights -v`: 2 pass; malicious pickle does not execute.
- JDK 21/Maven `-B -DargLine=-Djdk.net.unixdomain.tmpdir=D:\program\meguri-pet\tmp\jdk-unix-sockets test`: 573 total, 0 failures/errors, 3 skipped. Initial Windows JDK loopback failure disappears with the existing launcher's short socket-directory configuration; IPv4 alone did not resolve it.
- `node --test apps/desktop-airi/src/web-server-security.test.mjs`: 12 pass, including real isolated mock-upstream forwarding and foreign-client/path rejection.
- `pnpm test:ts`: 52 pass. Adapter canonical fixtures, schema consistency and installed AIRI fixture tests pass.
- `openspec validate complete-native-companion-experience --type change --no-interactive` and `git diff --check`: pass.

## Findings

CRITICAL: none in the implemented scope.

WARNING / runtime prerequisites:

1. A Meguri-specific PostgreSQL/pgvector connection file and internal bridge credential are not available. Real authoritative persistence/retrieval is not enabled or verified. Provide outside-repository configuration and already migrated schema, then follow `docs/native-desktop-memory.md`; offline authority tests do not claim a real database write.
2. No authorized Meguri Live2D model or additional compatible voice profile was supplied. The runtime is verified with an isolated official sample, and profile lifecycle/rollback are tested without replacing installed GPU weights. Verify the actual user packages after importing them.
3. Already running Electron/Core/gateway/TTS processes retain old code until restarted. They were not forcibly restarted, no scheduled tasks were changed, and no external provider/database state was modified.

SUGGESTION: measure load/render performance before optimizing the large main and lazy Live2D chunks. `NOT_MEASURED`; no unsupported speed claim is made.

All scoped implementation tasks are complete with the above operational prerequisites. Keep the change open; no archive was requested.

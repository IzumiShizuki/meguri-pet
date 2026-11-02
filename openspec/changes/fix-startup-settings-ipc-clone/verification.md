# Verification — 2026-11-02

## Result

| Dimension | Result |
| --- | --- |
| Completeness | 4/4 implementation tasks; 2/2 requirements implemented |
| Correctness | All three specification scenarios covered by executable tests |
| Coherence | Plain renderer DTO before contextBridge; unchanged main validation and persistence/application separation; identical feedback suppressed |

No critical findings or spec/design deviations. This change is not archived.

## Reproduction and fix

Before the fix, the isolated Electron script using actual Vue refs, extracted production save handler, production sandboxed preload and real contextBridge/IPC failed with:

`{"ok":false,"dirty":true,"saving":false,"messages":["Windows 启动设置应用失败：An object could not be cloned.","Windows 启动设置应用失败：An object could not be cloned."]}`

The main-process stub was not reached. The failure occurs while transferring the reactive draft, before Windows task application. The duplicate feedback comes from the two actual SettingsView paragraphs. After constructing an explicit two-boolean DTO and suppressing identical operation feedback, the same Electron test passed seven requests (four combinations, pending, rejected save, and recovery).

The test intentionally rejects one IPC request. Its expected logged exception does not indicate a test failure. The first harness attempt timed out because its ESM entry awaited app readiness at top level; the harness now starts through whenReady().then(), and the subsequent pre-fix reproduction and post-fix verification completed normally.

## Executed checks

- `pnpm --filter @meguri/desktop-meguri exec vitest run src/renderer/views/SettingsView.startup.test.ts src/main/startup-preferences-save.test.ts`: 11/11 passed.
- `pnpm --filter @meguri/desktop-meguri test:startup-settings`: passed, 7 real IPC requests; no production main import or Windows configuration call.
- `pnpm --filter @meguri/desktop-meguri test:unit`: 74 files, 578 tests passed.
- `pnpm --filter @meguri/desktop-meguri typecheck`: passed.
- `pnpm --filter @meguri/desktop-meguri exec vite build --outDir ../../output/startup-ipc-verification-20261102`: passed. Existing chunk-size warning; active dist was not replaced.
- `openspec validate fix-startup-settings-ipc-clone --strict --no-interactive`: passed.
- `git diff --check`: passed.

## Resumed acceptance checks

- Core: one GET to documented `http://127.0.0.1:18080/health` returned HTTP 200, status `ok`, runtime `java`. No model conversations, production Todo writes, or persistent sessions created.
- Shizuki: lyric route/alignment tests passed 23/23. Real App.vue and player played the local synthetic WAV/LRC with audio muted: ordinary page showed lyrics, `/auth` and `/auth/register` hid them while audio remained unpaused, and returning home restored lyrics. Screenshots were visually inspected for home/login; login OAuth buttons remained visible.
- Fixture: retained under `C:/Users/IzumiShizuki/Documents/Codex/2026-11-02/task/lyric-active-fixture-20261102`. Fixed only its explicit xmldom prebundle resolution and enabled audio looping. Screenshots: `playing-home.png`, `playing-login.png`, `playing-register.png`. External assets/API were blocked by fixture CSP and the browser localhost allowlist; missing remote decorative images are expected. No login submitted. The browser and both fixture servers were stopped.
- Android: using existing Java 17 activation and SDK, `gradlew.bat assembleDebug --offline --no-daemon --console=plain` still failed before compilation with `java.io.IOException: Unable to establish loopback connection`. No retry, APK, local.properties, or security/network configuration changes. This remains an independent blocker, despite successful Core HTTP and Electron IPC.

## Git scope and remaining work

The initial verification read local Git state only; publication was authorized in the subsequent user message.

- Meguri origin: `https://github.com/IzumiShizuki/meguri-pet.git`; branch `fix/dialogue-reliability-20261028`, 33 commits ahead of its locally cached upstream. This fix adds SettingsView changes, a package test script, two test harness scripts, the startup test, and this OpenSpec change. Existing ChatDock/styles/GalgameStage/MessageTimestamp and other documentation/OpenSpec work were preserved.
- Shizuki origin: `https://github.com/IzumiShizuki/shizuki-site.git`; branch `codex/local-master-frontend-20261011`, 1 commit ahead of locally cached origin/master (`3c6b23e7`). Existing App.vue lyric routing changes and two globalLyricBarRoute files were preserved. Existing publisher node_modules and lockfile remain untouched.
- Checked-in Meguri deployment workflows are manual workflow_dispatch; its environment CI push filter lists main and feat/environment-isolation. Shizuki checked-in CI runs on push/PR/manual. These files do not establish whether external deployment hooks exist, so automatic production deployment cannot be ruled out.
- This source fix is verified but not loaded into the user's running app. Live Task Scheduler acceptance remains pending under the earlier `fix-startup-admin-disable-feedback` change. No user window was closed/reloaded and no startup preference was applied.
- Android loopback diagnosis, old EXE acceptance, real account cross-client synchronization, and any remote publication remain incomplete. Earlier automatic approval rejection for old EXE/push was not retried or bypassed.

## Authorized publication and restart preparation

The user subsequently requested restart and push. Both origins were fetched with prune; the counts above remained 33 and 1. Publication targets the existing task branches (`fix/dialogue-reliability-20261028` and `codex/local-master-frontend-20261011`), not main/master. Only the relevant repair files and specification updates are staged; unrelated working-tree changes remain local.

The active renderer was backed up to `D:/program/meguri-pet/output/desktop-renderer-before-restart-20261102`; all 66 file hashes matched. The stored preference is openAtLogin=true and runAsAdministrator=true; the existing Meguri Desktop task reports Highest run level. Normal UI exit did not stop the old instance. One ordinary Stop-Process attempt for its known main PID 22992 returned Access denied. The user was immediately informed and asked to exit the app manually. No elevation or alternate termination method was attempted, and activation remains pending until that exit.

## Completed restart

The user confirmed exiting Meguri. The old PID was absent before activation. The previously verified renderer was copied into `apps/desktop-meguri/dist/renderer` and all 66 source/destination hashes matched. The existing `start-meguri-desktop.bat` launcher was invoked without changing the user's startup preferences or adding launch flags.

The new Meguri window was observed and visually inspected with the character and main interface displayed (new main PID 44556). Core `/health` returned status=ok, runtime=java. The saved preferences remain openAtLogin=true/runAsAdministrator=true. Read-only checks show the existing task at Highest run level with an MSFT_TaskLogonTrigger; the two corresponding registry flags remain 1. No settings Save operation, privilege downgrade, or UAC interaction was performed. This completes the earlier deployment/health/current-startup-state acceptance task; changing startup selections and a real UAC cancellation remain covered by isolated tests rather than live user-setting mutations.

The initial fix was pushed as `3260d52` on Meguri's task branch; the Shizuki account-route fix was pushed as `5529aa2c` on its task branch. This completion record is published in a follow-up documentation commit. Existing unrelated working-tree files remain uncommitted.

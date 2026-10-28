# Verification: complete-p0-learning-and-runtime-recovery

Date: 2026-10-08, Asia/Shanghai. Planning context loaded with `openspec status`
and `openspec instructions apply`; proposal, design, all three delta specs and
tasks were read. Verification covers completeness, correctness and coherence.

| Dimension | Result |
| --- | --- |
| Completeness | 21/21 implementation, activation and documentation checks complete |
| Correctness | All 8 requirements and 18 scenarios have code/test evidence; isolated PostgreSQL, independent JVMs and actual local Core/gateway/native-client recovery passed |
| Coherence | Existing protocol, authenticated gateway, immutable B baseline, PostgreSQL stores and Notion authority hierarchy reused |

## Requirement and scenario evidence

| Requirement | Implementation / checks |
| --- | --- |
| Optional native supervision | `apps/desktop-meguri/src/renderer/components/TrainingSettings.vue`, `TrainingFeedbackPanel.vue`, `stores/training-feedback.ts`; explicit opt-in and per-submit consent; native tests and isolated Electron DOM/actions/screenshots |
| Durable owned feedback | `TrainingFeedbackArchive.java`, `TrainingFeedbackService.java`, `TrainingFeedbackController.java`; atomic pending archives, append intents, file lock, stable body-bound identity, owner checks and withdrawal. `TrainingFeedbackRecoveryTest` covers reconstruction, wrong owner, no consent, conflicts, failed append and torn trailing append; `TrainingFeedbackControllerTest` checks authenticated legacy/native route separation |
| Review before export | `training/llm/scripts/feedback.py`, `feedback_dataset.py`; consent/provenance validation, redaction, pending imports, digest-bound reviewer decisions, schema-valid selected/corrected targets, no note-as-label; Python fixtures cover rejected and revoked records |
| Reproducible grouped exports | Conversation/scene/sample connected groups, canonical duplicate exclusion, prior assignment pinning, separate preference files, required-source group token validation; fixtures cover deterministic hashes, merged groups, locked scene exclusion and rehashed grouping/target tampering |
| Existing training gate compatibility | Explicit `meguri-feedback-augmented-sft-v1` branch in `training_utils.py`, baseline parent approval anchor, retained strict 2626/566 original branch, freshness/hash validation. Real approved parent independently checked; training/promotion not executed |
| Explicit PostgreSQL runtime activation | `runtime-postgres-config.ps1` wired into the existing launcher; dedicated absolute secret file, profile/datasource selection, rejection of missing/invalid postgres config and memory credential files, explicit in-memory rollback; `test-runtime-postgres-config.ps1` passed under Windows PowerShell 5.1 and PowerShell 7. Existing synced administrator configuration provisioned the server's independent restricted `meguri_turn_runtime` database; actual Core started with postgres profile and persisted the native client's synthetic Turn/events/context |
| Restart recovery invariants | Existing journal/context/outbox/job stores reused, recovery SQL whitespace fixed in `PostgresTurnJournal.java`. `PostgresRestartRecoveryIntegrationTest` verifies completed events/context, partial interruption, outbox dedup and lease identity; independent writer/reader JVMs restore pending feedback. `PostgresCoreProcessRestartIntegrationTest` launches three full Spring Core JVMs and verifies authenticated HTTP/idempotency, SSE/Last-Event-ID, restored context, pending feedback, saved receipt/withdrawal, partial terminal and a seeded job's stable identity/reclaimed lease with consent-required write skipped. The new Core entry also checks the actual PostgreSQL bean selections |
| Evidence identifies its environment | Local learning report and Notion distinguish offline tests, simulated UI API, real isolated PostgreSQL/component JVM/full Core process recovery, and actual local desktop activation using synthetic data in an isolated client profile. Remote hosted-Core deployment, GPU training and full disaster-recovery certification remain outside this task. Temporary schema cleanup independently confirmed |

## Results

- Final Java full run: 589 tests, 0 failures/errors, 5 conditional skips. Real PostgreSQL component and full Core process tests ran separately with opt-in. Tests specify `-Djdk.net.unixdomain.tmpdir=D:\program\meguri-pet\tmp` to avoid this machine's long temporary socket path.
- Full Core restart acceptance passed in 52.752 seconds: PID 54548 -> 69208 -> 72656; temporary schema `meguri_p0_core_66bbd81aa61e4b179585f3feac720ccb` cleaned. Retained XML: `tmp/p0-core-http-restart-results.xml`. Read-only inspection confirmed no test schemas remained and the dedicated Runtime secret was still absent.
- Two PostgreSQL-only application defects were found by this acceptance: session/context interface alias injection ambiguity (fixed with the primary context entry) and Set array order falsely rejected as changed Capability metadata after a new JVM. Typed descriptor equality fixes the latter; reversing a persisted modes array now passes, while changing its health still fails. No permissions or immutable-version conflict check was disabled.
- The final documented runner `check-runtime-postgres-recovery.ps1` was reproduced successfully with both opt-in tests (2 passed, 0 failures/errors/skips), after the final Java regression run. Log: `tmp/p0-complete-recovery.log`; retained XML: `tmp/p0-final-Postgres*IntegrationTest.xml`. Fresh read-only schema inventory confirmed cleanup after all successful and failed diagnostic runs.
- After the user's server activation instruction, found administrator credentials in the existing synced `D:\program\shizuki-site\resouces\yaml\common-config.yaml`, resolving its existing Spring placeholders privately. Provisioned only `meguri_turn_runtime` / `meguri_turn_owner` on `111.228.35.186:5432`; runtime role has no superuser/createdb/createrole/replication rights. Connection file is outside the repository, ACL protected for the owner/SYSTEM/Administrators. Existing memory and project roles/databases were not modified.
- Repeated both real recovery suites against the new dedicated database: full Core 55.201 seconds, component/cross-JVM 41.668 seconds; 2 passed, 0 failures/errors/skips. `tmp/p0-dedicated-runtime-recovery.log`, `tmp/p0-dedicated-Postgres*IntegrationTest.xml`; zero temporary schemas remain.
- Actual local activation and recovery: Core PID 49108 -> 10652 -> 39312, gateway PID 32836 -> 44908 -> 22024, successful native renderer processes 32964 -> 53388 -> 12056. Real configured provider, real gateway and restricted server connection were used with a synthetic session and isolated Electron profile. Same Turn `turn_57e9b0ef52fa48c4`, all 13 SSE events, snapshot state and idempotency survived two Core/gateway restarts. Pending feedback survived, explicit UI consent saved a receipt, the next Core/client restart recovered the same receipt and UI withdrawal persisted revoked status. Read-only inventory found one Turn, 13 events, context revision 2, zero formal-memory jobs and no pending feedback. Evidence: `tmp/p0-native-live/evidence.json`, `final-runtime-inventory.json`, phase runtime records and client logs.
- Actual launcher acceptance exposed Windows PowerShell 5.1 decoding a localized UTF-8 file without BOM and selecting an incompatible Split overload. Preserving the BOM and explicitly using the bounded char-array/three-argument overload fixed both; configuration checks now pass under both installed PowerShell runtimes. The Core remains running with postgres profile; only the acceptance renderer server was stopped.
- Python feedback/baseline/training utilities: 44 tests passed; real parent `meguri-text-sft-v1-532aca8b1a5d` confirmed at 2626/566.
- Native affected checks: 4 files / 43 tests passed; typecheck and production renderer build passed.
- Native full suite reproduced after activation: 343 passed / 1 failed. `reminder-flow.test.ts:33` expects clarification for `明天3点` whereas the existing reminder intent returns review; this change did not alter reminder parsing or its test. Its failure stops the combined `test` script before preload/instance/tray stages, so that combined command is not reported as passed.
- Gateway and legacy feedback: 15 tests passed.
- Isolated renderer smoke: no assumed consent, stable submission key, saved/withdrawn receipt and narrow layout passed; fake API and isolated profile, no user/server writes.
- Configuration script checks and task-specific whitespace diff review passed.
- Notion learning page and six existing pages were updated and read back; follow-up application recovery evidence is reflected in the learning page, Turn 5 and operations E. Five Mermaid code blocks are intact. Strict OpenSpec validation passed.

## Issues and assessment

**CRITICAL:** none. The previous task 4.5 configuration gate is closed by actual server provisioning, local activation and native-client/Core/gateway restart evidence. Earlier absent-secret observations describe the pre-activation state.

**WARNING:** the native full suite retains the unrelated reminder time assertion failure; its precise result is documented instead of reporting a blanket pass.

**SUGGESTION / separate data revision:** the active runtime prompt differs from the approved B training prompt, and B contains four normalized cross-split duplicate prompt/target pairs. This bridge pins B and blocks feedback duplicates; a baseline revision and prompt alignment require separate approval/evaluation.

All requested P0 implementation and actual local activation checks are complete.
No checks were skipped in the three verification dimensions. There is one
unrelated native-suite warning to address separately; the task is ready for
review/archiving with that warning, but remains unarchived. The old in-memory
service's historical event log cannot be reconstructed retroactively; durability
applies to Turns accepted by the activated PostgreSQL runtime. No commit, remote
hosted-Core deployment, model training or model promotion was performed.

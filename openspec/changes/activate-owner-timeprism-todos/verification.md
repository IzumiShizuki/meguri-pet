# Verification: activate-owner-timeprism-todos

Date: 2026-10-19, Asia/Shanghai.

| Dimension | Result |
|---|---|
| Completeness | 7/7 tasks complete; 3 requirements implemented |
| Correctness | All 8 scenarios mapped to code and tests/acceptance |
| Coherence | External secrets, exact private identity, durable reconciliation, independent privileged authentication and local collector boundaries preserved |

## Requirement and scenario evidence

| Requirement/scenario | Implementation and evidence |
|---|---|
| Verified owner / login succeeds | Authenticated HTTPS account ID bound to both services; final probe checks owner equality, exact private binding, external file readability and restricted mounts. Candidate/active live reports authenticate the same owner. |
| Account mismatch | `TimePrismTodoWriter.login` rejects a different returned ID before POST; writer test verifies zero writes. Controller test rejects another owner. Live acceptance rejects another private sender and group confirmation. |
| Private writer preserves chat | `CoreIdentityVerifier.verifyPrivilegedScope` authenticates independently of the global filter; `TimePrismReminderController.requireTodoWriter` requires exact owner/client/session. Identity tests cover missing credential 503, missing token 401, mismatched scope 403, valid scope and legacy chat. Candidate/active/local ordinary conversation acceptance passes. |
| Repeated request | Writer test returns identical verified/cached/reloaded receipt with one remote POST; live tests confirm adapter and Core idempotency with one account row. |
| Minute precision receipt | `TimePrismTodoWriter.receipt` formats verified times with LOCAL_MINUTE. Writer tests cover first, cache, reload, and uncertain recovery; controller asserts JSON minute precision. Red run had 3 failures; green run passed. Strict Python receipt validation remains unchanged. |
| Stalled body | Bounded body subscriber plus timed future and cancellation covers body completion and 1 MiB cap. Fixture tests stall both login and committed POST after headers, bound completion and verify later recovery. Red body test exposed the old hang. |
| Uncertain retry | Journal persists uncertain before POST; marker reconciliation verifies exact fields. Tests cover committed stalled and oversized responses, unmatched uncertain request, duplicate new request ID blocking, and original-ID recovery without additional POST. |
| Acceptance cleanup / zero platform sends | Candidate and active owner acceptance each passes 11 checks, confirms exactly one unique future task, removes its exact ID, verifies absence and preserves all 4 existing tasks. Generic Core simulation remains unable to perform live Todo writes. Isolated SDK/collectors send 0 QQ messages. Both earlier failed-run tasks were also deleted. |

## Checks

- Java package: 651 tests, 646 passed, 5 skipped, zero failures/errors. Focused 15/15 included in total.
- Relevant Python adapter regressions: 40/40.
- Candidate, active and local ordinary dialogue: each 4 scenarios and 5 checks, zero failed checks.
- Active deployment probe: 21/21 checks; source/runtime hashes match, candidate stopped, rollback assets preserved, unrelated configuration unchanged.
- OpenSpec strict validation passes. No checks were omitted from this change's three verification dimensions.

No critical issues, warnings or suggestions remain for this change. QQ reminder delivery remains separately disabled as designed; real QQ receipt, production fault injection and renderer/native UI testing were not claimed. Detailed evidence, deployment hashes and rollback steps are in `reports/owner-todo-activation-2026-10-19.md`. Change retained unarchived.

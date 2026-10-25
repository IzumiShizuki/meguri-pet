# Verification — add-desktop-memory-corrections

Date: 2026-10-23 (Asia/Shanghai). Schema: spec-driven. Branch: `codex/companion-memory-corrections`. This change implements and validates the source/build artifacts; the previous deployed runtime has not been switched to this feature.

Using change: `add-desktop-memory-corrections` with `openspec-verify-change`; select another explicitly with `$openspec-verify-change <name>` if needed.

| Dimension | Result |
|---|---|
| Completeness | 12/12 tasks; 3 requirements and all 8 scenarios covered |
| Correctness | Scoped detail, strict correction payload, explicit review, durable retry, stale base rejection, recoverable UI, session isolation and old-backend compatibility verified |
| Coherence | Existing provider, supersede/review rules, desktop identity, gateway credentials and visual system reused; no migration, dependencies or recall-weight changes |

## Requirement mapping

| Requirement | Implementation and scenario evidence |
|---|---|
| Scoped current-version explanation | `desktop_memory_bridge.py` detail projection and `DesktopMemoryController` authenticated GET; Python tests verify owner isolation, missing metadata and exclusion of raw provenance/structured content/actor identifiers. Core HTTP tests verify identity, invalid path and trusted scope forwarding. Gateway test verifies the exact path through a real isolated proxy process. |
| Reviewed optimistic correction | Existing `MemoryService.propose_supersede` and approval transaction retained; new bridge constructs actor/source and requires a base UUID. The real service with an isolated repository fixture verifies same-request candidate reuse, no immediate item mutation, stale/deleted/foreign item rejection and later stale approval rejection; valid approval reaches the existing version-replacement repository boundary. This is not a real PostgreSQL write test. |
| Recoverable accessible desktop editing | `memory-correction-editor.ts`, `MemoryDetail.vue` and session/generation guards in `MemorySettings.vue`; 7 editor tests plus browser checks cover draft preservation, request-ID reuse, changed version, double-submit suppression, disposal, old list responses, focus recovery and navigation to review. Python/Core capability status and renderer gating are tested against old/new responses. |

## Test evidence

- Desktop unit suite: **65 files / 464 tests passed** with four workers. `vue-tsc --noEmit` and final Vite production build passed. Existing large-bundle warnings remain.
- Python formal memory regression: **129 passed**, one existing warning, using the installed `py314` environment. No production database mutation was performed.
- Core: **27 tests passed** across `DesktopMemoryCorrectionHttpTest`, `DesktopMemoryTest`, `PythonMemoryGatewayTest`, the companion reach-out controller/service/provider tests and `MeguriCoreApplicationTest`; package build passed. JDK 21 and the previously established short socket directory were reused through `-DargLine=-Djdk.net.unixdomain.tmpdir=D:\program\meguri-pet\tmp\jdk-unix-sockets`.
- Gateway: **13 tests passed**. The expanded memory proxy test runs a real gateway child against a separate loopback fixture, verifying detail/correction routing, preserved `X-Request-ID` and JSON, host credential injection, and rejection of wrong methods/clients/unknown paths.
- Electron `test:chat-interaction`: passed for stage regression. New memory UI acceptance uses the actual Vue settings in a dedicated browser session with synthetic intercepted responses: **27 assertions passed**, no browser errors. It does not claim a live production database edit.
- Light/dark screenshots inspected at 480px and a desktop viewport; long source IDs wrap, labels and input counters are visible, and controls remain inside the viewport. Measured new hint contrast against the settings canvas: light **4.92:1**, dark **12.05:1**.
- Impeccable initial static findings: `[]`. Required independent finish review identified contextual error-copy and removed-control focus issues; both were fixed and verified in the browser, including the review-tab handoff. No new visual world or design-system replacement was introduced.
- Final strict OpenSpec validation and scoped whitespace checks passed. Existing deployed desktop gateway/Core health was checked read-only and returned HTTP 200 / `status=ok`.

The first new service fixture failed its foreign-owner assertion because the pre-existing fake repository ignored tenant/user filters; the fixture was scoped to match the real repository contract. The first HTTP test expected 403 for a wrong credential; the established controller returns 401, and the test was corrected. An initial loopback regression invocation lacked the already documented JDK short-socket option; the final run uses it and passes. Runtime authentication and socket behavior were not weakened to satisfy tests.

## Issues and delivery limits

**Critical:** none. **Warnings requiring implementation changes:** none.

Coordinated rollout requires Python bridge, Java Core, gateway and renderer updates. Status capability gating prevents the new entry from appearing with an older Core/bridge; the gateway whitelist must also be updated when deploying. Existing memories/candidates use the same schema and governance. Typed drafts remain only in the current editor; cancelling, selecting a different memory or switching sessions ends that draft.

The working tree already contained substantial uncommitted project work. It was preserved when creating the branch, and no mixed Git commit or push was made. The change remains unarchived. Build hashes, acceptance paths, usage and rollout notes are recorded in `reports/memory-corrections-delivery-2026-10-23.md` and `docs/desktop-memory-corrections.md`.

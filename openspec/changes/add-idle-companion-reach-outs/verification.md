# Verification — add-idle-companion-reach-outs

Date: 2026-10-22 (Asia/Shanghai). Schema: spec-driven. The initial verification below covers local source implementation. The subsequently authorized deployment and live networking verification is recorded in the final section.

| Dimension | Result |
|---|---|
| Completeness | All 9 implementation/evidence tasks complete; 4 requirements implemented |
| Correctness | Eligibility, consent/interaction races, quiet hours, limits, identity binding, expiry and assistant continuity covered |
| Coherence | Existing Core identity, persona, scoped context, notice delivery and playback ledger reused; no tools, synthetic user turns or memory extraction introduced |

## Evidence

- `pnpm --filter @meguri/desktop-meguri exec vitest run --maxWorkers=4`: **64 files / 457 tests passed**. This includes the new idle producer, consent/session/focus/interaction cancellation, local quiet hours, failure backoff, one unanswered starter, acknowledgement retry, mute/voice/off gates, daily cap across sessions/pruned receipts and the third-slot reservation race.
- `pnpm --filter @meguri/desktop-meguri typecheck`: passed.
- `pnpm --filter @meguri/desktop-meguri build`: passed. Existing large-chunk warnings remain; no dependency or asset pipeline changes were made.
- Existing `test:chat-interaction` Electron smoke: passed, including real stage rendering, compact/expanded controls, keyboard drawer, visibility cycles and narrow header. Its ignored result is `.codex-runtime/chat-interaction-smoke/result.json`. This is a stage regression check, not proof of a live model-generated reach-out.
- JDK 21 through `D:\environment\activate-dev-env.ps1 21`; `mvn -q '-Dtest=CompanionReachOutServiceTest,CompanionReachOutControllerTest,ReachOutProviderTest,LangChain4jLlmProviderTest,CompanionContextRuntimeTest,CoreIdentityVerifierTest' test`: **51 tests passed** (6 + 2 + 2 new companion/provider tests; 22 + 13 + 6 existing related tests).
- Full Spring application startup check `MeguriCoreApplicationTest`: **3 tests passed**, for **54 Core tests in total**. A plain invocation first reproduced the repository's previously documented Windows JDK `Unable to establish loopback connection` / `UnixDomainSockets.connect` failure. Reusing the existing launcher's short socket-directory setting with test-JVM `-DargLine=-Djdk.net.unixdomain.tmpdir=D:\program\meguri-pet\tmp\jdk-unix-sockets` made the same startup check pass. No runtime source or machine-wide environment change was needed; evidence is in ignored `java/meguri-core/target/reach-out-application-smoke.log` and Surefire XML.
- Controller verification exercises draft HTTP requests, validation, authenticated identity mismatch rejection, draft acknowledgement through the path ID, repeated acknowledgements and the resulting assistant-only context. Service tests additionally verify stale branches, expiry, concurrent/failed attempt caching, and in-flight user activity.
- Impeccable detector over the changed companion settings UI: `[]` (no findings). Settings copy preserves the incumbent layout.
- `openspec validate add-idle-companion-reach-outs --strict`: passed. Scoped `git diff --check`: passed.

The first new Core test run exposed incorrect use of the existing appendNode overload in the test fixture; the fixture was corrected. One fully parallel desktop rerun hit the existing 5-second limit in a loopback transport test and a new ledger test under load. The complete suite then passed with four workers and unchanged test timeouts. No runtime change was used to hide these failures.

## Requirement mapping

| Requirement | Implementation and verification |
|---|---|
| Bounded idle scheduling | `companion-reach-outs.ts`, StageView lifecycle; scheduler tests for all three intervals, quiet hours, typing/visibility/consent/focus, reload waiting, races and no repeated unanswered starter |
| Separate grounded generation | `CompanionReachOutController/Service`, optional `LlmProvider.generateReachOut`, LangChain4j persona/data messages; isolated HTTP/provider tests and bounded cached attempts |
| Delivery acknowledgement and continuity | `companion-notice-delivery.ts` rechecks a producer predicate under/after its claim; separate acknowledgement appends via `SessionContextStore.appendAssistantIfCurrent`; stale, expired and idempotent tests |
| Existing presentation controls | `reach_out` notice policy honors initiative, mute and surface controls; ChatDock publishes voice session activity; daily budget shares the ledger lock, remains independent of explicit reminders and is covered by regression tests |

## Limits and operating status

- Independent generation uses only the current persona and at most eight bounded recent server-owned messages. It deliberately does not add long-term-memory retrieval or web retrieval to the starter itself.
- Generation decline/error is cached and retry is bounded; candidate lifetime is ten minutes. A process crash between local display and remote acknowledgement can leave only local display history. In-flight acknowledgement retries stop after expiry or a session/consent change; changed context is never appended onto a new leaf.
- Daily budget contains only local date/count. Uncertain reserved claims conservatively consume a slot. No conversation content is stored in this budget.
- Source comparison and actual network observations are in `docs/research/eidolon-comparison-2026-10-22.md`. GitHub/Bing were reachable; the Java Core health endpoint at 18080 did not respond during inspection. Python 8000 was responding with mock LLM/native_pgvector health metadata. These observations do not verify a live desktop AI/web-search round trip.
- At the end of the initial local verification, no remote Core deployment, production message delivery, model-key change, dependency install, Git commit or push had been performed. The desktop and its selected Core both needed this update. An older Core returns an unavailable response and the desktop remains silent. Subsequent deployment is documented below.
- OpenSpec remains unarchived for user review and subsequent work.

## Authorized deployment and live networking verification

The user subsequently requested deployment and networking tests. Using change: `add-idle-companion-reach-outs`; verification follows `openspec-verify-change` (another change can be selected explicitly with `$openspec-verify-change <name>`).

| Dimension | Final result |
|---|---|
| Completeness | 17/17 tasks complete; all 6 requirements implemented and checked |
| Correctness | Original four companion requirements retain their regression coverage; regional deployment configuration, real model/web evidence and stream snapshot completion checked on active paths |
| Coherence | Existing credentials, gateway, persistence, provider implementation and UI controls reused; remote candidate checked before activation; original containers/images retained |

Additional requirement mapping:

| Requirement/scenario | Evidence |
|---|---|
| Validated search deployment / irrelevant HTTP-200 results | Same controlled Python query compared across Bing endpoints; installed local/remote launchers now set `https://cn.bing.com/search`; configuration check passes in PowerShell 7 and Windows PowerShell 5.1 |
| Live network evidence / search and answer succeed | Candidate, desktop gateway and active server reports each pass all five assertions; active desktop web lane has two results, active server one; answers cite relevant Python sources; official page independently returns 200 and contains the creation command |
| Live network evidence / stream closes early | Desktop completion is recorded as `snapshot.completed`, read from the same turn's completed snapshot under the existing SDK fallback contract; remote web turn receives `turn.completed`; missing SSE terminal events are not counted as received |

The active server image is `meguri-core-java:reachouts-cn-20261022-bdae7dcf6c77`. Its actual JAR SHA-256 matches the local packaged artifact: `bdae7dcf6c776179ad78c7b9404bb5944228963c7bb3dae307c0f6dd3d74e770`. Local Core and gateway are healthy; desktop restarted through its existing scheduled task with the updated renderer. Both active paths generate real reach-out candidates (HTTP 200) and reject acknowledgement after a new user turn (`accepted=false`). Tests disable formal-memory writes and send no real platform messages.

Full deployment identities, evidence paths and rollback instructions: `reports/reach-outs-deployment-2026-10-22.md`. Ignored raw evidence is in `.codex-runtime/deployment-20261022/`. No critical requirement or coherence issue remains. Operational limits are explicitly retained: partial web extraction failure, observed SSE early close with completed snapshot recovery, and no wall-clock wait for a natural idle timer delivery. Existing timing/race tests cover that scheduler. No archive was requested or performed.

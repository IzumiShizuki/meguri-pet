# Verification: stabilize-desktop-capability-routing

Date: 2026-10-18. Schema: spec-driven. Applied openspec-verify-change to proposal, design, tasks and delta spec.

## Scorecard

| Dimension | Result |
|---|---|
| Completeness | 16/16 tasks; all 4 requirements implemented |
| Correctness | All 14 scenarios have code and automated/live evidence |
| Coherence | All 9 design decisions followed; account/write boundaries preserved |
| Validation | Full suites and production builds pass; strict OpenSpec validation passes |

## Requirement/scenario mapping

| Scenario | Code and evidence |
|---|---|
| Screenshot query reaches chat | shared/reminder-intent.mjs:52; ordinary-chat regression, 19-case matrix, before/after browser and real original-query send |
| Genuine reminders | Same parser and renderer/services/reminder-flow.ts; Shanghai time/calendar, explicit confirmation, account change and duplicate-confirm tests |
| Review does not block unrelated chat | ChatDock routing, existing reminder-flow reconciliation tests, failed attachment retry, review-with-offline-chat.png |
| Current update lookup | CanonicalTurnPipeline.java:275 plus production/compatibility providers; TurnCanonicalPipelineIntegrationTest.currentInformationKeepsWebRetrievalWhenExecutionModeIsFast; explicit FAST/NONE regressions; live WEB lane |
| Unavailable/empty lookup | WebRetrievalProvider/adapter diagnostics and ProviderRequest.WebRetrievalSummary; parameterized outcomes, pipeline and LangChain4j prompt tests |
| Oversized retrieved pages | context/TokenBudgetAllocator.java:91; CompanionContextRuntimeTest real-tokenizer overflow and within-budget cases; actual failing live probe followed by completed turn |
| Conversational query | WebSearchPolicy.queryFor and both search boundaries; exact original/word-order/direct query captures; bounded closed-update-tail and retained-subject tests |
| System retrieval vs callable tools | Trusted ProviderRequest summary, LangChain4j system_web_retrieval; all six statuses, copies/digest tests; real response acknowledges searched tutorial and insufficient evidence |
| Explicit offline precedence | WebSearchPolicy guard before positive matches; WebSearchGatewayTest negatives; live-offline.json has no WEB lane |
| Direct online query | EXPLICIT_SEARCH/LOOKUP_WRAPPER; 联网查询/联网查/网上查询 eligibility and normalized production query tests |
| Visible slow lookup | Reactive conversation message, turn-progress.ts and ChatDock.vue:752; watchEffect/nextTick, public allowlist/max12/dedup regressions; final-ui-active.png/video |
| Terminal transition | finishTurnProgress and guarded finally; event→finish dedup, failure/cancel/stream timeout tests; final screenshot and released composer |
| Cancel during connection | conversation.ts:126 waitForRequest, controller/session guards, late-create cleanup; pending memory/hello/create cancel and stale-event/snapshot tests |
| Failed attachment send | lastUserText/lastAttachments, ChatDock send outcome; failed image/PDF preserves retry arguments; attachment/instruction reminder parser cases |

Web lane has its own bounded budget without extending local lanes or turn deadline (RetrievalRuntimeTest). Retrieved pages remain external/untrusted with provenance; required persona/security context remains strict. Existing reminder account, confirmation and uncertain-write reconciliation paths remain intact.

## Evidence

- Desktop: 416 pass/63 files; typecheck and production build pass.
- Java: 612 total, 607 pass/5 skip, no failures/errors; full package succeeds.
- Python: 492 pass/8 skip. Shared TS: 52 pass. Gateway: 27 pass/1 skip.
- Native preload, single-instance and tray checks pass; routing matrix 19/19.
- Live original query: accepted 202, real configured provider, FAST execution/SLOW retrieval, WEB result, controlled partial extraction diagnostic, turn.completed.
- Explicit no-web query completes without WEB lane. Browser uses preview native bridge plus real gateway/Core/model; native bridge checks are separate.

Detailed logs and limits are indexed in reports/desktop-capability-audit-2026-10-18.md. No verification dimension was skipped.

## Issues and limits

CRITICAL: none. WARNING (implementation/spec divergence or missing scenario): none.

Operational limitation: automatic approval review rejected restarting the verified old Core with blocked by policy. That runtime remains running; new code was validated in isolation and requires restart via the user's normal launch path.

Acceptance limits: no saved Shizuki login, so no real remote Todo write/QQ delivery; microphone, playback and PixPin hotkeys need device interaction. Search returned insufficient authoritative evidence for today's Codex release; the response correctly qualifies that result.

Suggestions: independent official-source/bounded-fallback improvement, code revision in health, connected account/device acceptance. These do not invalidate the scoped routing/recovery scenarios.

All scoped implementation checks passed. Change remains unarchived; no archive command was run.

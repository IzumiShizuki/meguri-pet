## 1. Contract and model boundary

- [x] 1.1 Add the ReAct prompt-skill dispatch and weather follow-up capability specs.
- [x] 1.2 Record the frozen snapshot, trust, budget, active-branch, and rollback decisions in the design.

## 2. ReAct skill dispatch

- [x] 2.1 Extend action validation to admit only safe exposed `PROMPT_SKILL` descriptors while retaining fail-closed checks for writes and approvals.
- [x] 2.2 Add Capability Runtime adapter paths that convert prompt-skill `content` and progressively disclosed external-Skill bodies into bounded normalized ReAct observations.
- [x] 2.3 Add the strict production planner route, preserve per-round capabilities/Skill candidates/observations, and emit sanitized skill traces.
- [x] 2.4 Add unit tests for multi-round prompt/external-Skill selection, strict planner rejection, budgets, and raw-payload/body redaction.

## 3. Weather follow-up context

- [x] 3.1 Implement active-path history resolution for narrow weather follow-up/correction messages.
- [x] 3.2 Wire history-aware weather classification and date resolution through turn preparation and retrieval/direct-weather paths.
- [x] 3.3 Add tests for tomorrow inheritance, explicit date override, ordinary-message isolation, and branch scope.

## 4. Verification and rollout

- [x] 4.1 Run focused Java tests and the full Java module test suite.
- [x] 4.2 Restart Meguri Core and verify health/build identity.
- [x] 4.3 Replay three weather turns and a multi-round skill scenario through the gateway; record any remaining limitation.

### Verification record (2026-08-13)

- Focused Java regression: 61 tests passed.
- Full Java module regression: 529 tests passed, 0 failed, 2 optional live tests skipped.
- OpenSpec strict validation passed.
- Restarted Java Core on `127.0.0.1:18080` and Meguri desktop gateway on `127.0.0.1:15173`; health reported Java runtime and build `meguri_v2_02c3db0c507d7c2d`.
- Gateway weather replay: “明天天气怎么样呢?” → tomorrow, “我问的是明天来着” → tomorrow, “那后天呢” → day after tomorrow.
- Gateway AGENT replay emitted `skill.completed` for `meguri.prompt.default` at ReAct round 1 and then completed the Turn; lifecycle data contained digests but no Skill body.
- Remaining live-test limitation: the local external Skill catalog was empty, so an imported external package was not exercised through the gateway. Frozen external-Skill L1/view/next-round behavior, scope rejection, digest/path checks, budget enforcement, and body redaction are covered by the Java component suite.

## Context

See proposal.md. Baseline: 62 desktop files / 388 tests pass, typecheck passes, protocol 52 pass, Python 492 pass / 8 skip. A direct parser assertion and browser interaction reproduce the exact false reminder interception. The working tree already contains extensive user work; edits must preserve it.

## Goals / Non-Goals

**Goals:** fix confirmed routing defects, retain web retrieval across execution modes, make supported progress visible, bound send cancellation/recovery and test the combination boundaries.

**Non-Goals:** introduce a new agent framework, change reminder write permissions, show internal chain of thought, or modify remote Todo data during live tests.

## Decisions

1. Require actual scheduling intent rather than `temporal && polite-action`. Keep explicit reminder commands and scheduled external actions; treat quoted reminder discussion and immediate lookup as chat. Retain existing Shanghai time parsing and write reconciliation.
2. Inspect execution-mode selection and canonical retrieval planning together. A latency optimization may reduce local retrieval but must retain an explicitly needed web lane. Keep `retrieval_mode=NONE` authoritative and preserve tested explicit overrides.
3. Preserve gateway diagnostics through the web retrieval adapter. Return unavailable/empty degradation codes instead of normalizing every non-ok outcome into successful empty results. Progress labels derive from structured events, not model-generated internal reasoning.
4. Add a small bounded per-turn progress model to the conversation store and display it with native accessible disclosure. Filter to an allowlist of public event fields; never display arbitrary event payloads or reasoning tokens.
5. Propagate cancellation/deadlines through pre-turn readiness, hello, create and event subscription. Optional readiness can degrade. A late create receipt should be cancelled where feasible. Test timeout/cancel with pending promises and a newer session.
6. Implement with two Luna delegates in disjoint areas: desktop parser/composer/progress/recovery and Java web routing/diagnostics. Root owns full-suite verification, live probes, visual QA and this change's documentation.
7. Real-model integration revealed `required RETRIEVAL context exceeds its source maximum` after live Web retrieval. Bound external retrieved content to the actual source/token budget before constructing mandatory context, retaining provenance and useful excerpts. Do not relax mandatory persona/security budgets or inject entire pages as required records. Verify with a real tokenizer / context assembly seam and a live provider turn.
8. Normalize conversational lookup requests at the production search boundary, retaining the subject, temporal qualifier and information intent. Live RSS inspection showed the unnormalized leading “帮我” dominated results with unrelated remote-support pages. Add trusted per-turn retrieval-state context separately from the callable capability snapshot: an empty tool-schema list must not imply no system web lookup. Result relevance and evidence sufficiency remain explicit constraints; a completed lookup does not establish a current fact by itself.
9. Explicit per-turn offline wording takes precedence over positive search/currentness terms. Recognize direct online query variants without interpreting a quoted or descriptive networking phrase as a directive. Keep the server's global disabled gate and explicit retrieval NONE authoritative.

## Risks / Trade-offs

- [Overly narrow scheduling recognition] → preserve existing reminder tests and add explicit scheduled-action negatives/positives.
- [Fast-path latency increases for fresh information] → only preserve web for policy-approved queries; retain existing result and timeout limits.
- [Optional event payloads vary] → consume known fields defensively; missing progress never fails a turn.
- [Windows long temp paths break Java HTTP selectors] → run tests using the existing launcher's short JDK socket directory.
- [Live runtime may use an older build] → report loaded runtime vs source separately; validate new runtime before claiming the live app was repaired.

## Migration Plan

Run regression tests, full desktop/protocol/Python/Core suites, desktop build, then isolated browser/native smoke checks. Restart only the required local Meguri processes after successful build and preserve user data; verify `/health` and a read-only web query. Roll back only this change's hunks if necessary; do not reset the user's working tree.

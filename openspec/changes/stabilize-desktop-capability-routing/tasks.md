## 1. Baseline and diagnosis

- [x] 1.1 Run desktop unit/type/protocol and Python suites; capture exact false-interception assertion and browser screenshots.
- [x] 1.2 Complete Java/native baseline, probe real web retrieval and document environment-specific failures.

## 2. Desktop repair — Luna

- [x] 2.1 Add failing regressions for ordinary queries versus explicit reminder/scheduled actions, fix parser interception and preserve Todo reconciliation.
- [x] 2.2 Add bounded public per-turn progress and accessible collapsible rendering for retrieval/tool/generation/terminal events.
- [x] 2.3 Make pre-turn cancellation and timeout recoverable, prevent stale responses affecting a newer turn and preserve failed drafts/attachments.
- [x] 2.4 Run focused desktop tests and typecheck; provide changed files and evidence.

## 3. Core web repair — Luna

- [x] 3.1 Reproduce policy-approved web retrieval being skipped across execution-mode/fast-path decisions with a failing regression.
- [x] 3.2 Preserve web retrieval for current-information requests while respecting disabled retrieval and explicit mode constraints.
- [x] 3.3 Preserve unavailable/empty web diagnostics and expose public retrieval progress/results without prompt or reasoning content.
- [x] 3.4 Run focused Java regressions with the existing short JDK socket path and document results.
- [x] 3.5 Fix real-tokenizer retrieval source-budget overflow revealed by live provider integration; add regression preserving bounded excerpts/provenance and core required context.
- [x] 3.6 Normalize conversational search queries on the production path and convey trustworthy per-turn web retrieval status independently of callable tool schemas; regress exact query, unavailable/insufficient evidence, explicit offline precedence and direct online query wording.

## 4. Integrated verification

- [x] 4.1 Run full desktop, protocol, Python, Core tests, native preload/instance/tray smoke and desktop production build.
- [x] 4.2 Verify browser send/review/Todo/error-retry/progress combinations and capture before/after evidence.
- [x] 4.3 Verify live or isolated updated Core web lookup, distinguish external search reachability from software defects and safely activate validated local changes when feasible. Isolated live probes pass; automatic approval review blocked restarting the old instance, which remains running.
- [x] 4.4 Write comprehensive audit report and use openspec-verify-change; keep change unarchived unless asked.

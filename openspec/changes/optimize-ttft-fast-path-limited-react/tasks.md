## 1. Contract And Baseline Gate

- [ ] 1.1 Validate every Java, Python, and TypeScript binding against `contracts/performance/v1` fixtures.
- [x] 1.2 Add release, commit, image, response-contract, prompt, model, provider, mode, retrieval, and client labels to the Turn trace.
- [ ] 1.3 Capture an unmodified current-path TTFT baseline with sample size and P50/P95/P99 before enabling any optimization.
- [x] 1.4 Record `NOT_MEASURED` rather than a numeric improvement when either side of the comparison is missing or incomparable.

## 2. TTFT Instrumentation

- [ ] 2.1 Instrument the canonical server timestamps and derived durations defined by the v1 contract.
- [ ] 2.2 Distinguish Provider first byte, first token, first-delta commit, SSE flush, client receive, and first visible render.
- [x] 2.3 Add privacy tests proving traces omit prompt text, conversation text, formal memory, sensitive tool inputs, and secrets.

## 3. Execution Mode And FAST Integration

- [x] 3.1 Implement the frozen `TurnExecutionMode`, `CurrentTurnSignals`, `ExecutionBudget`, and `ExecutionModeDecision` bindings.
- [x] 3.2 Implement deterministic priority and server clipping, including explicit-user no-upgrade rules.
- [x] 3.3 Add optional adapter execution-mode input and persist the resolved decision in events/traces.
- [x] 3.4 Prove FAST skips rewrite, rerank, Graph, Web, planner, Remote Agent, and bulk tool schema unless an audited mode decision permits otherwise.

## 4. Persona And Context Read Models

- [ ] 4.1 Implement version-keyed `PersonaBaseSnapshot` rebuild/invalidation without caching turn-dynamic inputs.
- [ ] 4.2 Compose `EffectivePersonaState` from the base snapshot plus current temporal, scene, override, client, and signal inputs.
- [ ] 4.3 Implement `ConversationContextReadModel` with write-time token counts and version invalidation.
- [ ] 4.4 Test deterministic full-harness fallback for quote, resume, topic link, edit, branch switch, and stale summary.

## 5. Retrieval Fast Path

- [ ] 5.1 Implement L0/L1/L2 gating and emit the frozen `RetrievalDecision` reason codes.
- [ ] 5.2 Start eligible original-query keyword, memory, and repository lanes without waiting for rewrite/embedding.
- [ ] 5.3 Implement configurable conditional reranking and versioned `EvidenceQuorum` policies from evaluation evidence.
- [ ] 5.4 Cancel or degrade late lanes after quorum while retaining ACL, ACTIVE-version, trust, citation, and trace checks.
- [ ] 5.5 Compare Recall@K, nDCG, bad cases, and TTFT against the baseline before rollout.

## 6. Provider, Delta, And SSE

- [x] 6.1 Audit the production Provider stream for blocking, aggregation, connection-pool waits, and release identity.
- [x] 6.2 Emit and durably append the first non-empty delta immediately before applying any later-token coalescing window.
- [ ] 6.3 Implement configurable subsequent-delta coalescing and byte-for-byte replay/terminal-state tests.
- [ ] 6.4 Verify SSE headers, proxy buffering settings, per-event flush, client immediate render, and AstrBot's separate platform metric.

## 7. Limited ReAct

- [x] 7.1 Implement the AGENT-only decision loop with the five frozen decisions and server-clipped budgets.
- [x] 7.2 Validate every action through capability snapshot, policy, schema, approval, idempotency, deadline, and cost/token limits.
- [x] 7.3 Add repeated-action detection, result reuse, no-new-information termination, cancellation propagation, and failure limits.
- [ ] 7.4 Persist normalized observations and treat remote-agent results as untrusted input.
- [x] 7.5 Isolate the Agent planner provider route, bounded concurrency/deadlines, and sanitized failure classification; prove no-Agent and saturated-generation behavior.
- [x] 7.6 Support an optional Planner secret file with an explicit outer-LLM secret fallback, documented without inline credentials.
- [x] 7.7 Restrict automatic Planner invocation to server-authoritative AGENT execution decisions.

## 8. Integration, Measurement, And Rollback

- [ ] 8.1 Run the Notion 08 matrix across FAST/NONE, THINK without tools, AGENT read-only, lane failures, provider 429/timeout, SSE replay, and remote-agent cancel/recovery.
- [ ] 8.2 Run Java, Python, TypeScript, contract, integration, quality, and performance suites with all flags off and each flag on independently.
- [ ] 8.3 Publish before/after TTFT sample size and P50/P95/P99 with matched release/workload/client definitions, plus quality and recovery results.
- [ ] 8.4 Set rollout thresholds only from the measured report and verify every flag returns to the current stable path when disabled.

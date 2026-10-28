## Context

See proposal.md. Event identity and ordered durable events follow ADR 002; memory remains behind the ADR 003 boundary; retrieval and execution budgets follow ADR 009. Existing runtime recovery tests and AstrBot simulated adapter tests are retained. The working tree contains unrelated pending work that must be preserved.

## Goals / Non-Goals

Goals: test boundaries together, fix reproduced defects at their owning seam, and prove that a failed or cancelled turn does not impair the next turn.

Non-goals: training new weights, changing production model selection, writing real QQ messages, disclosing hidden chain-of-thought, or claiming simulated tests prove production deployment.

## Decisions

1. Desktop resets use a generation/session identity captured before each async operation. New-conversation state is reset immediately; stale finally blocks and callbacks are ignored. Prefer extending the existing guards to replacing the store.
2. Exercise Java provider and RAG bridge through real loopback HTTP on OS-assigned ports with deterministic fake upstreams. Record model identity and prompt/context, not secret values. This makes timeout, malformed output and delayed callback cases reproducible without dependence on paid models.
3. The user clarified that the small model checks/rewrites the initial input, not agent planning or trained-model switching. Add a distinct bounded input-assistance route; keep raw text authoritative for permission, write-action intent, topic/history and web opt-out. Pass the checked text separately to generation (and only use it for retrieval where it cannot change source/policy decisions). Network/model/parse failures fall back to the original. No training registry or production route mutations are needed.
4. Reuse existing durable process-restart test infrastructure for the PostgreSQL boundary, and add missing multi-turn/session assertions at existing seams. Existing deployments remain read-only.
5. Use red-first focused regressions followed by relevant full suites. Results are reported as unit, boundary integration, actual service or untested; no aggregate pass count alone constitutes end-to-end evidence.
6. The general Core has no implicit input-model fallback to the planner. The explicit local desktop launcher selects its existing configured Flash endpoint for this newly requested input role while honoring an explicit disable/override. Compose leaves the input model empty until configured. This enables the requested local path after restart without selecting an unverified new model or altering production model routing.

## Risks / Trade-offs

- Timing assertions can be flaky → synchronize with latches/events; use generous upper deadlines and deterministic ordering.
- Provider cancellation can be cooperative → discard late results by identity and test the following turn.
- Runtime model configuration is startup-bound → explicitly document restart semantics; input checking and Agent planning keep separate model identities.
- Upstream can send headers then stall its body → enforce an outer reactive deadline for the entire input-check stage in addition to the request timeout, and verify cancellation frees the route.
- Existing production Core disables web search → retain this deployment finding separately from source fixes.

## Migration Plan

Run focused and broad suites, retain reports and OpenSpec verification, then deliver reviewable local changes. Any production activation requires the correct deployment path and is not implied by local green tests.

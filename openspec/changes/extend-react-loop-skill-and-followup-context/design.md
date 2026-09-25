## Context

See `proposal.md` for motivation. The Java runtime already has a bounded `LimitedReActRuntime`, a frozen `CapabilityRuntimeFacade.TurnCapabilities` token, normalized observations, and an active-path `SessionContextStore`. The current action validator deliberately admits only read tools/resources, while prompt skills are handled separately by `CanonicalTurnPipeline` before provider generation. Weather classification currently sees only the current message.

## Goals / Non-Goals

**Goals:**

- Make prompt-skill dispatch and external-Skill progressive disclosure first-class ReAct actions without creating a second capability authority.
- Give production providers a strict, replayable planner contract on every loop round.
- Keep each round deterministic, bounded, replay-safe, and observable.
- Resolve weather follow-ups from the durable active branch without changing the message DAG.

**Non-Goals:**

- Broadening the existing write-tool policy, enabling remote agents, bypassing approvals, or accepting arbitrary model-provided capabilities.
- Replacing the existing canonical one-shot prompt-skill preparation path.
- Introducing a weather-session cache, a new database table, or cross-branch context inference.

## Decisions

### 1. Extend the existing action gate, not the loop protocol

`LimitedReActRuntime` already calls the planner after every normalized observation and carries the observation list into each `ReactPlanningContext`. The defect is the validator's kind restriction, not a one-round loop. The validator will admit `PROMPT_SKILL` only when it is side-effect-free and approval-free; the existing frozen facade remains the execution authority.

Alternative: add a separate skill loop beside ReAct. Rejected because it would duplicate budgets, tracing, snapshot checks, and policy enforcement.

### 2. Use the prompt-skill context contract as the observation boundary

The ReAct adapter will call the frozen facade's ordinary `execute` path, then require the same bounded `content` contract used by canonical prompt assembly. The resulting summary is labeled as capability data and normalized by `DefaultObservationNormalizer`; no raw result is carried into the next round or trace.

Alternative: pass the prompt skill's raw result map to the planner. Rejected because raw capability data can contain unbounded or instruction-like fields.

### 3. Progressively disclose external Skills from the frozen Turn snapshot

Each planning round receives only planner-safe L1 candidate fields (`skillId`, `name`, `description`, and `tags`). If instructions are needed, the planner must select the already exposed `meguri.skill.view` capability. The Capability Runtime and `SkillDisclosureService` remain authoritative for Turn scope, candidate membership, revision, path, digest, and per-Turn disclosure ledger. Viewed content is normalized as `UNTRUSTED_EXTERNAL_SKILL`; its body is available to the next planner round but is redacted from persisted round traces and lifecycle events.

Alternative: inject all selected `SKILL.md` bodies into the first prompt. Rejected because it spends tokens before relevance is known and weakens the explicit disclosure and audit boundary.

### 4. Use an isolated strict provider planner contract

The production provider receives a bounded JSON projection of the goal, remaining budgets, latest-first normalized observations, exposed capability metadata, and external-Skill candidates. It must return exactly one strict JSON decision. Unknown fields, malformed actions, private free-form reasoning, and unsupported decisions fail closed. Planner calls share the provider's planner concurrency controls and telemetry but do not use the ordinary answer schema.

The Limited ReAct child budget is the component-wise intersection of the parent Turn budget and a hard maximum of six planning decisions, four actions, and six rounds. Smaller parent limits always win.

Alternative: infer actions from an ordinary assistant response. Rejected because parsing prose would be ambiguous and would mix user-visible generation with the authorization boundary.

### 5. Derive weather follow-up context from `SessionContextStore.recent`

The orchestrator will pass the current scope's bounded active-path messages to a new weather-context resolver. The resolver scans recent user messages from newest to oldest, recognizes only narrow correction/follow-up forms, and carries the original weather request as the effective date source. It does not write state, inspect sibling branches, or infer weather from assistant text alone.

Alternative: keep the previous weather query in a process map. Rejected because it would be lost on restart and could leak across branch/session scope.

### 6. Keep ordinary weather routing and ReAct routing separate

Weather detection will be centralized through the history-aware classifier at turn preparation and retrieval boundaries. Explicit weather requests retain the existing direct weather lane; only a resolved follow-up is rewritten for weather date resolution, while the original user message remains the immutable message and prompt input.

## Risks / Trade-offs

- [A prompt skill may return content that is semantically misleading] → Preserve the existing trust label and prompt-skill contract; ReAct never promotes observations to authority.
- [An external Skill body may contain prompt injection] → Label it as untrusted data, prohibit it from adding authority/capabilities, and redact its body from persisted traces.
- [More planning rounds increase latency and cost] → Use six/four as hard safety ceilings, retain smaller parent budgets, and keep Limited ReAct disabled by default.
- [Narrow follow-up matching can miss novel wording] → Prefer false negatives over accidental weather routing and add replay cases for the supported correction vocabulary.
- [History lookup adds a small synchronous read] → Use the existing bounded active-path projection and no external I/O.
- [Skill output can consume the same tool-call budget as a read tool] → This is intentional; the hard budget prevents skill chaining from expanding the turn.

## Migration Plan

1. Deploy with the existing Limited ReAct flag and all default flags unchanged.
2. Verify unit and integration tests, then replay the three weather turns and a multi-round prompt/external-Skill planner.
3. Roll back by disabling Limited ReAct; weather follow-up logic remains fail-closed for messages that do not match the narrow resolver.

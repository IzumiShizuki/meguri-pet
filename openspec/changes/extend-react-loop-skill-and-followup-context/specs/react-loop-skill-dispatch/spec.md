## Purpose

Allow a bounded AGENT ReAct loop to choose safe prompt skills or progressively disclose frozen external Skills after each observation while preserving the capability, policy, trust, and resource boundaries of the Turn.

## ADDED Requirements

### Requirement: ReAct can select an exposed prompt skill on every round

The system SHALL give the planner the current bounded observation history and the frozen exposed capability set on every AGENT ReAct planning round. A `CONTINUE` decision MAY name either an exposed read capability or an exposed side-effect-free prompt skill.

#### Scenario: Skill is selected after an earlier observation

- **WHEN** an AGENT planner receives a non-terminal observation and selects an exposed prompt skill on the next round
- **THEN** the runtime executes that skill and supplies its normalized observation to the following planner call

#### Scenario: No exposed skill is callable

- **WHEN** a planner proposes a prompt skill that is absent, deprecated, unhealthy, or not exposed in the frozen turn snapshot
- **THEN** the action is rejected before execution and no capability implementation is invoked

### Requirement: External Skills use frozen progressive disclosure

Every planning round SHALL receive only the planner-safe L1 fields of external-Skill candidates frozen for the Turn. The planner MAY request instructions only through an exposed `meguri.skill.view` action, and every view SHALL be checked against the frozen candidate set, revision, path, digest, and per-Turn disclosure ledger.

#### Scenario: External Skill is viewed between planner rounds

- **WHEN** a frozen L1 candidate is relevant and the planner selects `meguri.skill.view`
- **THEN** the next planner round receives its bounded normalized content as `UNTRUSTED_EXTERNAL_SKILL` data and may choose another authorized action or finalize

#### Scenario: Skill outside the frozen snapshot is requested

- **WHEN** the planner requests an external Skill or path that is not allowed by the frozen Turn snapshot
- **THEN** the disclosure fails closed and no unfrozen package content reaches the planner

### Requirement: Prompt skill execution preserves turn authority and budgets

Every ReAct skill invocation SHALL use the Turn's frozen capability snapshot and authenticated scope, validate the input schema, preserve the existing side-effect and approval policy, and consume the same remaining round, action, token, cost, cancellation, and absolute-deadline budgets as other ReAct actions. Limited ReAct v1 SHALL permit at most six planning decisions and four actions, with every smaller parent limit taking precedence.

#### Scenario: Invalid or unsafe skill proposal is fail-closed

- **WHEN** a proposed prompt skill has invalid input, a missing required scope, side effects, or an approval requirement
- **THEN** the runtime returns a sanitized rejection observation or terminal rejection and does not invoke the skill implementation

#### Scenario: Budget exhaustion stops further skill calls

- **WHEN** a prompt-skill result would exhaust the ReAct token, cost, tool-call, or deadline budget
- **THEN** the runtime records the bounded observation and prevents another planner or skill invocation beyond the applicable termination rule

#### Scenario: Parent budget is smaller than the ReAct ceiling

- **WHEN** a parent Turn permits fewer than six model calls or four actions
- **THEN** the ReAct loop uses the smaller parent limit and never expands it

### Requirement: Skill observations are bounded and untrusted to the planner

The runtime SHALL normalize prompt-skill and external-Skill output to a bounded summary and digest, SHALL discard raw payload details and external Skill bodies from the persisted ReAct trace, and SHALL label each observation as capability data rather than authority or instructions.

#### Scenario: Trace does not leak raw skill payload

- **WHEN** a prompt skill returns structured content containing additional fields or sensitive-looking values
- **THEN** the next planner receives only the normalized bounded observation and the trace contains no raw payload or action arguments

### Requirement: Production planning is strict and round-aware

The provider planner SHALL receive the current bounded observation history, frozen exposed capability projection, planner-safe external-Skill candidates, and remaining budget on every round. It SHALL return exactly one schema-valid decision and SHALL fail closed on malformed or extra output fields.

#### Scenario: A Skill observation changes the next action

- **WHEN** a prior round returns a normalized Skill observation
- **THEN** the next provider planning request contains that observation and may select a different exposed capability based on it

#### Scenario: Planner returns malformed JSON

- **WHEN** the provider response has unknown fields, an invalid decision/action combination, or non-JSON prose
- **THEN** the planner fails closed and no action executes

### Requirement: ReAct skill dispatch remains independently reversible

Disabling the Limited ReAct feature SHALL leave the stable non-ReAct path unchanged, and a skill execution failure SHALL not mutate the authoritative message DAG, persona state, or formal memory.

#### Scenario: ReAct flag is disabled

- **WHEN** a turn is not admitted to Limited ReAct because the feature is disabled
- **THEN** no ReAct planner or skill action runs and the canonical non-ReAct path handles the turn

#### Scenario: Skill failure does not mutate authority

- **WHEN** an admitted ReAct prompt skill times out, returns an invalid result, or fails
- **THEN** the turn remains within the bounded ReAct failure policy and the authoritative conversation and persona stores are unchanged

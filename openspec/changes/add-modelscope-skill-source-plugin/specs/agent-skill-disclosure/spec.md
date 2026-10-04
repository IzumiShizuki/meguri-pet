## Purpose

Expose relevant external Skill instructions progressively to authorized AGENT Turns while freezing versions, capabilities, references, and budgets against privilege expansion.

## ADDED Requirements

### Requirement: External Skills are selected only for server-authorized AGENT Turns
The system SHALL perform deterministic weighted retrieval only when the server has selected `AGENT` mode. It SHALL freeze at most eight compatible enabled candidates with Skill ID, revision, and digest at Turn start, and no Skill SHALL change execution mode or scope.

#### Scenario: Non-AGENT Turn receives no external Skill
- **WHEN** the server selects FAST or THINK mode
- **THEN** no external Skill candidates or Skill bodies are exposed

#### Scenario: Frozen candidate survives later catalog changes
- **WHEN** a candidate is disabled or updated after Turn start
- **THEN** the Turn uses only the candidate revision frozen at its start

### Requirement: Skill disclosure is bounded and read-only
The system SHALL expose `meguri.skill.search` for the frozen candidate set and `meguri.skill.view` for `SKILL.md` or safe package text. One Turn SHALL view at most three Skills and six referenced files under one shared 4096-token budget, returning truncation metadata when limits apply.

#### Scenario: Relevant Skill is progressively disclosed
- **WHEN** an AGENT query matches an enabled compatible Skill and the Agent views it
- **THEN** the bounded body is returned as untrusted external data and consumes the Turn's Skill budget

#### Scenario: Disclosure budget is exhausted
- **WHEN** a fourth Skill, seventh referenced file, or content past the shared token budget is requested
- **THEN** the request is rejected or truncated with a stable limit marker and no extra content is disclosed

### Requirement: External Skill text never becomes prompt authority
Skill bodies and references SHALL enter context as `USER_DATA / EXTERNAL_SKILL`, SHALL NOT be registered as trusted `PROMPT_SKILL`, and actions suggested by a Skill SHALL still be validated against the Turn's frozen Capability snapshot, scopes, approvals, budgets, and idempotency policy.

#### Scenario: Prompt injection cannot expand authority
- **WHEN** Skill text asks the model to ignore policy, enter AGENT mode, reveal a secret, execute a script, or call an unexposed tool
- **THEN** the request has no authority and the prohibited action is not executed

### Requirement: Approval wait preserves the frozen Skill snapshot
When a Turn waits for approval, the system SHALL persist selected Skill IDs, revisions, digests, viewed files, remaining Skill budget, and the frozen Capability snapshot identity. Resume SHALL restore that state and SHALL NOT reselect Skills or expand capabilities.

#### Scenario: Resume cannot gain a newly enabled capability
- **WHEN** a capability or Skill is enabled while a Turn waits for approval
- **THEN** the resumed Turn retains its original frozen set and remaining budget

### Requirement: Write tools remain fail-closed behind existing authority
A `WRITE_TOOL` action SHALL execute only when the descriptor is in the frozen snapshot, scopes authorize it, required approval is accepted, an idempotency key is valid, and all runtime budgets pass. Otherwise it SHALL not invoke the implementation.

#### Scenario: Skill proposes an unapproved write
- **WHEN** external Skill text leads the planner to propose a write without accepted approval or required idempotency
- **THEN** the existing Capability Runtime rejects the action before implementation

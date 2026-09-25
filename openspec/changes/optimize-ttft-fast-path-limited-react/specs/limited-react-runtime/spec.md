## Purpose

Enable a small, durable, auditable action/observation loop for explicit AGENT work while reusing existing capability, approval, budget, and remote-agent authority.

## ADDED Requirements

### Requirement: Only AGENT mode is eligible for Limited ReAct
The system SHALL reject ReAct entry for FAST and THINK modes; an AGENT decision provides eligibility only and does not itself grant capability permission.

#### Scenario: THINK model proposes a tool action
- **WHEN** a THINK Turn emits an action proposal
- **THEN** the proposal cannot start a ReAct loop and no tool executes through that proposal

### Requirement: Planner decisions use a closed vocabulary
Each loop decision SHALL be exactly one of `CONTINUE`, `FINALIZE`, `WAIT_FOR_APPROVAL`, `DELEGATE_AGENT`, or `FAIL`.

#### Scenario: Planner emits an unknown decision
- **WHEN** the model returns a decision outside the closed vocabulary
- **THEN** validation fails closed without executing an action

### Requirement: Every action crosses existing authority checks
Before execution, each action SHALL pass capability snapshot, authenticated policy, input schema, approval, idempotency, remaining deadline, token/cost/call limits, and cancellation checks.

#### Scenario: Write action lacks approval
- **WHEN** a proposed write capability requires approval that has not been granted
- **THEN** the runtime returns `WAIT_FOR_APPROVAL` and does not perform the write

### Requirement: The loop terminates within the frozen budget
The runtime SHALL terminate on FINALIZE, sufficient evidence, no new information, repeated action, exhausted round/tool/agent/token/cost budget, expired deadline, repeated capability failure, or user cancellation.

#### Scenario: Successful action repeats
- **WHEN** an identical successful action is proposed again within the same idempotency scope
- **THEN** the runtime reuses the stored result or terminates instead of executing the side effect twice

### Requirement: Observations remain untrusted
Tool and remote-agent results SHALL be normalized and labeled as untrusted observations and SHALL NOT directly mutate persona, relationship, formal memory, or another authoritative state.

#### Scenario: Remote agent requests a relationship upgrade
- **WHEN** a remote-agent result contains instructions to upgrade relationship state
- **THEN** the instruction is treated as untrusted data and cannot perform the authority mutation

### Requirement: Limited ReAct is independently reversible
The runtime SHALL be guarded by an independent feature flag whose disabled state returns to the current stable non-ReAct path rather than a mock success.

#### Scenario: Flag is disabled during rollout
- **WHEN** a new Turn would otherwise be AGENT/ReAct eligible
- **THEN** it uses the documented stable fallback and reports that Limited ReAct was disabled

### Requirement: Agent planning has an isolated bounded provider route
The system SHALL invoke optional Agent planning only after a server-authoritative `AGENT` execution decision and through a provider client and concurrency budget independent from ordinary response generation. A Skill description, SLOW retrieval, or ordinary provider request alone SHALL NOT invoke the planner. The planner route SHALL enforce a physical provider deadline shorter than its Turn-level deadline and SHALL use a bounded structured-output configuration. A valid no-Agent decision SHALL complete normally without an `agent.failed` event.

#### Scenario: Ordinary slow retrieval has no Agent decision
- **WHEN** a Turn uses SLOW retrieval without a server `AGENT` execution decision
- **THEN** the planner is not called and no Agent event is emitted

#### Scenario: Ordinary generation holds all response permits
- **WHEN** one or more ordinary streamed responses occupy every ordinary-generation concurrency permit
- **THEN** an eligible Agent planner can use its independent planner permit and is not queued behind those responses

#### Scenario: Planner returns no-Agent
- **WHEN** the planner returns a valid `invoke_agent=false` decision
- **THEN** the system continues without an Agent invocation and does not emit `agent.failed`

#### Scenario: Planner credential is omitted
- **WHEN** `MEGURI_AGENT_PLANNER_API_KEY_FILE` is unset or blank
- **THEN** the planner uses the credential loaded from `MEGURI_LLM_API_KEY_FILE` without exposing either secret

#### Scenario: Planner credential overrides the ordinary credential
- **WHEN** `MEGURI_AGENT_PLANNER_API_KEY_FILE` identifies a valid repository-external secret file
- **THEN** only the planner provider client uses that credential while ordinary generation retains `MEGURI_LLM_API_KEY_FILE`

#### Scenario: Planner provider deadline expires
- **WHEN** the planner provider does not produce a response before its physical deadline
- **THEN** the system records `AGENT_PLANNER_TIMEOUT` without exposing provider payloads or secrets and follows the documented safe fallback

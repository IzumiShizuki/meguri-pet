## Purpose

Separate low-latency conversation, deeper reasoning, and bounded external action into explicit server-authoritative modes without coupling them to retrieval depth or expanding permission.

## ADDED Requirements

### Requirement: Execution mode is independent from retrieval mode
The system SHALL represent `TurnExecutionMode` as `FAST | THINK | AGENT` and `RetrievalMode` as `NONE | FAST | SLOW`, and SHALL allow any policy-valid combination without treating the two enums as aliases.

#### Scenario: Deep reasoning uses light retrieval
- **WHEN** a Turn is resolved as `THINK` with `FAST` retrieval
- **THEN** the Turn may use deeper reasoning without becoming eligible for a tool loop

### Requirement: The server makes one explainable mode decision
The system SHALL resolve the mode using user request, Skill policy, deterministic rules, optional classifier candidate, and final server clipping in that priority order, and SHALL record stable reason codes.

#### Scenario: User explicitly requests FAST
- **WHEN** a model or classifier proposes AGENT for a user-explicit FAST Turn
- **THEN** the final decision remains FAST unless a separate audited escalation is authorized by server policy

### Requirement: Execution decisions carry bounded budgets
Each `ExecutionModeDecision` SHALL contain mode, reason codes, an `ExecutionBudget`, `user_explicit`, and `react_eligible`; its budget SHALL use the Turn's absolute deadline and server-clipped call, depth, token, and cost limits.

#### Scenario: Child execution receives a budget
- **WHEN** an AGENT Turn delegates work
- **THEN** the child deadline equals or precedes the parent absolute deadline and every child limit is a subset of the remaining parent budget

### Requirement: FAST omits heavyweight defaults
FAST execution SHALL default to no query rewrite model, external reranker, Graph, Web, Skill planner, Remote Agent, ReAct loop, or bulk capability schema exposure.

#### Scenario: Ordinary greeting follows FAST
- **WHEN** a greeting has sufficient current context and retrieval resolves to NONE
- **THEN** the trace proves the heavyweight stages did not run before the Provider request

### Requirement: THINK does not imply external action
THINK execution SHALL permit higher reasoning effort or a bounded plan/answer sequence but SHALL default to zero external tool calls and no action/observation loop.

#### Scenario: Architecture analysis needs no tool
- **WHEN** a THINK Turn has sufficient supplied context
- **THEN** it can complete with deeper reasoning and `react_eligible` false

### Requirement: Mode selection cannot expand authority
A client, Skill, classifier, or model SHALL NOT use mode selection to expand authenticated scopes, capability policy, approval, deadline, token, cost, or concurrency limits.

#### Scenario: Client requests AGENT without tool permission
- **WHEN** the client requests AGENT but the authenticated scope lacks tool permission
- **THEN** the server clips or rejects the decision without granting the missing permission

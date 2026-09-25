## Purpose

Provide reproducible, privacy-safe evidence for every stage between Turn receipt and the first user-visible semantic text so performance changes can be compared without guessing.

## ADDED Requirements

### Requirement: Release identity accompanies every latency trace
The system SHALL associate each `TurnLatencyTrace` with `release_id`, `git_commit`, `image_digest`, `response_contract_revision`, `prompt_revision`, model, provider, execution mode, retrieval mode, and client type.

#### Scenario: Trace is tied to the tested build
- **WHEN** a Turn is included in a TTFT report
- **THEN** the report can identify the exact code, image, prompt, response contract, model route, modes, and client used for that sample

### Requirement: TTFT uses canonical semantic boundaries
The system SHALL record the v1 timestamp names for Turn receipt/persistence, persona, retrieval gate, optional rewrite/retrieval, context build, capability exposure, Provider request/first byte/first token, first-delta persistence/SSE flush, client first-delta receipt, and client first render.

#### Scenario: Optional stage is skipped
- **WHEN** FAST execution skips query rewrite or retrieval
- **THEN** the corresponding timestamp is absent and a stable reason code identifies that the stage was intentionally skipped

### Requirement: Derived durations remain internally consistent
The system SHALL derive millisecond durations from the canonical timestamps and SHALL reject negative or causally impossible measurements.

#### Scenario: End-to-end TTFT is derived
- **WHEN** both `turn.received_at` and `client.first_render.at` are available
- **THEN** `end_to_end_ttft` equals their non-negative elapsed time under the same trace clock policy

#### Scenario: Client transport and render are separated
- **WHEN** a character-stream client reports both `client.first_delta_received_at` and `client.first_render.at`
- **THEN** network delivery and local rendering can be derived as separate non-negative durations

### Requirement: Trace payloads protect sensitive content
The system SHALL store identifiers, revisions, classifications, lengths, durations, and stable error codes, and SHALL NOT store complete prompts, chat text, formal memories, sensitive tool parameters, or secrets in performance traces.

#### Scenario: A tool request contains a secret
- **WHEN** the tool participates in a traced Turn
- **THEN** the trace contains timing and classification metadata but not the secret or original sensitive argument value

### Requirement: Performance claims use comparable distributions
The system SHALL report sample size and P50/P95/P99 for matched workload, release identity, model/provider, execution/retrieval mode, and client metric definitions before claiming an improvement.

#### Scenario: No valid baseline exists
- **WHEN** an optimized run lacks a comparable pre-change dataset
- **THEN** the improvement is reported as `NOT_MEASURED` rather than a fabricated duration or percentage

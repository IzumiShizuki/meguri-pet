## ADDED Requirements

### Requirement: Current-user constraint scope
The provider SHALL derive enforceable constraints only from recognized direct clauses in the current raw user message and SHALL NOT promote quotations, prior conversation, retrieval data or checked-input clarification.

#### Scenario: Quoted requirement
- **WHEN** a user asks to explain a quoted instruction containing a length request
- **THEN** that quoted request does not activate enforcement

### Requirement: Validated delivery
The provider SHALL validate exact literals, supported Han bounds and one-sentence constraints before emitting constrained text, allow at most one repair under the original deadline, and fail explicitly on persistent violation without trimming or padding.

#### Scenario: Length repair
- **WHEN** the generated reply has 265 Han characters for a 220–260 request
- **THEN** one repair is allowed and only a compliant reply is delivered

#### Scenario: Ordinary conversation
- **WHEN** no supported direct constraint is present
- **THEN** natural streaming, persona and existing context behavior remain unchanged

#### Scenario: Withdrawn or quoted requirement
- **WHEN** the current user withdraws a preceding constraint, or the apparent constraint occurs in a quote or fenced source
- **THEN** the old or quoted constraint is not enforced; quoted withdrawals do not cancel a direct outer request

#### Scenario: Literal and technical sentence boundaries
- **WHEN** a quoted literal has an unambiguous compatible suffix, or a valid sentence contains code, URLs, qualified names or decimals
- **THEN** trailing instructions do not become literal text and internal technical periods do not create extra sentences

### Requirement: Accurate call and lifecycle accounting
Each actual model call SHALL have exactly one telemetry outcome. Local validation SHALL NOT count as another call or stream interruption. Cancellation or deadline during repair SHALL suppress late delivery and permit a subsequent turn to recover. Repair limits SHALL be per candidate, allowing up to four answer-model calls for an explicitly requested A/B comparison.

#### Scenario: Two noncompliant responses
- **WHEN** two successful provider responses fail output validation
- **THEN** telemetry records two successes and zero additional provider failures, and the turn reports a constraint violation

#### Scenario: Interrupted repair
- **WHEN** cancellation or deadline wins during the repair call
- **THEN** no late reply is delivered, one terminal event is persisted and the next independent turn can complete

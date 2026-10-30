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

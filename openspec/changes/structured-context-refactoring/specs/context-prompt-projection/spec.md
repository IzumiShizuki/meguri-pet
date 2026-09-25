## Purpose

Keep the model-facing context compact and semantically useful while retaining trust, provenance, and replay metadata inside the server-side ContextBundle and trace.

## ADDED Requirements

### Requirement: Internal provenance is not model-visible prompt content
The system SHALL retain source IDs, trace IDs, database identifiers, and compression metadata in server-side context metadata, but SHALL omit those internal identifiers from the model-facing compact projection unless a user-facing citation is explicitly required.

#### Scenario: Structured fact is projected to the provider
- **WHEN** a structured summary enters a provider request
- **THEN** the model receives the compact fact projection and not the fact ID, source message IDs, or context trace metadata as prompt content

#### Scenario: Prompt trust is preserved
- **WHEN** a compact projection contains user, approved-memory, or external data
- **THEN** its existing trust and wrapping semantics remain unchanged

### Requirement: Canonical context is represented once
The provider request SHALL represent the ContextBundle once as canonical user-data blocks, SHALL keep Persona and policy blocks in their trusted authority layer, and SHALL preserve semantic prompt-digest stability across volatile trace IDs.

#### Scenario: Context is serialized for a provider
- **WHEN** a typed ProviderRequest is serialized
- **THEN** context content is not duplicated through a second legacy history field

#### Scenario: Trace ID changes only
- **WHEN** two semantically identical requests differ only in volatile trace identifiers
- **THEN** their canonical prompt digests remain equal

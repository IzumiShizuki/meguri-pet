## Purpose

Reduce retrieval time before Provider dispatch through layered gating, speculative work, conditional reranking, and evidence quorum while preserving provenance, ACL, and quality.

## ADDED Requirements

### Requirement: Retrieval uses layered gating
The system SHALL use deterministic L0 decisions first, local lightweight L1 classification only when needed, and a small-model L2 planner only for ambiguous, complex, multi-source, or AGENT requests.

#### Scenario: Greeting requires no evidence
- **WHEN** L0 classifies an ordinary greeting as retrieval NONE
- **THEN** rewrite, rerank, Graph, Web, and retrieval Provider calls are not started

### Requirement: Current input remains a primary signal
The retrieval gate SHALL evaluate the current user input even when prior conversation topics or cached projections indicate another domain.

#### Scenario: Code question follows casual chat
- **WHEN** the current Turn asks about repository code after a non-technical conversation
- **THEN** eligible repository or knowledge retrieval can start in that same Turn

### Requirement: Eligible original-query lanes start speculatively
Keyword/BM25, structured memory, and explicit repository lanes SHALL be allowed to start from the original query without waiting for rewrite or embedding, while retaining query-origin provenance.

#### Scenario: Rewrite finishes after keyword evidence
- **WHEN** original-query keyword evidence already satisfies the applicable quorum
- **THEN** the Provider path may proceed and the trace still identifies the evidence as original-query output

### Requirement: Reranking is conditional and evaluated
The system SHALL call an external reranker only when a versioned evaluation policy requires it, and SHALL NOT hard-code one threshold for all modes, datasets, providers, or clients.

#### Scenario: FAST has a clear top candidate
- **WHEN** a small candidate set has one trusted candidate clearly satisfying the configured policy
- **THEN** the retrieval decision records that reranking was skipped

### Requirement: Minimum evidence can complete the critical path
`EvidenceQuorum` SHALL define required source seats and minimum item counts, and the system MAY complete the critical-path `RetrievalBundle` before all lanes settle only after that quorum is satisfied.

#### Scenario: Required knowledge seats arrive first
- **WHEN** all required ACTIVE knowledge seats arrive while optional Web work is still pending
- **THEN** the critical path can proceed and optional work is cancelled or marked late/degraded in the trace

### Requirement: Early completion preserves security and provenance
Speculation, cancellation, rerank skipping, and quorum completion SHALL NOT widen tenant/user/scope ACL, accepted authority states, document versions, trust, citation, or evidence requirements.

#### Scenario: Fast untrusted result arrives before authorized evidence
- **WHEN** an untrusted or unauthorized lane returns first
- **THEN** it cannot satisfy a trusted quorum seat or enter context as authoritative evidence

## Purpose

Provide an offline bridge from explicitly consented user supervision to reviewed, reproducible model-training datasets with source provenance, revocation and separation from canonical data and evaluation.

## ADDED Requirements

### Requirement: Review before export
Imported feedback SHALL remain pending until explicit digest-bound human approval. Missing consent, withdrawn data, malformed responses, unsafe or unsupported targets MUST be rejected or quarantined. A free-text behavior note alone MUST NOT become an assistant training target.

#### Scenario: Approved selection or structured correction
- **WHEN** a reviewer approves a valid selected response or supplies and approves a schema-valid corrected response
- **THEN** the exporter can emit it as an SFT target with provenance and response-contract revision

#### Scenario: Note without target or withdrawn source
- **WHEN** the source has only a correction note, lacks consent or has been withdrawn
- **THEN** no assistant target is exported until a permitted reviewed target exists, and withdrawn data stays excluded

### Requirement: Reproducible grouped exports
The bridge SHALL redact sensitive inputs, deduplicate samples and split related conversation/scene groups without overlap. It MUST preserve canonical datasets, use only permitted evaluation metadata, and export preferences separately from SFT.

#### Scenario: Duplicate and shared conversation
- **WHEN** duplicate records and records sharing a conversation or scene are imported
- **THEN** duplicate targets are removed and the shared group appears in only one split

#### Scenario: Provenance and repeatability
- **WHEN** the same approved sources and review ledger are exported again
- **THEN** data content, grouping and hashes are deterministic and the manifest identifies sources, reviews and exclusion counts

### Requirement: Existing training gate compatibility
Feedback-augmented datasets SHALL be accepted only through an explicit manifest branch that validates their approved baseline parent, feedback hashes, review provenance and split separation. Existing canonical dataset gates MUST retain their strict behavior; preparing feedback MUST NOT train or promote a model.

#### Scenario: Augmented dataset validation
- **WHEN** a reviewed feedback export is combined with a valid derived baseline
- **THEN** the existing trainer accepts the verified augmented dataset and rejects tampering or an unverified baseline

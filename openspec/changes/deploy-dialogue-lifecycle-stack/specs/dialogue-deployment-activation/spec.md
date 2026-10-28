## Purpose

Activate validated dialogue lifecycle behavior in the actual desktop and AstrBot services, with explicit capability configuration, bounded acceptance checks and recoverable service replacement.

## ADDED Requirements

### Requirement: Activated services expose the intended capabilities
An authorized deployment SHALL activate the validated artifact with online retrieval and the separately configured input-check route, and SHALL verify both configuration and actual multi-turn behavior before reporting success.

#### Scenario: Remote conversation activation
- **WHEN** the deployed AstrBot Core is upgraded
- **THEN** web retrieval is enabled and a synthetic conversation can complete successive turns through the updated adapter with no real QQ delivery

#### Scenario: Desktop activation
- **WHEN** the desktop stack is upgraded
- **THEN** the updated Core and renderer can start a new session, complete a message and continue after reopening

### Requirement: Failed activation is recoverable
Deployment SHALL retain existing credentials and state boundaries and a usable rollback target, and SHALL report failed acceptance without labeling it successful.

#### Scenario: Candidate fails
- **WHEN** a candidate fails health or conversation checks
- **THEN** the old active service remains available or is restored and the failure is diagnosed before another activation

#### Scenario: Deployment exposes a code defect
- **WHEN** live acceptance reproduces a defect
- **THEN** a focused regression precedes the fix and the rebuilt artifact is revalidated after deployment

#### Scenario: Cancelled old send finishes after session reset
- **WHEN** a message is still generating and the user starts a new conversation
- **THEN** the old send completion SHALL NOT restore its text or attachments into the new conversation's composer, and a new message can be sent immediately

### Requirement: Search results survive unavailable page bodies
When search succeeds but every eligible page body is unavailable, the retrieval lane SHALL retain policy-approved search snippets with explicit snippet-only provenance and degradation, without claiming the page body was read. One stalled page SHALL NOT consume the entire lane budget.

#### Scenario: Page bodies unavailable
- **WHEN** eligible public search results have snippets but page extraction fails or returns empty
- **THEN** the result remains usable as clearly labeled search-summary evidence with a safe public diagnostic

#### Scenario: One body stalls
- **WHEN** one extractor never completes while another result is readable
- **THEN** bounded per-page extraction allows the readable result to complete within the lane deadline

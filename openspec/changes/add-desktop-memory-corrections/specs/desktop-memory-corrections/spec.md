## Purpose

Let the desktop owner understand the current formal memory version and correct mistaken facts through explicit review while preserving ownership, concurrency and provenance boundaries.

## ADDED Requirements

### Requirement: Scoped current-version explanation
The desktop SHALL show the selected memory's current text, version number, update time and available source client/session/kind. Access MUST verify the existing authenticated desktop owner and tenant. Detail responses SHALL use an explicit display projection and MUST NOT include raw provenance, structured content or actor identifiers.

#### Scenario: Owner opens memory details
- **WHEN** the authorized owner opens an existing memory
- **THEN** the current version and available source metadata are displayed, and absent provenance is labeled as unavailable

#### Scenario: Foreign or unauthorized access
- **WHEN** a request targets another owner's memory or supplies untrusted actor/scope fields
- **THEN** it is rejected without exposing that memory or forwarding a mutation

### Requirement: Reviewed optimistic correction
An owner correction SHALL contain nonblank bounded replacement text, a bounded reason, the observed base version and a stable request ID. It MUST create a supersede candidate through the authoritative review service. Submission MUST NOT change the active version, approve the candidate, or alter protected relationship fields. Repeated identical requests SHALL reuse the durable candidate, and stale/deleted versions SHALL be rejected according to existing state rules.

#### Scenario: Submit and confirm correction
- **WHEN** the owner submits a valid correction
- **THEN** it appears in candidates for explicit confirmation, while the old version remains active until review succeeds

#### Scenario: Concurrent version change
- **WHEN** the current memory version differs from the editor's base version at submission or approval
- **THEN** the authoritative service rejects the conflicting action and the UI retains the unsaved text

#### Scenario: Ambiguous network response
- **WHEN** submission may have succeeded but its response is lost
- **THEN** an unchanged retry uses the same request ID and does not create another candidate

### Requirement: Recoverable accessible desktop editing
Details and correction SHALL extend the existing settings list with labeled, keyboard-accessible inline controls. The owner MUST be able to cancel, see submission errors and explicitly reload a changed version. Switching sessions or memories SHALL discard the old editor binding and MUST NOT apply delayed responses to the new selection. The UI MUST NOT claim a pending correction has already changed formal memory.

#### Scenario: Service fails while editing
- **WHEN** the detail or correction request fails
- **THEN** a readable error is shown, retry is available and any entered draft remains available until cancel or selection change

#### Scenario: Session changes during a request
- **WHEN** the active desktop session changes before a response arrives
- **THEN** the old editor is removed and stale list/detail data is not displayed in the new session

#### Scenario: Older backend is still running
- **WHEN** the selected Core or authoritative bridge does not advertise the detail and correction capabilities
- **THEN** the desktop hides the new entry while preserving the existing memory management controls

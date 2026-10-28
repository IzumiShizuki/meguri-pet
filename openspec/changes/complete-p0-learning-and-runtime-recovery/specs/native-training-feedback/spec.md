## Purpose

Let native desktop users optionally supervise a reply with explicit consent and recoverable, withdrawable feedback while maintaining authenticated ownership and keeping model learning distinct from formal memory.

## ADDED Requirements

### Requirement: Optional native supervision
The native desktop SHALL offer an opt-in training mode and optional per-reply comparisons or corrections. Submitting data for training MUST require explicit consent and a saved receipt. Ordinary conversation MUST remain usable without feedback.

#### Scenario: Consented preference or correction
- **WHEN** the owner selects a reply or submits a nonempty correction and explicitly consents
- **THEN** the scoped feedback is saved and a stable receipt is displayed

#### Scenario: No consent
- **WHEN** training consent is absent or withdrawn before submission
- **THEN** no training candidate is saved

### Requirement: Durable owned feedback
Pending comparisons and accepted receipts SHALL survive service reconstruction/restart. Submissions and withdrawals MUST be scoped to the authenticated user/client/session and idempotent; conflicting retries MUST fail without overwriting prior feedback.

#### Scenario: Recovered pending comparison
- **WHEN** the Core is reconstructed after registering a comparison
- **THEN** its owner can retrieve that comparison and submit feedback

#### Scenario: Repeated request and other owner
- **WHEN** the same request is retried or a different owner requests the comparison
- **THEN** the original receipt is returned for the identical retry and the different owner is denied

#### Scenario: Withdrawal
- **WHEN** the owner withdraws saved feedback and retries the withdrawal
- **THEN** it remains withdrawn, carries a durable exclusion marker and cannot be newly exported for training

#### Scenario: Save failure
- **WHEN** durable feedback persistence fails
- **THEN** no success receipt is returned and the comparison remains available for retry

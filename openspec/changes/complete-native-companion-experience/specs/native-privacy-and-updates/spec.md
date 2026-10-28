## Purpose

Expose accurate native desktop data choices, a readable privacy policy, sanitized diagnostic export and reliable release detection without adding analytics.

## ADDED Requirements

### Requirement: Privacy choices and withdrawal
The application SHALL present the policy on first launch and explain local history, configured remote AI processing and optional formal memory. Until the user accepts remote chat use, new remote chat requests MUST be blocked with a visible explanation. The user SHALL be able to withdraw remote chat and memory consent and disable local history retention, clearing local history only through an explicit action. No analytics SDK SHALL be added.

#### Scenario: First launch
- **WHEN** no current policy acceptance is stored
- **THEN** the user sees the policy and individual choices before using networked conversation.

#### Scenario: Withdraw permission
- **WHEN** the user withdraws remote chat or formal memory permission
- **THEN** subsequent related requests stop while existing user data is retained unless explicitly removed.

### Requirement: Sanitized diagnostics
The application SHALL export bounded diagnostics through a user-chosen file destination, containing application/runtime versions, readiness and sanitized failures. Credentials, raw conversation text, private config content, account tokens and full user paths MUST be excluded or redacted.

#### Scenario: Export feedback information
- **WHEN** the user exports diagnostics
- **THEN** a readable file is produced without secret or conversation data and its outcome is displayed.

### Requirement: Accurate release checks
The application SHALL allow manual GitHub release checks and one silent startup check when the corresponding network preference permits it. It MUST compare semantic versions correctly, open only a verified release URL, and distinguish available update, current version, no published release, unavailable service and rate limits. It MUST not install or publish a release.

#### Scenario: New release
- **WHEN** an eligible stable release is newer than the current application
- **THEN** the app offers its version and verified release page.

#### Scenario: Release service failure
- **WHEN** GitHub returns no release, a rate limit or another failure
- **THEN** the corresponding state is shown without falsely reporting that the app is current.

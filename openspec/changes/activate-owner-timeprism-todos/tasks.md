## 1. Verified owner and configuration

- [x] 1.1 Authenticate the selected existing account and identify its user ID without exposing credentials.
- [x] 1.2 Verify the exact real private operator binding and prepare restricted external secret files and rollback assets.

## 2. Writer reliability

- [x] 2.1 Add real loopback HTTP writer/controller coverage for receipts, owner mismatch, duplicates and uncertain reconciliation.
- [x] 2.2 Reproduce stalled response bodies, repair bounded complete-body consumption while preserving size limits and verify later-call recovery.

## 3. Activation and acceptance

- [x] 3.1 Stage and activate the tested Core plus owner/operator configuration, preserving ordinary conversations.
- [x] 3.2 Validate preview, confirmation, idempotent receipt and cleanup through synthetic adapter events and a local collector with zero QQ sends.
- [x] 3.3 Run applicable suites, verify OpenSpec and record final state, limitations and rollback steps.

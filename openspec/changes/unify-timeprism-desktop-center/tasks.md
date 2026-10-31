## 1. Contract
- [x] 1.1 Confirm authority, auth, nullable deadline and existing version/notes gaps; freeze shared contract.
- [x] 1.2 Align site implementation and client fixtures without changing IDs or creating credentials.
## 2. Desktop
- [x] 2.1 Add capability-gated center API with immutable account-scoped journal and cache.
- [x] 2.2 Add tasks/agreements/priority and note editing through existing Todo button.
- [x] 2.3 Expose offline/pending/conflict/retry state with no silent overwrite.
## 3. Acceptance
- [x] 3.1 Run meaningful fake-server and renderer regression, typecheck/build and security boundary checks.
- [x] 3.2 Coordinate authoritative backend, MCP and independent client contract acceptance.
- [ ] 3.3 Prepare backup/rollback and deploy only through approved supported paths; record exact untested gates.
- [ ] 3.4 Preserve user changes, commit scoped files and update report.

- [ ] 3.5 Validate physical PostgreSQL migration/concurrency and real multi-client account synchronization after approved deployment.

## 4. MCP error receipts
- [x] 4.1 Preserve bounded UUID-bound error details as untrusted data without weakening approval, write outcome or retry policy.
- [x] 4.2 Verify executor, transport and approval regression with isolated HTTP fixtures and full Java suite.

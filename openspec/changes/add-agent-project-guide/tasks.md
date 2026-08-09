## 1. Agent Guide Structure

- [x] 1.1 Add `.agent/README.md` with first-read order, quick repository map, and task-start checklist.
- [x] 1.2 Add `.agent/architecture.md` with runtime ownership, module boundaries, and authority/data-flow notes.
- [x] 1.3 Add `.agent/workflows.md` with local setup, run, test, benchmark, and build commands.
- [x] 1.4 Add `.agent/guardrails.md` with secret, offline-default, external-state, OpenSpec, and Git safety rules.

## 2. Verification

- [x] 2.1 Check every referenced repository path and command against the current workspace.
- [x] 2.2 Run `openspec validate --change add-agent-project-guide` and confirm the change status is complete.
- [x] 2.3 Confirm the guide contains no secrets, private credentials, generated local state, or stale branch-specific claims.

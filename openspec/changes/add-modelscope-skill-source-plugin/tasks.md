## 1. Prerequisite and contracts

- [x] 1.1 Complete and verify `extend-react-loop-skill-and-followup-context`, including six decisions, four actions, prompt-skill observations, weather follow-up context, and safe write authority.
- [x] 1.2 Validate this change's proposal, four capability specs, design, and tasks with strict OpenSpec validation.

## 2. Catalog and validation

- [x] 2.1 Add the Skill domain models, source plugin/registry contracts, service configuration, and feature flag.
- [x] 2.2 Implement immutable digest package storage and strict package/frontmatter validation.
- [x] 2.3 Implement tool/env compatibility evaluation, license warnings, activation, disablement, update import, and rollback lifecycle.
- [x] 2.4 Add local-mock and PostgreSQL repositories, migration SQL, and metadata-only audit records.
- [x] 2.5 Add catalog, validator, malicious-package, lifecycle, persistence, and restart tests.

## 3. ModelScope source and bridge

- [x] 3.1 Add the constrained Python `modelscope-hub` download bridge with internal authentication, staging isolation, limits, and tests.
- [x] 3.2 Implement ModelScope OpenAPI search/detail, bridge fetch, update checks, DTO normalization, timeout/error mapping, and fixture tests.
- [x] 3.3 Add optional network acceptance for `@MiniMax-AI/minimax-pdf` while keeping default CI offline.

## 4. Agent disclosure

- [x] 4.1 Implement deterministic AGENT-only selection and freeze at most eight compatible L1 candidates by revision/digest.
- [x] 4.2 Register and implement read-only `meguri.skill.search` and `meguri.skill.view` over the frozen Turn snapshot.
- [x] 4.3 Enforce three-Skill, six-reference, 4096-token disclosure budgets and `USER_DATA / EXTERNAL_SKILL` trust labels.
- [x] 4.4 Persist disclosure state across approval wait/resume without re-selection or capability expansion.
- [x] 4.5 Add selection, irrelevance, injection, budget, frozen-update, disablement, restart, and rollback tests.

## 5. APIs and desktop

- [x] 5.1 Add all scoped internal browse/import/list/detail/activate/disable/update endpoints and stable errors.
- [x] 5.2 Add the `desktop-meguri` Skills route, typed API client, state, ModelScope search/detail, import progress, reports, lifecycle actions, and revision history.
- [x] 5.3 Add keyboard, responsive, reduced-motion, loading/empty/error UX and desktop component/API tests.

## 6. Verification and rollout

- [x] 6.1 Run focused and full Java, Python, and desktop test suites plus format/type checks.
- [x] 6.2 Run strict OpenSpec verification and static secret/path/content-leak checks.
- [x] 6.3 Start local Core with the source disabled and enabled against fixtures; verify health and rollback controls.
- [x] 6.4 Run the optional public ModelScope acceptance when networking is explicitly enabled and record any remaining limitation.

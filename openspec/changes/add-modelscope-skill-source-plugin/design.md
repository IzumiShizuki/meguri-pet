## Context

See `proposal.md`. Meguri already has a frozen Capability Runtime, server-owned execution modes, approval/idempotency enforcement, untrusted context labels, a Java Core plus a small Python internal service, PostgreSQL/local-mock persistence seams, and an in-progress native desktop shell. External Skill text must fit those seams rather than become a second Agent runtime.

## Goals / Non-Goals

**Goals:**

- Make source integration replaceable while keeping Java Core authoritative for validation, catalog state, runtime selection, and audit.
- Preserve exact package bytes and activated history through immutable SHA-256 revisions.
- Make disclosure useful without eagerly injecting all Skill bodies into prompts.
- Keep local operation available during remote catalog outages.

**Non-Goals:**

- Hot-loading third-party JARs, installing or running Skill scripts, installing tool/MCP dependencies, or trusting publishers.
- Private ModelScope content, collection sync, publishing, automatic updates, automatic activation, or redistribution without a license.
- Replacing the stable Agent/Capability path or adding a generic shell executor.

## Decisions

### 1. Compile-time Java source plugins behind one interface

`SkillSourcePlugin` provides `search`, `detail`, `fetch`, and `checkUpdate`; Spring discovers implementations as beans. `SkillSourceRegistry` rejects duplicate source IDs. V1 ships only `ModelScopeSkillSourcePlugin`.

Alternative: load arbitrary plugin JARs from disk. Rejected because classloader isolation, signing, dependency conflicts, and runtime code trust are outside V1.

### 2. Split transport from package authority

Java calls ModelScope OpenAPI for search/detail. Fetch calls an authenticated Python internal bridge that uses `modelscope-hub`; the bridge only downloads into a request-scoped staging root. Java walks the result without following links, validates paths/types/limits, computes a canonical package digest, then copies accepted bytes into `<data-root>/skills/packages/<sha256>` using create-if-absent semantics. Java alone persists revision state.

Alternative: let Python validate and write catalog records. Rejected because it would create two authorities and complicate rollback consistency.

### 3. Separate identity, revision, binding, and report

`SkillManifest` is normalized descriptive metadata; `SkillRevision` points to one immutable digest and validation state; `SkillBinding` stores enabled digest; `SkillValidationReport` contains stable issues and compatibility; `SkillRequirement` models tools/env; `FrozenSkillSnapshot` stores a Turn's candidates, viewed references, and budget.

Activation never mutates a revision. One binding transition selects a validated compatible digest. Updates add a revision; rollback is the same activation command over an older digest.

### 4. Strict parser and file policy

Only one root `SKILL.md` is allowed. Frontmatter is parsed as data with bounded scalar/list/object depth; unknown keys are preserved only as inert metadata. The validator rejects absolute/traversal paths, links, device paths, duplicate canonical paths, executable/binary signatures, files above 1 MiB, packages above 8 MiB or 256 files, and digest mismatches. Text reads use UTF-8 with bounded replacement and never invoke files.

Alternative: trust archive extraction or MIME extension alone. Rejected because both are bypassable.

### 5. Runtime catalog registers two read tools, not imported prompt skills

At AGENT Turn preparation, `SkillSelectionService` scores enabled compatible manifests using deterministic token overlap plus exact tag/title boosts and freezes up to eight L1 summaries. Two existing-runtime descriptors—`meguri.skill.search` and `meguri.skill.view`—operate only on the frozen snapshot. Returned blocks are `USER_DATA / EXTERNAL_SKILL` and share a 4096-token, three-Skill, six-reference ledger.

External packages never become `PROMPT_SKILL`, because that kind is reserved for trusted local executable prompt capabilities.

### 6. Resume stores disclosure state beside Turn authority

The frozen snapshot serializes candidate revision/digest, selected IDs, referenced normalized paths, remaining tokens, and Capability snapshot ID. Approval waiting persists this structure with the Turn record. Resume verifies snapshot identity and consumes remaining counters; it never queries the live catalog.

The ReAct budget is hard-clipped to six model decisions and four actions. `WRITE_TOOL` remains executable only through the existing Capability Runtime after frozen exposure, scope, approval, budget, and idempotency checks. External Skill text does not satisfy any of these checks.

### 7. One management service backs HTTP and desktop

`SkillManagementService` owns search/detail/import/list/activate/disable/update flows. Controllers map `skill:read` and `skill:manage` scopes. The local profile uses a synchronized in-memory repository; PostgreSQL uses normalized Skill/revision/binding/report/audit tables. Bodies stay on disk and out of database/audit rows.

The native desktop adds a first-class Skills route using its existing fetch and store conventions. The visual direction is an archival field notebook: restrained ink/ivory surfaces, digest/revision metadata, strong status labels, and a two-pane search/detail layout that collapses to one column.

## Risks / Trade-offs

- [ModelScope OpenAPI fields evolve] → Normalize through tolerant DTOs, require stable external ID, and fixture unknown/missing fields.
- [Downloaded content contains prompt injection] → Preserve external trust labels, disclose only on demand, and enforce actions exclusively through frozen capabilities.
- [Python bridge is absent] → Return `BRIDGE_UNAVAILABLE`; browsing and installed local revisions remain available.
- [Digest storage grows] → Keep immutable history for audit/rollback in V1; add an explicit retention policy later rather than silent deletion.
- [Token-overlap retrieval misses semantic matches] → Prefer deterministic false negatives in V1; scoring is replaceable without changing snapshot contracts.
- [Desktop shell prerequisite changes] → Keep Skills state/API isolated behind a route component and do not modify AIRI fallback.

## Migration Plan

1. Deploy database tables and data-root directories with the source flag false.
2. Deploy Python bridge and Java source/catalog APIs; verify fixture import and malicious packages.
3. Deploy runtime disclosure capabilities and desktop route, still disabled for ModelScope.
4. Enable in an explicit local/staging profile and run the optional public Skill acceptance.
5. Roll back by disabling `meguri.skills.modelscope.enabled`, disabling affected bindings, or activating a prior valid digest. Do not delete packages or audit rows.

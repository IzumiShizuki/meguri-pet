## Context

See proposal.md. Existing Notion sources are B (3aaa3636-5963-81e5-b3a7-efdfd076c848), Turn Runtime 5 (3a8a3636-5963-8144-aad4-eecda46c7c1d), E (3aaa3636-5963-811a-a9f6-fd9cc581961c) and the 2026-09-17 learning supplement (3dea3636-5963-8109-a595-fa495612ba18). B keeps training offline and preserves locked evaluation; the supplement requires optional visible and revocable feedback; 5 makes PostgreSQL the Turn authority. The native desktop already sends the shared protocol, which supports training_mode. Legacy feedback comparisons live in a volatile Java map and consented submissions are appended to local JSONL. Existing PostgreSQL repositories and post-reply workers should be reused.

## Goals / Non-Goals

**Goals:** Complete the native feedback vertical slice; make its supervision recoverable; produce reviewed data usable by the existing trainer; enable and verify explicit durable runtime configuration; preserve user-owned changes and distinguish code evidence from deployment evidence.

**Non-Goals:** Companion Director, inferred presentation-preference learning, unrestricted agents, daily retraining, real GPU experiments, model promotion and production deployment are outside these three P0 items. Formal memory remains governed by its existing Python service.

## Decisions

1. Native feedback uses the existing optional training_mode and comparison events, plus an authenticated desktop control API for retrieving, saving and withdrawing supervision. Gateway routes are allowlisted and scope is bound server-side. A separate UI consent is required for each submitted training candidate. Hiding a panel is not treated as approval.
2. Pending comparisons and receipts use a durable local training store with atomic replacement/append and stable submission identity. This storage holds training candidates, not Turn authority. Persist before returning success; preserve pending comparisons on persistence failure. Keep legacy capture compatibility where safe, but never trust caller-supplied ownership.
3. The feedback bridge is an explicit offline import -> review -> export workflow. Review decisions bind source digests; withdrawals exclude candidates. Selected valid replies or structured reviewer corrections provide targets; unstructured notes remain review evidence. Group by conversation/scene and emit preference pairs separately.
4. Existing exact-count baseline training validation stays intact. A separate feedback-augmented manifest branch verifies a canonical-derived baseline parent and reviewed-feedback provenance before entering the same tokenizer/trainer. Source exports and locked evaluation content stay untouched.
5. Reuse existing PostgreSQL Turn/context/job repositories. Add runtime-specific secret-file configuration with explicit activation and fail-closed diagnostics. Do not reuse the deployed memory application's limited-role credentials for runtime DDL. Test offline invariants first; any real database check must use an isolated test scope and record its evidence.
6. Notion updates preserve historical designs. Add a dated learning/status page with the five explanatory diagrams and implementation evidence, then append concise dated corrections/links to the existing main/A, B, 5 and E sources. Avoid claiming the whole 2026-09-17 proposed design is implemented.
7. Full Core restart acceptance additionally covers Spring wiring and Capability bootstrap. The ContextRuntimePersistence facade is the primary injection entry for the shared PostgreSQL session/context instance. Compare persisted Capability descriptors by their typed domain value, preserving set semantics across JVM serialization order while rejecting actual same-version metadata changes.
8. The actual Windows entry uses Windows PowerShell 5.1. Preserve UTF-8 BOM on the localized Runtime configuration script and select the bounded char-array Split overload explicitly; validate configuration under both Windows PowerShell 5.1 and PowerShell 7. Following the user's explicit server activation request, reuse administrator credentials from the existing synced project config for provisioning only, and keep the new restricted Runtime connection in its own external secret file.

## Risks / Trade-offs

- Existing dirty files overlap native UI/backend work -> narrow ownership, coordinated APIs, no resets, review task-specific additions.
- Feedback contains private conversation data -> consent, redaction, scoped access, withdrawal and ignored local artifacts; never upload raw feedback to Notion.
- Withdrawal cannot undo an already trained model -> exclude future exports and identify affected manifest provenance; no automatic retraining or promotion claim.
- PostgreSQL acceptance may require unavailable tooling -> prepare reproducible isolated checks and record the exact verification boundary; do not install a replacement toolchain or mutate live memory data as a test.

## Migration Plan

Keep new user-facing training mode off by default. Preserve existing feedback schema compatibility during import, with strict known consent provenance. Enable durable Turn storage only through dedicated runtime configuration; a mode rollback must be explicit. Run language/module checks and OpenSpec verification before reporting completion. Notion keeps the before-state and appends tested after-state plus remaining environment gates.

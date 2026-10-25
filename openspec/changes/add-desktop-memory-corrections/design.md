## Context

See proposal.md. Formal memory already has owner-scoped reads, version IDs, reviewed supersede candidates and durable idempotency. Desktop management currently exposes only compatibility records and candidate approve/reject. Python owns the authoritative state; Java verifies desktop identity and forwards a bounded request. The worktree contains prior uncommitted features and must remain intact.

## Goals / Non-Goals

**Goals:** Make the current version understandable, make owner corrections actionable, and retain conflict/approval rules across the trusted bridge.

**Non-Goals:** Version-history browsing, per-answer recall explanations, modifying recall weights, automatic correction approval, changing other clients, database schema changes, or unsolicited production memory writes during tests.

## Decisions

1. Add GET `/v1/memory/desktop/memories/{uuid}` and POST `/v1/memory/desktop/memories/{uuid}/corrections`. The POST accepts only `base_version_id`, `content_text` (1–4000 trimmed characters), and `change_reason` (1–1000), with the existing required `X-Request-ID`. Actor, owner, scope and source identifiers are constructed server-side. Returning 200 for the candidate preserves the existing desktop bridge convention; submitting is not approval.
   The local gateway has a closed memory-operation allowlist. Extend only the GET detail and POST correction patterns, retaining Electron/desktop checks, server-side credential injection and all existing method/path rejections. Test through a separate gateway process against an isolated loopback Core fixture.
2. Python detail returns an allowlisted display projection of the current item/version and source client/session/kind. No raw provenance, structured content, actor IDs, other-user lookup or all-version dump crosses this endpoint. Missing source metadata is honestly represented as unavailable.
3. Forward correction to `propose_supersede(MemoryUpdate)` with a required observed version. Existing state checks and later approval transaction reject stale versions. Reuse the existing durable request idempotency and separate explicit review; do not introduce a direct text update API. Version contents remain untouched until approval.
4. Extend the incumbent MemorySettings list with an inline detail/editor component. Keep the current visual system, focus styles, typography and layout. Explain the review step before submission; preserve inputs and a stable request ID on retry, rotate it when inputs change, and disable other mutations while editing. Capture the memory ID and session at editor mount; session changes clear the editor and ignore stale list responses. Avoid a modal and avoid persisting typed memory content locally.
5. Test actual bridge/controller contracts, unauthorized access, invalid/unknown fields, no implicit review, stale versions and UI retry behavior. Existing authoritative service tests cover candidate approval and immutable version replacement; add a boundary integration test using the real service when fixtures permit.
6. Advertise `memory_detail` and `memory_corrections` through the existing protected status response. Core forwards only recognized bridge capabilities; the renderer reveals the new entry only when both are present. An older Core/bridge therefore keeps existing management usable without exposing unavailable operations during rollout. The gateway must still be upgraded in the coordinated release.

## Risks / Trade-offs

- Version changes while editing → server rejects with existing conflict code; retain the draft and let the owner explicitly reload current version before resubmission.
- Ambiguous timeout after submission → stable request ID retries the same operation; no automatic retries with changed text.
- Unsupported older Core/bridge → ordinary error with retained draft; existing memory management continues to work.
- Large or sensitive provenance → explicit projection; tests include metadata that must never appear in the response.
- Unattended development → follow the user's authorization to implement and validate in the new branch; use synthetic isolated records for mutation tests.

## Migration Plan

No migration or dependency installation. Validate Python bridge, Core, gateway and desktop together; build both runtime artifacts. Existing deployed runtime continues until a coordinated release of the bridge, Core, gateway and renderer is selected. Rollback removes the new routes/UI; persisted corrections remain ordinary candidates/versions understood by the existing service.

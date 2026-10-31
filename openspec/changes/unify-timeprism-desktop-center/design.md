## Context
See proposal.md and docs/contracts/timeprism-unified-center-v1.md. Site remains the authority; current pet already has a Todo button, account encryption and pure snapshot access.

## Goals / Non-Goals
Goals: protected tasks/notes editing, nullable deadlines, deterministic urgency ordering, visible offline and conflict state with immutable request replay.
Non-goals: creating credentials, unknown installations, global exactly-once notifications or silently approving deployment/UAC.

## Decisions
A dedicated desktop center client composes the existing authenticated request function, rather than exposing tokens to the renderer. It probes contract capabilities before sending protected writes. A bounded atomic local store keeps account-keyed cache and pending request bodies without credentials; a conflict remains a retained draft and never auto-adopts a newer version. Existing server IDs remain authoritative. The existing Todo panel embeds the new center; no edit to user-modified ChatDock/Galgame/styles files is required. Old reminder methods remain compatibility paths until replaced through a separately verified capability transition.

Use the site request schema exactly. New center writes require immutable request UUID and explicit version for existing resources. A fresh online capability probe is required before dispatch; previously verified capability metadata can allow an offline draft to be queued, never establish success. Cache is marked stale and not used as a successful write receipt.

## Risks / Trade-offs
[Site not deployed] -> show read-only/unsupported status, no fallback to unsafe writes.
[Conflict or uncertain network response] -> retain body and request identity; expose explicit retry and edit-copy flows.
[Local cache includes private note text] -> use existing user data directory, bounded atomic storage and account separation; no credentials in cache.
[Legacy single tasks use Shanghai] -> preserve current semantics; recurring records retain their IANA zone.

## Migration Plan
Build and test against fake server fixtures, then cross-repo contract acceptance. Deploy site additive migrations only with backup/rollback and coordinated authorization, then load pet via supported path. No destructive migration or new permissions.

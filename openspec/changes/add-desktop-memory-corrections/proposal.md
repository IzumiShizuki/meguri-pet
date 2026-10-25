## Why

The desktop can approve and delete formal memories but cannot explain the current version or correct a mistaken fact. The Eidolon comparison identified a useful next step: expose existing version/provenance semantics and close the owner's correction loop without bypassing memory review.

## What Changes

- Add owner-scoped desktop detail and correction endpoints over the existing authoritative memory provider.
- Show bounded version, source and update information using an explicit display projection, excluding raw provenance.
- Let the owner propose a correction against an observed base version, then confirm or reject the resulting candidate through the existing review controls.
- Preserve typed drafts on errors, reject stale versions, and test identity isolation, retries and review semantics.

## Capabilities

### New Capabilities

- `desktop-memory-corrections`: Explain the current formal memory version and submit reviewed, concurrency-safe corrections from the desktop.

### Modified Capabilities

None. Existing general memory review and persistence rules remain authoritative.

## Impact

Python desktop memory bridge, Java desktop memory controller, the existing gateway's explicit memory-route allowlist, renderer contracts/API/settings and focused integration tests. No database migration, new dependency, automatic approval, or change to recall ranking. Existing uncommitted project work is preserved on `codex/companion-memory-corrections`.

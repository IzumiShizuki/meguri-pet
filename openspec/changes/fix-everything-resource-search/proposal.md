## Why

The local-resource feature can return `No safe local resource matched the query.` even when Everything has matching files on a local drive. Users therefore cannot reliably attach local resources that have already been indexed by their desktop search service.

## What Changes

- Preserve valid Everything search results from all locally indexed drive roots through the resource-selection pipeline.
- Apply explicit safety rules to a result's canonical local path instead of rejecting it because its root differs from a narrow default workspace list.
- Keep metadata-only behavior, confirmation requirements, result limits, and all existing protected-path exclusions.
- Return a diagnosable reason when matches are excluded by the safety policy.

## Capabilities

### New Capabilities

- `safe-everything-resource-search`: Select safe metadata-only local resources returned by Everything regardless of local drive location.

### Modified Capabilities

- None.

## Impact

- Everything search gateway and local-resource selection policy in `java/meguri-core`.
- AIRI resource selector responses and tests; no content-reading capability or new external dependency is introduced.

## Context

The local-resource selector is metadata-only and confirmation-gated, but a valid Everything result can be rejected before it reaches the selector. See `proposal.md` for motivation and `specs/safe-everything-resource-search/spec.md` for the behavior contract.

## Goals / Non-Goals

**Goals:**

- Make acceptance depend on canonical-path safety rather than an incidental workspace-root allow-list.
- Preserve the existing no-content-read boundary and protected-directory exclusions.
- Provide deterministic filtering tests with candidate paths from more than one local drive.

**Non-Goals:**

- Enabling network shares, removable-media discovery, directory selection, or file-content ingestion.
- Changing Everything index configuration or bypassing the user's existing Everything service.

## Decisions

### Separate result discovery from path safety

The gateway will retain the count of raw Everything matches and apply a single canonical-path safety policy before exposing metadata. This permits a clear distinction between no matches and unsafe matches. Treating the configured workspace list as the safety policy was rejected because it makes legitimate local-drive files invisible.

### Allow only canonical local regular files

The policy will allow canonical paths on locally mounted volumes while rejecting UNC paths, non-files, and protected system/application/credential/runtime locations. A broad unrestricted path allow-list was rejected because the resource feature must remain metadata-only and safe by default.

### Keep error details non-sensitive

User-visible empty results will identify the category of exclusion but will not reveal protected paths. Detailed candidate reasons remain testable through structured internal results or logs.

## Risks / Trade-offs

- [A broad local-drive rule exposes sensitive files] → Canonicalize first, retain protected roots, and require the existing confirmation before attachment.
- [Everything returns stale or inaccessible entries] → Skip inaccessible/non-regular paths and continue evaluating remaining matches.
- [Drive classification differs between Windows configurations] → Use the platform's canonical path and filesystem metadata rather than drive-letter text alone.

## Migration Plan

1. Add a failing fixture for a safe file outside the former workspace roots.
2. Refactor the filter around canonical local-path safety and empty-set diagnostics.
3. Run the resource gateway/controller tests and the Java module suite.
4. Deploy with the existing desktop confirmation flow unchanged; rollback restores the former restrictive filter.

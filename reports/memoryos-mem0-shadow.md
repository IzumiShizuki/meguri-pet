# MemoryOS import and Mem0 shadow evidence

Date: 2026-07-14  
Milestone: M-011

## Authority boundary

- PostgreSQL + pgvector remains the only owner of formal memory IDs, immutable versions, deletion state and audit state.
- `ExistingMemoryOSAdapter` supports health, retrieval and journal reads. `upsert`, `supersede` and `delete` all raise `MemoryOSUnsupportedOperation` before any HTTP write request.
- `MemoryOSImporter` reads journal records and translates supported active records to `memory_candidates` with `source_kind=memoryos_import`, original source IDs in provenance and `pending_review` as the expected destination state. Transient/unsupported types are skipped.
- `Mem0ShadowEvaluator` calls the authoritative search independently, hashes the query and content fingerprints for comparison, and emits only aggregate counts, overlap and latency. Shadow hits are not returned as prompt memories and the evaluator has no mutation path.

## Offline verification

The automated fixtures verify:

1. MemoryOS import invokes only the read interface and creates a review candidate with source provenance.
2. All MemoryOS mutation methods fail without an HTTP request.
3. Disabling Mem0 makes no sidecar call while authoritative results remain available.
4. A Mem0 error becomes the stable `sidecar_failure` code; exception text and credentials are not emitted.
5. Shadow output contains aggregate comparison data and no recalled content.

No live MemoryOS or Mem0 instance was mutated during this work. No claim is made about live-sidecar latency or recall quality; those require environment handoff and an approved evaluation corpus.

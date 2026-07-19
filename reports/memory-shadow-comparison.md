# Authoritative versus sidecar shadow comparison

Date: 2026-07-14

Offline fixtures compare native-authority hits with Mem0-style shadow hits using normalized content fingerprints. The fixed case produced two authoritative hits, two shadow hits, one overlap and overlap@k of 0.5. Only query/content hashes and aggregate counts/latency leave the evaluator; recalled text is not emitted.

MemoryOS is now read-only and imports supported journal records as `pending_review` candidates with original IDs in provenance. Mem0 is disabled by default and has no mutation or Prompt-injection path. Sidecar failure yields `sidecar_failure` without exception details while authoritative results remain available.

This is interface/isolation evidence, not a live quality decision. Live recall quality and latency remain unmeasured until an approved corpus and sidecar environment exist.

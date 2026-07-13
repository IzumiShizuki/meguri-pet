# ADR 004: Existing MemoryOS

Status: evaluated for local shadow use; production authority rejected.

The running file-backed MemoryOS instance is treated as external production state. Source inspection found append, retrieve, profile and journal operations, but no stable record IDs, update/supersede API, delete API or application-layer authentication. The local `ExistingMemoryOSAdapter` therefore supports only explicit shadow evaluation and never calls the wrapper's internal `respond` LLM route. It cannot be the authoritative provider until ownership, authentication, backup/restore and deletion semantics are proven. Do not modify, migrate, restart or delete the instance.
